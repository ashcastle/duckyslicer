#include <jni.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "libslic3r/Model.hpp"
#include "libslic3r/PrintConfig.hpp"

namespace sapil {
Slic3r::Model& getGlobalModel();
bool isModelLoaded();
}

namespace {

constexpr std::array<unsigned char, 4> MAGIC{'D', 'B', 'P', '1'};
constexpr std::uint32_t MAX_POINTS = 256;
constexpr std::uint64_t HEADER_BYTES = 8;
constexpr std::uint64_t ENTRY_BYTES = 16;
constexpr float MAX_COORDINATE_MM = 1'000'000.0F;
constexpr float MIN_RADIUS_MM = 2.5F;
constexpr float MAX_RADIUS_MM = 10.0F;

bool read_u32_be(std::istream& input, std::uint32_t& value)
{
    std::array<unsigned char, 4> bytes{};
    if (!input.read(reinterpret_cast<char*>(bytes.data()), bytes.size())) return false;
    value = (static_cast<std::uint32_t>(bytes[0]) << 24) |
            (static_cast<std::uint32_t>(bytes[1]) << 16) |
            (static_cast<std::uint32_t>(bytes[2]) << 8) |
            static_cast<std::uint32_t>(bytes[3]);
    return true;
}

bool read_f32_be(std::istream& input, float& value)
{
    std::uint32_t bits = 0;
    if (!read_u32_be(input, bits)) return false;
    static_assert(sizeof(bits) == sizeof(value));
    std::memcpy(&value, &bits, sizeof(value));
    return true;
}

bool apply_brim_points(int object_index, const std::string& sidecar_path)
{
    if (!sapil::isModelLoaded() || object_index < 0) return false;
    Slic3r::Model& model = sapil::getGlobalModel();
    if (static_cast<std::size_t>(object_index) >= model.objects.size()) return false;
    Slic3r::ModelObject* object = model.objects[static_cast<std::size_t>(object_index)];
    if (object == nullptr || object->instances.size() != 1) return false;

    std::ifstream input(sidecar_path, std::ios::binary | std::ios::ate);
    if (!input) return false;
    const std::streamoff size = input.tellg();
    if (size < static_cast<std::streamoff>(HEADER_BYTES)) return false;
    input.seekg(0, std::ios::beg);
    std::array<unsigned char, 4> magic{};
    if (!input.read(reinterpret_cast<char*>(magic.data()), magic.size()) || magic != MAGIC) {
        return false;
    }
    std::uint32_t count = 0;
    if (!read_u32_be(input, count) || count == 0 || count > MAX_POINTS) return false;
    const std::uint64_t expected = HEADER_BYTES + static_cast<std::uint64_t>(count) * ENTRY_BYTES;
    if (size < 0 || static_cast<std::uint64_t>(size) != expected) return false;

    const Slic3r::Transform3d world_to_object =
        object->instances.front()->get_transformation().get_matrix().inverse();
    Slic3r::BrimPoints points;
    points.reserve(count);
    for (std::uint32_t index = 0; index < count; ++index) {
        float x = 0.0F;
        float y = 0.0F;
        float z = 0.0F;
        float radius = 0.0F;
        if (!read_f32_be(input, x) || !read_f32_be(input, y) ||
            !read_f32_be(input, z) || !read_f32_be(input, radius)) {
            return false;
        }
        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z) ||
            !std::isfinite(radius) || std::abs(x) > MAX_COORDINATE_MM ||
            std::abs(y) > MAX_COORDINATE_MM || std::abs(z) > MAX_COORDINATE_MM ||
            radius < MIN_RADIUS_MM || radius > MAX_RADIUS_MM) {
            return false;
        }
        const Slic3r::Vec3d local = world_to_object * Slic3r::Vec3d(x, y, z);
        if (!local.allFinite()) return false;
        points.emplace_back(local.cast<float>(), radius);
    }

    object->brim_points = std::move(points);
    object->config.set_key_value(
        "brim_type",
        new Slic3r::ConfigOptionEnum<Slic3r::BrimType>(Slic3r::btPainted));
    return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_applyBrimPoints(
        JNIEnv* env, jobject, jint object_index, jstring sidecar_path)
{
    if (sidecar_path == nullptr || env->GetStringUTFLength(sidecar_path) > 1024) {
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(sidecar_path, nullptr);
    if (path == nullptr) return JNI_FALSE;
    bool result = false;
    try {
        result = apply_brim_points(static_cast<int>(object_index), std::string(path));
    } catch (...) {
        result = false;
    }
    env->ReleaseStringUTFChars(sidecar_path, path);
    return result ? JNI_TRUE : JNI_FALSE;
}
