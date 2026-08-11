#include <jni.h>

#include <cstdint>
#include <limits>

#include "libslic3r/Model.hpp"
#include "libslic3r/QuadricEdgeCollapse.hpp"

namespace sapil {
Slic3r::Model& getGlobalModel();
bool isModelLoaded();
}

namespace {

constexpr std::uint32_t MINIMUM_TARGET_TRIANGLES = 4;

int simplify_object(int object_index, std::uint32_t target_triangles)
{
    if (!sapil::isModelLoaded() || object_index < 0 ||
        target_triangles < MINIMUM_TARGET_TRIANGLES) {
        return -1;
    }

    Slic3r::Model& model = sapil::getGlobalModel();
    if (static_cast<std::size_t>(object_index) >= model.objects.size()) return -1;
    Slic3r::ModelObject* object = model.objects[static_cast<std::size_t>(object_index)];
    if (object == nullptr || object->instances.size() != 1) return -1;

    Slic3r::ModelVolume* model_part = nullptr;
    for (Slic3r::ModelVolume* volume : object->volumes) {
        if (volume == nullptr || !volume->is_model_part()) continue;
        if (model_part != nullptr) return -1;
        model_part = volume;
    }
    if (model_part == nullptr) return -1;

    indexed_triangle_set simplified = model_part->mesh().its;
    const std::size_t original_count = simplified.indices.size();
    if (original_count <= target_triangles ||
        original_count > std::numeric_limits<std::uint32_t>::max()) {
        return -1;
    }

    Slic3r::its_quadric_edge_collapse(simplified, target_triangles);
    const std::size_t simplified_count = simplified.indices.size();
    if (simplified_count < MINIMUM_TARGET_TRIANGLES ||
        simplified_count >= original_count ||
        simplified_count > static_cast<std::size_t>(std::numeric_limits<jint>::max())) {
        return -1;
    }

    model_part->set_mesh(std::move(simplified));
    model_part->calculate_convex_hull();
    model_part->invalidate_convex_hull_2d();
    model_part->set_new_unique_id();
    object->invalidate_bounding_box();
    return static_cast<int>(simplified_count);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSimplifyObject(
    JNIEnv*,
    jobject,
    jint object_index,
    jint target_triangles)
{
    if (target_triangles < 0) return -1;
    try {
        return simplify_object(
            static_cast<int>(object_index),
            static_cast<std::uint32_t>(target_triangles));
    } catch (...) {
        return -1;
    }
}
