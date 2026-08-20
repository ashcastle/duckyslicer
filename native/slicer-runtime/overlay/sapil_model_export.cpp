#include <jni.h>

#include <algorithm>
#include <array>
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
constexpr std::size_t MAX_VOLUME_CONFIG_ENTRIES = 128;
constexpr std::size_t MAX_VOLUME_CONFIG_KEY_BYTES = 128;
constexpr std::size_t MAX_VOLUME_CONFIG_VALUE_BYTES = 4 * 1024;
constexpr std::uint64_t MAX_VOLUME_CONFIG_BYTES = 64 * 1024;
constexpr std::size_t MAX_ANNOTATED_TRIANGLES = 100000;
constexpr std::size_t MAX_ANNOTATION_VALUE_BYTES = 4 * 1024;
constexpr std::uint64_t MAX_ANNOTATION_BYTES = 8ULL * 1024ULL * 1024ULL;
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
    int volume_type;
    std::string config_path;
    std::string support_annotation_path;
    std::string seam_annotation_path;
    std::string multi_color_annotation_path;
};

struct ParsedAnnotation {
    std::vector<std::pair<std::uint32_t, std::string>> triangles;
};

bool valid_config_key(const std::string& key)
{
    if (key.empty() || key.size() > MAX_VOLUME_CONFIG_KEY_BYTES ||
        key.front() < 'a' || key.front() > 'z') {
        return false;
    }
    return std::all_of(key.begin() + 1, key.end(), [](char value) {
        return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9') ||
            value == '_';
    });
}

void write_u32_be(std::ostream& output, std::uint32_t value)
{
    const std::array<unsigned char, 4> encoded{
        static_cast<unsigned char>((value >> 24U) & 0xFFU),
        static_cast<unsigned char>((value >> 16U) & 0xFFU),
        static_cast<unsigned char>((value >> 8U) & 0xFFU),
        static_cast<unsigned char>(value & 0xFFU),
    };
    output.write(reinterpret_cast<const char*>(encoded.data()), encoded.size());
}

bool read_u32_be(std::istream& input, std::uint32_t& value)
{
    std::array<unsigned char, 4> bytes{};
    if (!input.read(reinterpret_cast<char*>(bytes.data()), bytes.size())) return false;
    value = (static_cast<std::uint32_t>(bytes[0]) << 24U) |
            (static_cast<std::uint32_t>(bytes[1]) << 16U) |
            (static_cast<std::uint32_t>(bytes[2]) << 8U) |
            static_cast<std::uint32_t>(bytes[3]);
    return true;
}

int hex_value(char value)
{
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

bool valid_annotation_value(const std::string& value, int maximum_allowed_state)
{
    if (value.empty() || value.size() > MAX_ANNOTATION_VALUE_BYTES) return false;
    std::size_t cursor = value.size();
    std::size_t pending_nodes = 1;
    while (pending_nodes > 0) {
        if (cursor == 0) return false;
        const int code = hex_value(value[--cursor]);
        if (code < 0) return false;
        --pending_nodes;
        const int split_sides = code & 0b11;
        if (split_sides != 0) {
            pending_nodes += static_cast<std::size_t>(split_sides + 1);
            if (pending_nodes > MAX_ANNOTATION_VALUE_BYTES * 4) return false;
            continue;
        }
        int state = code >> 2;
        if ((code & 0b1100) == 0b1100) {
            int extensions = 0;
            int next = 0;
            do {
                if (cursor == 0) return false;
                next = hex_value(value[--cursor]);
                if (next < 0) return false;
                if (next == 0xF && ++extensions > 16) return false;
            } while (next == 0xF);
            state = next + 15 * extensions + 3;
        }
        if (state > maximum_allowed_state) return false;
    }
    return cursor == 0;
}

bool append_facet_annotation(
    const std::string& directory,
    std::size_t file_index,
    const char* kind,
    const Slic3r::FacetsAnnotation& annotation,
    std::size_t triangle_count,
    int maximum_allowed_state,
    std::string& path,
    std::vector<std::string>& outputs)
{
    if (annotation.empty()) {
        path.clear();
        return true;
    }
    std::vector<std::pair<std::uint32_t, std::string>> values;
    std::uint64_t encoded_bytes = 8;
    for (std::size_t triangle_index = 0; triangle_index < triangle_count; ++triangle_index) {
        std::string value = annotation.get_triangle_as_string(static_cast<int>(triangle_index));
        if (value.empty()) continue;
        if (values.size() >= MAX_ANNOTATED_TRIANGLES ||
            triangle_index > std::numeric_limits<std::uint32_t>::max() ||
            !valid_annotation_value(value, maximum_allowed_state)) {
            return false;
        }
        encoded_bytes += 8 + value.size();
        if (encoded_bytes > MAX_ANNOTATION_BYTES) return false;
        values.emplace_back(static_cast<std::uint32_t>(triangle_index), std::move(value));
    }
    if (values.empty()) {
        path.clear();
        return true;
    }
    char file_name[72];
    std::snprintf(
        file_name,
        sizeof(file_name),
        "project-volume-%s-annotation-%03zu.bin",
        kind,
        file_index);
    path = directory + "/" + file_name;
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) return false;
    output.write("DOA1", 4);
    write_u32_be(output, static_cast<std::uint32_t>(values.size()));
    for (const auto& [triangle_index, value] : values) {
        write_u32_be(output, triangle_index);
        write_u32_be(output, static_cast<std::uint32_t>(value.size()));
        output.write(value.data(), static_cast<std::streamsize>(value.size()));
    }
    output.close();
    std::ifstream written(path, std::ios::binary | std::ios::ate);
    if (!written || static_cast<std::uint64_t>(written.tellg()) != encoded_bytes) {
        std::remove(path.c_str());
        path.clear();
        return false;
    }
    outputs.push_back(path);
    return true;
}

bool read_facet_annotation(
    const std::string& path,
    std::size_t triangle_count,
    int maximum_allowed_state,
    ParsedAnnotation& parsed)
{
    parsed.triangles.clear();
    if (path.empty()) return true;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return false;
    const std::streamoff size = input.tellg();
    if (size < 8 || static_cast<std::uint64_t>(size) > MAX_ANNOTATION_BYTES) return false;
    input.seekg(0, std::ios::beg);
    std::array<char, 4> magic{};
    if (!input.read(magic.data(), magic.size()) || magic != std::array<char, 4>{'D', 'O', 'A', '1'}) {
        return false;
    }
    std::uint32_t count = 0;
    if (!read_u32_be(input, count) || count > MAX_ANNOTATED_TRIANGLES) return false;
    parsed.triangles.reserve(count);
    std::uint64_t consumed = 8;
    std::uint32_t previous_index = 0;
    bool has_previous = false;
    for (std::uint32_t entry = 0; entry < count; ++entry) {
        std::uint32_t triangle_index = 0;
        std::uint32_t value_size = 0;
        if (!read_u32_be(input, triangle_index) || !read_u32_be(input, value_size) ||
            triangle_index >= triangle_count || (has_previous && triangle_index <= previous_index) ||
            value_size == 0 || value_size > MAX_ANNOTATION_VALUE_BYTES) {
            return false;
        }
        consumed += 8 + value_size;
        if (consumed > static_cast<std::uint64_t>(size) || consumed > MAX_ANNOTATION_BYTES) {
            return false;
        }
        std::string value(value_size, '\0');
        if (!input.read(value.data(), static_cast<std::streamsize>(value.size())) ||
            !valid_annotation_value(value, maximum_allowed_state)) {
            return false;
        }
        parsed.triangles.emplace_back(triangle_index, std::move(value));
        previous_index = triangle_index;
        has_previous = true;
    }
    return consumed == static_cast<std::uint64_t>(size) && input.peek() == EOF;
}

void apply_facet_annotation(
    Slic3r::FacetsAnnotation& annotation,
    std::size_t triangle_count,
    const ParsedAnnotation& parsed)
{
    annotation.reset();
    if (parsed.triangles.empty()) return;
    annotation.reserve(triangle_count);
    for (const auto& [triangle_index, value] : parsed.triangles) {
        annotation.set_triangle_from_string(static_cast<int>(triangle_index), value);
    }
    annotation.shrink_to_fit();
}

bool append_volume_config(
    const std::string& directory,
    std::size_t file_index,
    const Slic3r::ModelConfigObject& config,
    std::string& path,
    std::vector<std::string>& outputs)
{
    const std::vector<std::string> keys = config.keys();
    if (keys.empty()) {
        path.clear();
        return true;
    }
    if (keys.size() > MAX_VOLUME_CONFIG_ENTRIES) return false;
    std::vector<std::pair<std::string, std::string>> values;
    values.reserve(keys.size());
    std::uint64_t encoded_bytes = 8;
    for (const std::string& key : keys) {
        const std::string value = config.opt_serialize(key);
        if (!valid_config_key(key) || value.size() > MAX_VOLUME_CONFIG_VALUE_BYTES ||
            value.find('\0') != std::string::npos) {
            return false;
        }
        encoded_bytes += 8 + key.size() + value.size();
        if (encoded_bytes > MAX_VOLUME_CONFIG_BYTES) return false;
        values.emplace_back(key, value);
    }
    std::sort(values.begin(), values.end());
    char file_name[56];
    std::snprintf(
        file_name,
        sizeof(file_name),
        "project-volume-config-%03zu.bin",
        file_index);
    path = directory + "/" + file_name;
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) return false;
    output.write("DVC1", 4);
    write_u32_be(output, static_cast<std::uint32_t>(values.size()));
    for (const auto& [key, value] : values) {
        write_u32_be(output, static_cast<std::uint32_t>(key.size()));
        write_u32_be(output, static_cast<std::uint32_t>(value.size()));
        output.write(key.data(), static_cast<std::streamsize>(key.size()));
        output.write(value.data(), static_cast<std::streamsize>(value.size()));
    }
    output.close();
    std::ifstream written(path, std::ios::binary | std::ios::ate);
    if (!written || static_cast<std::uint64_t>(written.tellg()) != encoded_bytes) {
        std::remove(path.c_str());
        path.clear();
        return false;
    }
    outputs.push_back(path);
    return true;
}

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
            "\t" + std::to_string(record.object_ordinal) + "\t" +
            std::to_string(record.volume_type) + "\t" + record.config_path + "\t" +
            record.support_annotation_path + "\t" + record.seam_annotation_path + "\t" +
            record.multi_color_annotation_path;
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
        const std::size_t volume_count = std::count_if(
            object->volumes.begin(),
            object->volumes.end(),
            [](const Slic3r::ModelVolume* volume) {
                return volume != nullptr && !volume->mesh().empty();
            });
        if (volume_count > MAX_EXPORTED_VOLUMES_PER_OBJECT) {
            remove_outputs(outputs);
            return nullptr;
        }
        for (std::size_t instance_index = 0; instance_index < object->instances.size(); ++instance_index) {
            const Slic3r::ModelInstance* instance = object->instances[instance_index];
            if (instance == nullptr || object_ordinal >= MAX_EXPORTED_OBJECTS ||
                project_records.size() > MAX_EXPORTED_OBJECTS - volume_count) {
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
            std::size_t volume_ordinal = 0;
            for (std::size_t volume_index = 0; volume_index < object->volumes.size(); ++volume_index) {
                const Slic3r::ModelVolume* volume = object->volumes[volume_index];
                if (volume == nullptr || volume->mesh().empty()) continue;
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
                if (volume->is_model_part()) object_bounds.merge(exported.bounding_box());
                if (!append_export(
                        directory,
                        "project-volume",
                        project_records.size(),
                        std::move(exported),
                        triangles,
                        safe_name(volume->name, volume_ordinal),
                        total_bytes,
                        flat_records,
                        outputs)) {
                    remove_outputs(outputs);
                    return nullptr;
                }
                const bool accepts_filament = volume->is_model_part() || volume->is_modifier();
                int extruder = accepts_filament ?
                    (volume->config.has("extruder") ? volume->config.extruder() :
                        (object->config.has("extruder") ? object->config.extruder() : 1)) : 1;
                if (extruder <= 0) extruder = 1;
                if (extruder > MAX_FILAMENT_SLOTS) {
                    remove_outputs(outputs);
                    return nullptr;
                }
                const ExportRecord& flat = flat_records.back();
                std::string config_path;
                if (!append_volume_config(
                        directory,
                        project_records.size(),
                        volume->config,
                        config_path,
                        outputs)) {
                    remove_outputs(outputs);
                    return nullptr;
                }
                std::string support_annotation_path;
                std::string seam_annotation_path;
                std::string multi_color_annotation_path;
                if (!append_facet_annotation(
                        directory,
                        project_records.size(),
                        "support",
                        volume->supported_facets,
                        triangles,
                        2,
                        support_annotation_path,
                        outputs) ||
                    !append_facet_annotation(
                        directory,
                        project_records.size(),
                        "seam",
                        volume->seam_facets,
                        triangles,
                        2,
                        seam_annotation_path,
                        outputs) ||
                    !append_facet_annotation(
                        directory,
                        project_records.size(),
                        "color",
                        volume->mmu_segmentation_facets,
                        triangles,
                        MAX_FILAMENT_SLOTS,
                        multi_color_annotation_path,
                        outputs)) {
                    remove_outputs(outputs);
                    return nullptr;
                }
                project_records.push_back({
                    flat.path,
                    object_name,
                    flat.name,
                    0.0,
                    0.0,
                    accepts_filament ? extruder - 1 : 0,
                    object_ordinal,
                    static_cast<int>(volume->type()),
                    std::move(config_path),
                    std::move(support_annotation_path),
                    std::move(seam_annotation_path),
                    std::move(multi_color_annotation_path),
                });
                ++volume_ordinal;
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
            if (volume != nullptr && volume->is_fuzzy_skin_painted()) {
                ++unsupported;
            }
        }
    }
    if (unsupported > static_cast<std::size_t>(std::numeric_limits<jint>::max())) return -1;
    return static_cast<jint>(unsupported);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeApplyOrcaFacetAnnotations(
    JNIEnv* env,
    jobject,
    jint object_index,
    jint volume_index,
    jstring support_path,
    jstring seam_path,
    jstring multi_color_path)
{
    if (!sapil::isModelLoaded() || object_index < 0 || volume_index < 0 ||
        support_path == nullptr || seam_path == nullptr || multi_color_path == nullptr) {
        return JNI_FALSE;
    }
    Slic3r::Model& model = sapil::getGlobalModel();
    if (static_cast<std::size_t>(object_index) >= model.objects.size()) return JNI_FALSE;
    Slic3r::ModelObject* object = model.objects[static_cast<std::size_t>(object_index)];
    if (object == nullptr || static_cast<std::size_t>(volume_index) >= object->volumes.size()) {
        return JNI_FALSE;
    }
    Slic3r::ModelVolume* volume = object->volumes[static_cast<std::size_t>(volume_index)];
    if (volume == nullptr || !volume->is_model_part()) return JNI_FALSE;

    auto string_value = [env](jstring value, std::string& output) -> bool {
        const char* chars = env->GetStringUTFChars(value, nullptr);
        if (chars == nullptr) return false;
        output.assign(chars);
        env->ReleaseStringUTFChars(value, chars);
        return output.size() <= 900;
    };
    std::string support;
    std::string seam;
    std::string multi_color;
    if (!string_value(support_path, support) || !string_value(seam_path, seam) ||
        !string_value(multi_color_path, multi_color)) {
        return JNI_FALSE;
    }
    const std::size_t triangle_count = volume->mesh().facets_count();
    ParsedAnnotation parsed_support;
    ParsedAnnotation parsed_seam;
    ParsedAnnotation parsed_multi_color;
    if (!read_facet_annotation(support, triangle_count, 2, parsed_support) ||
        !read_facet_annotation(seam, triangle_count, 2, parsed_seam) ||
        !read_facet_annotation(
            multi_color,
            triangle_count,
            MAX_FILAMENT_SLOTS,
            parsed_multi_color)) {
        return JNI_FALSE;
    }
    apply_facet_annotation(volume->supported_facets, triangle_count, parsed_support);
    apply_facet_annotation(volume->seam_facets, triangle_count, parsed_seam);
    apply_facet_annotation(volume->mmu_segmentation_facets, triangle_count, parsed_multi_color);
    return JNI_TRUE;
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
