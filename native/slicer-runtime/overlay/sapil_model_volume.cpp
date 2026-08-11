#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <string>

#include "libslic3r/Format/STL.hpp"
#include "libslic3r/Model.hpp"

namespace sapil {
Slic3r::Model& getGlobalModel();
bool isModelLoaded();
}

namespace {

constexpr std::size_t MAX_MODEL_PART_VOLUMES = 64;
constexpr std::size_t MAX_VOLUME_NAME_BYTES = 200;

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
    if (model_part_count >= MAX_MODEL_PART_VOLUMES) return -1;

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
