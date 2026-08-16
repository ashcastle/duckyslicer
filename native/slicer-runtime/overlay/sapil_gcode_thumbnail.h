#pragma once

#include <functional>

#include "libslic3r/GCode/ThumbnailData.hpp"

namespace Slic3r {
class Model;
}

namespace sapil {

Slic3r::ThumbnailsGeneratorCallback makeGcodeThumbnailCallback(
    const Slic3r::Model& model,
    std::function<void()> cancel_check);

} // namespace sapil
