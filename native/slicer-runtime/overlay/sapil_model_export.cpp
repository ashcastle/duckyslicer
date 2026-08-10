#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace sapil {
Slic3r::Model& getGlobalModel();
bool isModelLoaded();
}

namespace {

constexpr std::size_t MAX_EXPORTED_OBJECTS = 256;
constexpr std::uint64_t MAX_EXPORTED_BYTES = 512ULL * 1024ULL * 1024ULL;
constexpr std::uint64_t BINARY_STL_HEADER_BYTES = 84;
constexpr std::uint64_t BINARY_STL_TRIANGLE_BYTES = 50;

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

    struct ExportRecord {
        std::string path;
        std::string name;
        double center_x;
        double center_y;
    };
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
            const std::uint64_t output_bytes = BINARY_STL_HEADER_BYTES +
                triangles * BINARY_STL_TRIANGLE_BYTES;
            if (output_bytes > MAX_EXPORTED_BYTES - total_bytes) {
                remove_outputs(outputs);
                return nullptr;
            }

            char file_name[32];
            std::snprintf(file_name, sizeof(file_name), "object-%03zu.stl", records.size());
            const std::string path = directory + "/" + file_name;
            if (!exported.write_binary(path.c_str())) {
                remove_outputs(outputs);
                return nullptr;
            }
            std::ifstream written(path, std::ios::binary | std::ios::ate);
            if (!written || static_cast<std::uint64_t>(written.tellg()) != output_bytes) {
                std::remove(path.c_str());
                remove_outputs(outputs);
                return nullptr;
            }
            const Slic3r::BoundingBoxf3 bounds = exported.bounding_box();
            if (!bounds.defined) {
                std::remove(path.c_str());
                remove_outputs(outputs);
                return nullptr;
            }
            std::string name = safe_name(object->name, object_index);
            if (object->instances.size() > 1) {
                name += " " + std::to_string(instance_index + 1);
            }
            records.push_back({path, name, bounds.center().x(), bounds.center().y()});
            outputs.push_back(path);
            total_bytes += output_bytes;
        }
    }
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
