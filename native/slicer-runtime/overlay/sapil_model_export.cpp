#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <limits>
#include <string>
#include <utility>
#include <vector>

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace sapil {
Slic3r::Model& getGlobalModel();
bool isModelLoaded();
}

namespace {

constexpr std::size_t MAX_EXPORTED_OBJECTS = 256;
constexpr std::size_t MAX_EXPORTED_VOLUMES_PER_OBJECT = 64;
constexpr int MAX_FILAMENT_SLOTS = 16;
constexpr std::uint64_t MAX_EXPORTED_BYTES = 512ULL * 1024ULL * 1024ULL;
constexpr std::uint64_t BINARY_STL_HEADER_BYTES = 84;
constexpr std::uint64_t BINARY_STL_TRIANGLE_BYTES = 50;

struct ExportRecord {
    std::string path;
    std::string name;
    double center_x;
    double center_y;
};

struct ProjectExportRecord {
    std::string path;
    std::string object_name;
    std::string volume_name;
    double center_x;
    double center_y;
    int filament_slot;
    std::size_t object_ordinal;
};

std::string safe_name(std::string name, std::size_t fallback_index)
{
    for (char& value : name) {
        if (value == '\t' || value == '\r' || value == '\n' || value == '\0') value = ' ';
    }
    if (name.empty()) name = "Object " + std::to_string(fallback_index + 1);
    if (name.size() > 200) name.resize(200);
    return name;
}

void remove_outputs(const std::vector<std::string>& paths)
{
    for (const std::string& path : paths) std::remove(path.c_str());
}

bool append_export(
    const std::string& directory,
    const char* file_prefix,
    std::size_t file_index,
    Slic3r::TriangleMesh&& mesh,
    std::uint64_t triangles,
    std::string name,
    std::uint64_t& total_bytes,
    std::vector<ExportRecord>& records,
    std::vector<std::string>& outputs)
{
    if (triangles == 0 || mesh.empty()) return false;
    const std::uint64_t output_bytes =
        BINARY_STL_HEADER_BYTES + triangles * BINARY_STL_TRIANGLE_BYTES;
    if (output_bytes > MAX_EXPORTED_BYTES - total_bytes) return false;

    char file_name[40];
    std::snprintf(file_name, sizeof(file_name), "%s-%03zu.stl", file_prefix, file_index);
    const std::string path = directory + "/" + file_name;
    if (!mesh.write_binary(path.c_str())) return false;
    std::ifstream written(path, std::ios::binary | std::ios::ate);
    if (!written || static_cast<std::uint64_t>(written.tellg()) != output_bytes) {
        std::remove(path.c_str());
        return false;
    }
    const Slic3r::BoundingBoxf3 bounds = mesh.bounding_box();
    if (!bounds.defined) {
        std::remove(path.c_str());
        return false;
    }
    records.push_back({
        path,
        std::move(name),
        bounds.center().x(),
        bounds.center().y(),
    });
    outputs.push_back(path);
    total_bytes += output_bytes;
    return true;
}

jobjectArray encode_records(
    JNIEnv* env,
    const std::vector<ExportRecord>& records,
    const std::vector<std::string>& outputs)
{
    if (records.empty() || records.size() > MAX_EXPORTED_OBJECTS) {
        remove_outputs(outputs);
        return nullptr;
    }
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        remove_outputs(outputs);
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(records.size(), string_class, nullptr);
    if (result == nullptr) {
        remove_outputs(outputs);
        return nullptr;
    }
    for (std::size_t index = 0; index < records.size(); ++index) {
        const ExportRecord& record = records[index];
        const std::string encoded = record.path + "\t" + record.name + "\t" +
            std::to_string(record.center_x) + "\t" + std::to_string(record.center_y);
        jstring value = env->NewStringUTF(encoded.c_str());
        if (value == nullptr) {
            remove_outputs(outputs);
            return nullptr;
        }
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

jobjectArray encode_project_records(
    JNIEnv* env,
    const std::vector<ProjectExportRecord>& records,
    const std::vector<std::string>& outputs)
{
    if (records.empty() || records.size() > MAX_EXPORTED_OBJECTS) {
        remove_outputs(outputs);
        return nullptr;
    }
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        remove_outputs(outputs);
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(records.size(), string_class, nullptr);
    if (result == nullptr) {
        remove_outputs(outputs);
        return nullptr;
    }
    for (std::size_t index = 0; index < records.size(); ++index) {
        const ProjectExportRecord& record = records[index];
        const std::string encoded = record.path + "\t" + record.object_name + "\t" +
            record.volume_name + "\t" + std::to_string(record.center_x) + "\t" +
            std::to_string(record.center_y) + "\t" + std::to_string(record.filament_slot) +
            "\t" + std::to_string(record.object_ordinal);
        jstring value = env->NewStringUTF(encoded.c_str());
        if (value == nullptr) {
            remove_outputs(outputs);
            return nullptr;
        }
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeExportLoadedObjects(
    JNIEnv* env,
    jobject,
    jstring output_directory)
{
    if (!sapil::isModelLoaded() || output_directory == nullptr) return nullptr;
    const char* directory_chars = env->GetStringUTFChars(output_directory, nullptr);
    if (directory_chars == nullptr) return nullptr;
    const std::string directory(directory_chars);
    env->ReleaseStringUTFChars(output_directory, directory_chars);
    if (directory.empty() || directory.size() > 900) return nullptr;

    std::vector<ExportRecord> records;
    std::vector<std::string> outputs;
    std::uint64_t total_bytes = 0;

    const Slic3r::Model& model = sapil::getGlobalModel();
    std::size_t instance_count = 0;
    for (const Slic3r::ModelObject* object : model.objects) {
        instance_count += object == nullptr ? 0 : object->instances.size();
    }
    if (instance_count == 0 || instance_count > MAX_EXPORTED_OBJECTS) return nullptr;

    for (std::size_t object_index = 0; object_index < model.objects.size(); ++object_index) {
        const Slic3r::ModelObject* object = model.objects[object_index];
        if (object == nullptr) continue;
        for (std::size_t instance_index = 0; instance_index < object->instances.size(); ++instance_index) {
            const Slic3r::ModelInstance* instance = object->instances[instance_index];
            if (instance == nullptr) continue;
            const Slic3r::Transform3d instance_transform =
                instance->get_transformation().get_matrix();
            Slic3r::TriangleMesh exported;
            std::uint64_t triangles = 0;
            for (const Slic3r::ModelVolume* volume : object->volumes) {
                if (volume == nullptr || !volume->is_model_part()) continue;
                const std::size_t volume_triangles = volume->mesh().facets_count();
                if (volume_triangles > (MAX_EXPORTED_BYTES - BINARY_STL_HEADER_BYTES) /
                        BINARY_STL_TRIANGLE_BYTES - triangles) {
                    remove_outputs(outputs);
                    return nullptr;
                }
                triangles += volume_triangles;
                Slic3r::TriangleMesh part = volume->mesh();
                part.transform(instance_transform * volume->get_matrix(), true);
                exported.merge(part);
            }
            if (triangles == 0 || exported.empty()) continue;
            std::string name = safe_name(object->name, object_index);
            if (object->instances.size() > 1) {
                name += " " + std::to_string(instance_index + 1);
            }
            if (!append_export(
                    directory,
                    "object",
                    records.size(),
                    std::move(exported),
                    triangles,
                    std::move(name),
                    total_bytes,
                    records,
                    outputs)) {
                remove_outputs(outputs);
                return nullptr;
            }
        }
    }
    return encode_records(env, records, outputs);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeExportLoadedProjectVolumes(
    JNIEnv* env,
    jobject,
    jstring output_directory)
{
    if (!sapil::isModelLoaded() || output_directory == nullptr) return nullptr;
    const char* directory_chars = env->GetStringUTFChars(output_directory, nullptr);
    if (directory_chars == nullptr) return nullptr;
    const std::string directory(directory_chars);
    env->ReleaseStringUTFChars(output_directory, directory_chars);
    if (directory.empty() || directory.size() > 900) return nullptr;

    std::vector<ExportRecord> flat_records;
    std::vector<ProjectExportRecord> project_records;
    std::vector<std::string> outputs;
    std::uint64_t total_bytes = 0;
    std::size_t object_ordinal = 0;

    const Slic3r::Model& model = sapil::getGlobalModel();
    for (std::size_t object_index = 0; object_index < model.objects.size(); ++object_index) {
        const Slic3r::ModelObject* object = model.objects[object_index];
        if (object == nullptr) continue;
        const std::size_t model_part_count = std::count_if(
            object->volumes.begin(),
            object->volumes.end(),
            [](const Slic3r::ModelVolume* volume) {
                return volume != nullptr && volume->is_model_part();
            });
        if (model_part_count == 0) continue;
        if (model_part_count > MAX_EXPORTED_VOLUMES_PER_OBJECT) {
            remove_outputs(outputs);
            return nullptr;
        }
        for (std::size_t instance_index = 0; instance_index < object->instances.size(); ++instance_index) {
            const Slic3r::ModelInstance* instance = object->instances[instance_index];
            if (instance == nullptr || object_ordinal >= MAX_EXPORTED_OBJECTS ||
                project_records.size() > MAX_EXPORTED_OBJECTS - model_part_count) {
                remove_outputs(outputs);
                return nullptr;
            }
            const Slic3r::Transform3d instance_transform =
                instance->get_transformation().get_matrix();
            Slic3r::BoundingBoxf3 object_bounds;
            const std::size_t record_begin = project_records.size();
            std::string object_name = safe_name(object->name, object_index);
            if (object->instances.size() > 1) {
                object_name += " " + std::to_string(instance_index + 1);
            }
            std::size_t model_part_ordinal = 0;
            for (std::size_t volume_index = 0; volume_index < object->volumes.size(); ++volume_index) {
                const Slic3r::ModelVolume* volume = object->volumes[volume_index];
                if (volume == nullptr || !volume->is_model_part()) continue;
                const std::size_t triangles = volume->mesh().facets_count();
                if (triangles > (MAX_EXPORTED_BYTES - BINARY_STL_HEADER_BYTES) /
                        BINARY_STL_TRIANGLE_BYTES) {
                    remove_outputs(outputs);
                    return nullptr;
                }
                const Slic3r::Transform3d world_transform =
                    instance_transform * volume->get_matrix();
                Slic3r::TriangleMesh exported = volume->mesh();
                exported.transform(world_transform, true);
                object_bounds.merge(exported.bounding_box());
                if (!append_export(
                        directory,
                        "project-volume",
                        project_records.size(),
                        std::move(exported),
                        triangles,
                        safe_name(volume->name, model_part_ordinal),
                        total_bytes,
                        flat_records,
                        outputs)) {
                    remove_outputs(outputs);
                    return nullptr;
                }
                int extruder = volume->config.has("extruder") ? volume->config.extruder() :
                    (object->config.has("extruder") ? object->config.extruder() : 1);
                if (extruder <= 0) extruder = 1;
                if (extruder > MAX_FILAMENT_SLOTS) {
                    remove_outputs(outputs);
                    return nullptr;
                }
                const ExportRecord& flat = flat_records.back();
                project_records.push_back({
                    flat.path,
                    object_name,
                    flat.name,
                    0.0,
                    0.0,
                    extruder - 1,
                    object_ordinal,
                });
                ++model_part_ordinal;
            }
            if (!object_bounds.defined || project_records.size() == record_begin) {
                remove_outputs(outputs);
                return nullptr;
            }
            const Slic3r::Vec3d center = object_bounds.center();
            for (std::size_t index = record_begin; index < project_records.size(); ++index) {
                project_records[index].center_x = center.x();
                project_records[index].center_y = center.y();
            }
            ++object_ordinal;
        }
    }
    return encode_project_records(env, project_records, outputs);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetUnsupportedProjectSemanticCount(
    JNIEnv*,
    jobject)
{
    if (!sapil::isModelLoaded()) return -1;
    std::size_t unsupported = 0;
    const Slic3r::Model& model = sapil::getGlobalModel();
    for (const Slic3r::ModelObject* object : model.objects) {
        if (object == nullptr) continue;
        for (const Slic3r::ModelVolume* volume : object->volumes) {
            if (volume != nullptr && (
                    !volume->is_model_part() ||
                    volume->is_fdm_support_painted() ||
                    volume->is_seam_painted() ||
                    volume->is_mm_painted() ||
                    volume->is_fuzzy_skin_painted())) {
                ++unsupported;
            }
        }
    }
    if (unsupported > static_cast<std::size_t>(std::numeric_limits<jint>::max())) return -1;
    return static_cast<jint>(unsupported);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeExportObjectVolumeRange(
    JNIEnv* env,
    jobject,
    jstring output_directory,
    jint object_index,
    jint start_volume_index,
    jint volume_count)
{
    if (!sapil::isModelLoaded() || output_directory == nullptr || object_index < 0 ||
        start_volume_index < 0 || volume_count <= 0 ||
        volume_count > static_cast<jint>(MAX_EXPORTED_OBJECTS)) {
        return nullptr;
    }
    const char* directory_chars = env->GetStringUTFChars(output_directory, nullptr);
    if (directory_chars == nullptr) return nullptr;
    const std::string directory(directory_chars);
    env->ReleaseStringUTFChars(output_directory, directory_chars);
    if (directory.empty() || directory.size() > 900) return nullptr;

    const Slic3r::Model& model = sapil::getGlobalModel();
    if (static_cast<std::size_t>(object_index) >= model.objects.size()) return nullptr;
    const Slic3r::ModelObject* object = model.objects[static_cast<std::size_t>(object_index)];
    if (object == nullptr || object->instances.size() != 1) return nullptr;
    const std::size_t start = static_cast<std::size_t>(start_volume_index);
    const std::size_t count = static_cast<std::size_t>(volume_count);
    if (start > object->volumes.size() || count > object->volumes.size() - start) return nullptr;

    std::vector<ExportRecord> records;
    std::vector<std::string> outputs;
    records.reserve(count);
    outputs.reserve(count);
    std::uint64_t total_bytes = 0;
    const Slic3r::Transform3d instance_transform =
        object->instances.front()->get_transformation().get_matrix();
    for (std::size_t offset = 0; offset < count; ++offset) {
        const std::size_t volume_index = start + offset;
        const Slic3r::ModelVolume* volume = object->volumes[volume_index];
        if (volume == nullptr || !volume->is_model_part()) {
            remove_outputs(outputs);
            return nullptr;
        }
        const std::size_t triangles = volume->mesh().facets_count();
        if (triangles > (MAX_EXPORTED_BYTES - BINARY_STL_HEADER_BYTES) /
                BINARY_STL_TRIANGLE_BYTES) {
            remove_outputs(outputs);
            return nullptr;
        }
        Slic3r::TriangleMesh exported = volume->mesh();
        exported.transform(instance_transform * volume->get_matrix(), true);
        if (!append_export(
                directory,
                "volume",
                offset,
                std::move(exported),
                triangles,
                safe_name(volume->name, volume_index),
                total_bytes,
                records,
                outputs)) {
            remove_outputs(outputs);
            return nullptr;
        }
    }
    return encode_records(env, records, outputs);
}
