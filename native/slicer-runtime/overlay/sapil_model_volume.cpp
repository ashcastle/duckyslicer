#include <jni.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

#include "libslic3r/Format/STL.hpp"
#include "libslic3r/Model.hpp"

namespace sapil {
Slic3r::Model& getGlobalModel();
bool isModelLoaded();
}

namespace {

constexpr std::size_t MAX_VOLUMES_PER_OBJECT = 64;
constexpr std::size_t MAX_VOLUME_NAME_BYTES = 200;
constexpr std::size_t MAX_VOLUME_CONFIG_ENTRIES = 128;
constexpr std::size_t MAX_VOLUME_CONFIG_KEY_BYTES = 128;
constexpr std::size_t MAX_VOLUME_CONFIG_VALUE_BYTES = 4 * 1024;
constexpr std::uint64_t MAX_VOLUME_CONFIG_BYTES = 64 * 1024;
constexpr std::array<unsigned char, 4> VOLUME_CONFIG_MAGIC{'D', 'V', 'C', '1'};

bool read_u32_be(std::ifstream& input, std::uint32_t& value)
{
    std::array<unsigned char, 4> bytes{};
    if (!input.read(reinterpret_cast<char*>(bytes.data()), bytes.size())) return false;
    value = (static_cast<std::uint32_t>(bytes[0]) << 24U) |
        (static_cast<std::uint32_t>(bytes[1]) << 16U) |
        (static_cast<std::uint32_t>(bytes[2]) << 8U) |
        static_cast<std::uint32_t>(bytes[3]);
    return true;
}

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

bool read_volume_config(const std::string& path, Slic3r::ModelConfig& config)
{
    if (path.empty()) return true;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return false;
    const std::streamoff length = input.tellg();
    if (length < 8 || static_cast<std::uint64_t>(length) > MAX_VOLUME_CONFIG_BYTES) return false;
    input.seekg(0, std::ios::beg);
    std::array<unsigned char, 4> magic{};
    if (!input.read(reinterpret_cast<char*>(magic.data()), magic.size()) ||
        magic != VOLUME_CONFIG_MAGIC) {
        return false;
    }
    std::uint32_t count = 0;
    if (!read_u32_be(input, count) || count > MAX_VOLUME_CONFIG_ENTRIES) return false;
    std::vector<std::pair<std::string, std::string>> values;
    values.reserve(count);
    for (std::uint32_t index = 0; index < count; ++index) {
        std::uint32_t key_size = 0;
        std::uint32_t value_size = 0;
        if (!read_u32_be(input, key_size) || !read_u32_be(input, value_size) ||
            key_size == 0 || key_size > MAX_VOLUME_CONFIG_KEY_BYTES ||
            value_size > MAX_VOLUME_CONFIG_VALUE_BYTES) {
            return false;
        }
        std::string key(key_size, '\0');
        std::string value(value_size, '\0');
        if (!input.read(key.data(), static_cast<std::streamsize>(key.size())) ||
            !input.read(value.data(), static_cast<std::streamsize>(value.size())) ||
            !valid_config_key(key) || value.find('\0') != std::string::npos) {
            return false;
        }
        if (std::any_of(values.begin(), values.end(), [&key](const auto& entry) {
                return entry.first == key;
            })) {
            return false;
        }
        values.emplace_back(std::move(key), std::move(value));
    }
    if (input.peek() != std::ifstream::traits_type::eof()) return false;
    Slic3r::ConfigSubstitutionContext substitutions(
        Slic3r::ForwardCompatibilitySubstitutionRule::Enable);
    try {
        for (const auto& [key, value] : values) {
            config.set_deserialize(key, value, substitutions);
        }
    } catch (...) {
        return false;
    }
    return true;
}

int add_model_part_volume(int object_index, const std::string& path, std::string name)
{
    if (!sapil::isModelLoaded() || object_index < 0 || path.empty() || path.size() > 900) {
        return -1;
    }
    Slic3r::Model& model = sapil::getGlobalModel();
    if (static_cast<std::size_t>(object_index) >= model.objects.size()) return -1;
    Slic3r::ModelObject* target = model.objects[static_cast<std::size_t>(object_index)];
    if (target == nullptr || target->instances.size() != 1) return -1;
    const std::size_t model_part_count = std::count_if(
        target->volumes.begin(),
        target->volumes.end(),
        [](const Slic3r::ModelVolume* volume) {
            return volume != nullptr && volume->is_model_part();
        });
    const std::size_t volume_count = std::count_if(
        target->volumes.begin(),
        target->volumes.end(),
        [](const Slic3r::ModelVolume* volume) { return volume != nullptr; });
    if (volume_count >= MAX_VOLUMES_PER_OBJECT) return -1;

    Slic3r::Model imported;
    if (!Slic3r::load_stl(path.c_str(), &imported) || imported.objects.size() != 1) return -1;
    const Slic3r::ModelObject* source = imported.objects.front();
    if (source == nullptr || source->instances.size() > 1) return -1;
    const Slic3r::ModelVolume* source_part = nullptr;
    for (const Slic3r::ModelVolume* volume : source->volumes) {
        if (volume == nullptr || !volume->is_model_part()) continue;
        if (source_part != nullptr) return -1;
        source_part = volume;
    }
    if (source_part == nullptr || source_part->mesh().empty()) return -1;

    Slic3r::Transform3d source_world = source_part->get_matrix();
    if (!source->instances.empty()) {
        source_world =
            source->instances.front()->get_transformation().get_matrix() * source_world;
    }
    const Slic3r::Transform3d target_world =
        target->instances.front()->get_transformation().get_matrix();
    Slic3r::ModelVolume* added = target->add_volume(
        *source_part,
        Slic3r::ModelVolumeType::MODEL_PART);
    if (added == nullptr) return -1;
    added->set_transformation(target_world.inverse() * source_world);
    if (name.empty()) name = "Part " + std::to_string(model_part_count + 1);
    if (name.size() > MAX_VOLUME_NAME_BYTES) name.resize(MAX_VOLUME_NAME_BYTES);
    added->name = std::move(name);
    added->source.input_file = path;
    added->source.object_idx = object_index;
    added->source.volume_idx = static_cast<int>(target->volumes.size() - 1);
    target->invalidate_bounding_box();
    return static_cast<int>(target->volumes.size() - 1);
}

bool set_volume_semantics(
    int object_index,
    int volume_index,
    int volume_type,
    const std::string& config_path)
{
    if (!sapil::isModelLoaded() || object_index < 0 || volume_index < 0 ||
        volume_type < static_cast<int>(Slic3r::ModelVolumeType::MODEL_PART) ||
        volume_type > static_cast<int>(Slic3r::ModelVolumeType::SUPPORT_ENFORCER) ||
        config_path.size() > 900) {
        return false;
    }
    Slic3r::Model& model = sapil::getGlobalModel();
    if (static_cast<std::size_t>(object_index) >= model.objects.size()) return false;
    Slic3r::ModelObject* object = model.objects[static_cast<std::size_t>(object_index)];
    if (object == nullptr || static_cast<std::size_t>(volume_index) >= object->volumes.size()) {
        return false;
    }
    Slic3r::ModelVolume* volume = object->volumes[static_cast<std::size_t>(volume_index)];
    if (volume == nullptr) return false;
    Slic3r::ModelConfig config;
    if (!read_volume_config(config_path, config)) return false;
    const auto type = static_cast<Slic3r::ModelVolumeType>(volume_type);
    if (type == Slic3r::ModelVolumeType::NEGATIVE_VOLUME ||
        type == Slic3r::ModelVolumeType::SUPPORT_BLOCKER ||
        type == Slic3r::ModelVolumeType::SUPPORT_ENFORCER) {
        config.erase("extruder");
    }
    volume->set_type(type);
    volume->config.assign_config(std::move(config));
    object->invalidate_bounding_box();
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeAddModelPartVolume(
    JNIEnv* env,
    jobject,
    jint object_index,
    jstring path,
    jstring name)
{
    if (path == nullptr || name == nullptr) return -1;
    const char* path_chars = env->GetStringUTFChars(path, nullptr);
    if (path_chars == nullptr) return -1;
    const char* name_chars = env->GetStringUTFChars(name, nullptr);
    if (name_chars == nullptr) {
        env->ReleaseStringUTFChars(path, path_chars);
        return -1;
    }
    const std::string path_text(path_chars);
    const std::string name_text(name_chars);
    env->ReleaseStringUTFChars(name, name_chars);
    env->ReleaseStringUTFChars(path, path_chars);
    try {
        return add_model_part_volume(static_cast<int>(object_index), path_text, name_text);
    } catch (...) {
        return -1;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSetVolumeSemantics(
    JNIEnv* env,
    jobject,
    jint object_index,
    jint volume_index,
    jint volume_type,
    jstring config_path)
{
    if (config_path == nullptr) return JNI_FALSE;
    const char* path_chars = env->GetStringUTFChars(config_path, nullptr);
    if (path_chars == nullptr) return JNI_FALSE;
    const std::string path(path_chars);
    env->ReleaseStringUTFChars(config_path, path_chars);
    try {
        return set_volume_semantics(
            static_cast<int>(object_index),
            static_cast<int>(volume_index),
            static_cast<int>(volume_type),
            path) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}
