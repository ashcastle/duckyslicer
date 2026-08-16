#include "sapil_gcode_thumbnail.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <utility>
#include <vector>

#include "libslic3r/Config.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace {

constexpr std::size_t MAX_THUMBNAIL_PIXELS = 1'000'000;
constexpr std::size_t MAX_RASTER_TRIANGLES = 1'000'000;
constexpr std::size_t CANCEL_CHECK_INTERVAL = 4'096;

struct MeshSource {
    const indexed_triangle_set* triangles;
    Slic3r::Transform3d transform;
    std::array<std::uint8_t, 3> color;
};

struct ProjectedPoint {
    double x;
    double y;
    double depth;
};

struct ProjectedBounds {
    double minimum_x = std::numeric_limits<double>::infinity();
    double maximum_x = -std::numeric_limits<double>::infinity();
    double minimum_y = std::numeric_limits<double>::infinity();
    double maximum_y = -std::numeric_limits<double>::infinity();

    void merge(const ProjectedPoint& point)
    {
        minimum_x = std::min(minimum_x, point.x);
        maximum_x = std::max(maximum_x, point.x);
        minimum_y = std::min(minimum_y, point.y);
        maximum_y = std::max(maximum_y, point.y);
    }

    bool valid() const
    {
        return std::isfinite(minimum_x) && std::isfinite(maximum_x) &&
            std::isfinite(minimum_y) && std::isfinite(maximum_y) &&
            maximum_x > minimum_x && maximum_y > minimum_y;
    }
};

constexpr std::array<std::array<std::uint8_t, 3>, 8> TOOL_COLORS{{
    {{246, 201, 69}},
    {{63, 195, 226}},
    {{132, 105, 226}},
    {{233, 92, 99}},
    {{80, 202, 151}},
    {{237, 126, 48}},
    {{213, 95, 221}},
    {{225, 226, 218}},
}};

ProjectedPoint project(const Slic3r::Vec3d& point)
{
    constexpr double inverse_sqrt_two = 0.7071067811865475;
    constexpr double inverse_sqrt_six = 0.4082482904638631;
    constexpr double inverse_sqrt_three = 0.5773502691896258;
    return {
        (point.x() - point.y()) * inverse_sqrt_two,
        (-point.x() - point.y() + 2.0 * point.z()) * inverse_sqrt_six,
        (point.x() + point.y() + point.z()) * inverse_sqrt_three,
    };
}

std::vector<MeshSource> collect_sources(
    const Slic3r::Model& model,
    const std::function<void()>& cancel_check,
    std::size_t& triangle_count,
    ProjectedBounds& bounds)
{
    std::vector<MeshSource> sources;
    triangle_count = 0;
    std::size_t visited_sources = 0;
    for (const Slic3r::ModelObject* object : model.objects) {
        if (object == nullptr || !object->printable) continue;
        for (const Slic3r::ModelInstance* instance : object->instances) {
            if (instance == nullptr || !instance->printable) continue;
            const Slic3r::Transform3d instance_transform =
                instance->get_transformation().get_matrix();
            for (const Slic3r::ModelVolume* volume : object->volumes) {
                if (volume == nullptr || !volume->is_model_part() || volume->mesh().empty()) continue;
                const auto* extruder = dynamic_cast<const Slic3r::ConfigOptionInt*>(
                    volume->config.option("extruder"));
                const int tool = extruder == nullptr ? 1 : std::max(1, extruder->value);
                const Slic3r::Transform3d transform = instance_transform * volume->get_matrix();
                const Slic3r::BoundingBoxf3 world_bounds =
                    volume->mesh().transformed_bounding_box(transform);
                if (!world_bounds.defined) continue;
                for (int x = 0; x < 2; ++x) {
                    for (int y = 0; y < 2; ++y) {
                        for (int z = 0; z < 2; ++z) {
                            bounds.merge(project({
                                x == 0 ? world_bounds.min.x() : world_bounds.max.x(),
                                y == 0 ? world_bounds.min.y() : world_bounds.max.y(),
                                z == 0 ? world_bounds.min.z() : world_bounds.max.z(),
                            }));
                        }
                    }
                }
                const auto& indexed = volume->mesh().its;
                triangle_count += indexed.indices.size();
                sources.push_back({
                    &indexed,
                    transform,
                    TOOL_COLORS[static_cast<std::size_t>(tool - 1) % TOOL_COLORS.size()],
                });
                if (++visited_sources % 64 == 0) cancel_check();
            }
        }
    }
    cancel_check();
    return sources;
}

double edge(double ax, double ay, double bx, double by, double px, double py)
{
    return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
}

void write_pixel(
    Slic3r::ThumbnailData& output,
    std::vector<float>& depth_buffer,
    int x,
    int y,
    double depth,
    const std::array<std::uint8_t, 3>& color)
{
    const std::size_t pixel = static_cast<std::size_t>(y) * output.width + x;
    if (depth <= depth_buffer[pixel]) return;
    depth_buffer[pixel] = static_cast<float>(depth);
    const std::size_t offset = pixel * 4;
    output.pixels[offset] = color[0];
    output.pixels[offset + 1] = color[1];
    output.pixels[offset + 2] = color[2];
    output.pixels[offset + 3] = 255;
}

Slic3r::ThumbnailData render_thumbnail(
    const std::vector<MeshSource>& sources,
    std::size_t triangle_count,
    const ProjectedBounds& bounds,
    unsigned int width,
    unsigned int height,
    const std::function<void()>& cancel_check)
{
    Slic3r::ThumbnailData output;
    if (
        width == 0 || height == 0 || width > 999 || height > 999 ||
        static_cast<std::size_t>(width) * height > MAX_THUMBNAIL_PIXELS ||
        sources.empty() || triangle_count == 0 || !bounds.valid()
    ) {
        return output;
    }
    output.set(width, height);
    std::fill(output.pixels.begin(), output.pixels.end(), 0);
    std::vector<float> depth_buffer(
        static_cast<std::size_t>(width) * height,
        -std::numeric_limits<float>::infinity());

    const double margin = std::max(2.0, std::min(width, height) * 0.07);
    const double available_width = std::max(1.0, width - 2.0 * margin);
    const double available_height = std::max(1.0, height - 2.0 * margin);
    const double scale = std::min(
        available_width / (bounds.maximum_x - bounds.minimum_x),
        available_height / (bounds.maximum_y - bounds.minimum_y));
    const double center_x = (bounds.minimum_x + bounds.maximum_x) * 0.5;
    const double center_y = (bounds.minimum_y + bounds.maximum_y) * 0.5;
    const std::size_t stride = std::max<std::size_t>(
        1,
        (triangle_count + MAX_RASTER_TRIANGLES - 1) / MAX_RASTER_TRIANGLES);

    std::size_t visited = 0;
    std::size_t drawn_pixels = 0;
    const Slic3r::Vec3d light = Slic3r::Vec3d(-0.35, -0.45, 0.82).normalized();
    for (const MeshSource& source : sources) {
        const auto& vertices = source.triangles->vertices;
        for (const auto& triangle : source.triangles->indices) {
            const std::size_t ordinal = visited++;
            if (ordinal % CANCEL_CHECK_INTERVAL == 0) cancel_check();
            if (ordinal % stride != 0) continue;

            std::array<Slic3r::Vec3d, 3> world;
            std::array<ProjectedPoint, 3> projected;
            for (int index = 0; index < 3; ++index) {
                world[index] = source.transform * vertices[triangle[index]].cast<double>();
                projected[index] = project(world[index]);
                projected[index].x = width * 0.5 + (projected[index].x - center_x) * scale;
                projected[index].y = height * 0.5 + (projected[index].y - center_y) * scale;
            }

            Slic3r::Vec3d normal = (world[1] - world[0]).cross(world[2] - world[0]);
            const double normal_length = normal.norm();
            if (normal_length <= 1e-12) continue;
            normal /= normal_length;
            const double shade = 0.38 + 0.62 * std::abs(normal.dot(light));
            const std::array<std::uint8_t, 3> shaded{{
                static_cast<std::uint8_t>(std::clamp(source.color[0] * shade, 0.0, 255.0)),
                static_cast<std::uint8_t>(std::clamp(source.color[1] * shade, 0.0, 255.0)),
                static_cast<std::uint8_t>(std::clamp(source.color[2] * shade, 0.0, 255.0)),
            }};

            const double area = edge(
                projected[0].x,
                projected[0].y,
                projected[1].x,
                projected[1].y,
                projected[2].x,
                projected[2].y);
            if (std::abs(area) < 0.25) {
                const int x = static_cast<int>(std::lround(
                    (projected[0].x + projected[1].x + projected[2].x) / 3.0));
                const int y = static_cast<int>(std::lround(
                    (projected[0].y + projected[1].y + projected[2].y) / 3.0));
                if (x >= 0 && x < static_cast<int>(width) && y >= 0 && y < static_cast<int>(height)) {
                    write_pixel(
                        output,
                        depth_buffer,
                        x,
                        y,
                        (projected[0].depth + projected[1].depth + projected[2].depth) / 3.0,
                        shaded);
                    ++drawn_pixels;
                }
                continue;
            }

            const int minimum_x = std::max(0, static_cast<int>(std::floor(std::min({
                projected[0].x, projected[1].x, projected[2].x,
            }))));
            const int maximum_x = std::min(
                static_cast<int>(width) - 1,
                static_cast<int>(std::ceil(std::max({
                    projected[0].x, projected[1].x, projected[2].x,
                }))));
            const int minimum_y = std::max(0, static_cast<int>(std::floor(std::min({
                projected[0].y, projected[1].y, projected[2].y,
            }))));
            const int maximum_y = std::min(
                static_cast<int>(height) - 1,
                static_cast<int>(std::ceil(std::max({
                    projected[0].y, projected[1].y, projected[2].y,
                }))));
            for (int y = minimum_y; y <= maximum_y; ++y) {
                for (int x = minimum_x; x <= maximum_x; ++x) {
                    const double sample_x = x + 0.5;
                    const double sample_y = y + 0.5;
                    const double first = edge(
                        projected[1].x, projected[1].y,
                        projected[2].x, projected[2].y,
                        sample_x, sample_y) / area;
                    const double second = edge(
                        projected[2].x, projected[2].y,
                        projected[0].x, projected[0].y,
                        sample_x, sample_y) / area;
                    const double third = 1.0 - first - second;
                    if (first < -1e-8 || second < -1e-8 || third < -1e-8) continue;
                    write_pixel(
                        output,
                        depth_buffer,
                        x,
                        y,
                        first * projected[0].depth + second * projected[1].depth +
                            third * projected[2].depth,
                        shaded);
                    ++drawn_pixels;
                }
            }
        }
    }
    cancel_check();
    if (drawn_pixels == 0) {
        output.reset();
        return output;
    }

    const std::vector<unsigned char> filled = output.pixels;
    for (unsigned int y = 0; y < height; ++y) {
        for (unsigned int x = 0; x < width; ++x) {
            const std::size_t pixel = static_cast<std::size_t>(y) * width + x;
            if (filled[pixel * 4 + 3] != 0) continue;
            bool adjacent = false;
            for (int offset_y = -1; offset_y <= 1 && !adjacent; ++offset_y) {
                for (int offset_x = -1; offset_x <= 1; ++offset_x) {
                    const int neighbor_x = static_cast<int>(x) + offset_x;
                    const int neighbor_y = static_cast<int>(y) + offset_y;
                    if (
                        neighbor_x >= 0 && neighbor_x < static_cast<int>(width) &&
                        neighbor_y >= 0 && neighbor_y < static_cast<int>(height) &&
                        filled[(static_cast<std::size_t>(neighbor_y) * width + neighbor_x) * 4 + 3] != 0
                    ) {
                        adjacent = true;
                        break;
                    }
                }
            }
            if (adjacent) {
                output.pixels[pixel * 4] = 31;
                output.pixels[pixel * 4 + 1] = 32;
                output.pixels[pixel * 4 + 2] = 30;
                output.pixels[pixel * 4 + 3] = 230;
            }
        }
    }
    return output;
}

} // namespace

namespace sapil {

Slic3r::ThumbnailsGeneratorCallback makeGcodeThumbnailCallback(
    const Slic3r::Model& model,
    std::function<void()> cancel_check)
{
    return [&model, cancel_check = std::move(cancel_check)](
               const Slic3r::ThumbnailsParams& parameters) {
        std::size_t triangle_count = 0;
        ProjectedBounds bounds;
        const std::vector<MeshSource> sources =
            collect_sources(model, cancel_check, triangle_count, bounds);
        Slic3r::ThumbnailsList thumbnails;
        thumbnails.reserve(parameters.sizes.size());
        for (const Slic3r::Vec2d& size : parameters.sizes) {
            const auto width = static_cast<unsigned int>(std::lround(size.x()));
            const auto height = static_cast<unsigned int>(std::lround(size.y()));
            Slic3r::ThumbnailData thumbnail = render_thumbnail(
                sources,
                triangle_count,
                bounds,
                width,
                height,
                cancel_check);
            if (thumbnail.is_valid()) thumbnails.emplace_back(std::move(thumbnail));
        }
        return thumbnails;
    };
}

} // namespace sapil
