#![deny(unsafe_op_in_unsafe_fn)]

use std::collections::{HashMap, HashSet};
use std::ffi::{CStr, c_char};
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JIntArray, JString};
use jni::sys::{jboolean, jfloat, jfloatArray, jint, jstring};
use serde::{Deserialize, Serialize};
use thiserror::Error;

unsafe extern "C" {
    fn duckyslicer_core_version() -> *const c_char;
    fn duckyslicer_probe_vulkan(capabilities: *mut VulkanCapabilitiesNative);
}

#[repr(C)]
struct VulkanCapabilitiesNative {
    loader_api_version: u32,
    physical_device_count: u32,
    device_api_version: u32,
    driver_version: u32,
    vendor_id: u32,
    device_id: u32,
    device_type: u32,
    compute_queue_family: u32,
    api_available: u8,
    compute_queue_available: u8,
    shader_int64: u8,
    software_device: u8,
    driver_probe_passed: u8,
    auto_candidate: u8,
    device_name: [c_char; 256],
    reason: [c_char; 128],
}

impl Default for VulkanCapabilitiesNative {
    fn default() -> Self {
        Self {
            loader_api_version: 0,
            physical_device_count: 0,
            device_api_version: 0,
            driver_version: 0,
            vendor_id: 0,
            device_id: 0,
            device_type: 0,
            compute_queue_family: u32::MAX,
            api_available: 0,
            compute_queue_available: 0,
            shader_int64: 0,
            software_device: 0,
            driver_probe_passed: 0,
            auto_candidate: 0,
            device_name: [0; 256],
            reason: [0; 128],
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct VulkanCapabilities {
    api_available: bool,
    loader_api_version: String,
    physical_device_count: u32,
    device_name: String,
    device_api_version: String,
    driver_version: u32,
    vendor_id: u32,
    device_id: u32,
    device_type: String,
    compute_queue_available: bool,
    compute_queue_family: Option<u32>,
    shader_int64: bool,
    software_device: bool,
    driver_probe_passed: bool,
    auto_candidate: bool,
    auto_enabled: bool,
    reason: String,
}

fn vulkan_version(value: u32) -> String {
    format!(
        "{}.{}.{}",
        value >> 22,
        (value >> 12) & 0x3ff,
        value & 0xfff,
    )
}

fn native_text(value: &[c_char]) -> String {
    let pointer = value.as_ptr();
    if pointer.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(pointer) }
            .to_string_lossy()
            .into_owned()
    }
}

fn probe_vulkan() -> VulkanCapabilities {
    let mut native = VulkanCapabilitiesNative::default();
    unsafe { duckyslicer_probe_vulkan(&mut native) };
    VulkanCapabilities {
        api_available: native.api_available != 0,
        loader_api_version: vulkan_version(native.loader_api_version),
        physical_device_count: native.physical_device_count,
        device_name: native_text(&native.device_name),
        device_api_version: vulkan_version(native.device_api_version),
        driver_version: native.driver_version,
        vendor_id: native.vendor_id,
        device_id: native.device_id,
        device_type: match native.device_type {
            1 => "integrated_gpu",
            2 => "discrete_gpu",
            3 => "virtual_gpu",
            4 => "cpu",
            _ => "other",
        }
        .to_owned(),
        compute_queue_available: native.compute_queue_available != 0,
        compute_queue_family: (native.compute_queue_family != u32::MAX)
            .then_some(native.compute_queue_family),
        shader_int64: native.shader_int64 != 0,
        software_device: native.software_device != 0,
        driver_probe_passed: native.driver_probe_passed != 0,
        auto_candidate: native.auto_candidate != 0,
        auto_enabled: false,
        reason: native_text(&native.reason),
    }
}

const MAX_MODEL_IMPORT_BYTES: u64 = 512 * 1024 * 1024;
const MAX_TEXT_LINE_BYTES: usize = 64 * 1024;
const MAX_STL_COORDINATE_ABS_MM: f32 = 1_000_000.0;
const PREVIEW_TRIANGLE_LIMIT: usize = 12_000;
const PREVIEW_CLUSTER_START_RESOLUTION: u16 = 36;
const PREVIEW_CLUSTER_WORK_LIMIT: usize = PREVIEW_TRIANGLE_LIMIT * 8;
const MODEL_PREVIEW_PAYLOAD_MAGIC: f32 = 17_492.0;
const MODEL_PREVIEW_PAYLOAD_VERSION: f32 = 1.0;
const MODEL_PREVIEW_HEADER_FLOATS: usize = 10;
const MODEL_PREVIEW_MAX_EXACT_INTEGER: usize = 1 << 24;
const MAX_GCODE_COORDINATE_ABS_MM: f32 = 1_000_000.0;
const MAX_PREVIEW_SEGMENTS: usize = 120_000;
const PREVIEW_COMPACTION_THRESHOLD: usize = MAX_PREVIEW_SEGMENTS * 2;
const MAX_PREVIEW_LAYERS: usize = 1_000_000;
const PREVIEW_PAYLOAD_MAGIC: f32 = 17_491.0;
const PREVIEW_PAYLOAD_VERSION: f32 = 1.0;
const PREVIEW_HEADER_FLOATS: usize = 7;
const TOOLPATH_SEGMENT_FLOATS: usize = 6;
const PACKED_TOOLPATH_FLOATS: usize = 8;
const TOOLPATH_ROLE_COUNT: usize = 10;
const TOOLPATH_Z_OFFSET_MM: f32 = 0.024;
const TOOLPATH_ROLE_COLORS: [[f32; 3]; TOOLPATH_ROLE_COUNT] = [
    [1.0, 0.812, 0.251],
    [0.267, 0.843, 1.0],
    [0.4, 0.545, 1.0],
    [1.0, 0.384, 0.816],
    [0.655, 0.545, 0.98],
    [0.369, 0.902, 0.659],
    [1.0, 0.42, 0.42],
    [1.0, 0.624, 0.263],
    [0.906, 0.906, 0.886],
    [0.0, 0.843, 0.741],
];
const TOOLPATH_ROLE_WIDTHS_MM: [f32; TOOLPATH_ROLE_COUNT] =
    [0.52, 0.46, 0.40, 0.46, 0.42, 0.42, 0.52, 0.48, 0.36, 0.46];
const INTERNAL_ERROR_JSON: &str =
    "{\"ok\":false,\"error\":\"The file could not be processed safely\"}";
static TEMP_OUTPUT_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Error)]
enum EngineError {
    #[error("Unable to open file: {0}")]
    Open(#[from] std::io::Error),
    #[error("Unable to access Android data: {0}")]
    Jni(#[from] jni::errors::Error),
    #[error("Unable to parse STL mesh: {0}")]
    Parse(String),
    #[error("STL contains no vertices")]
    Empty,
}

struct ToolpathPackingRequest<'a> {
    segments: &'a [f32],
    path_starts: &'a [i32],
    path_ends_exclusive: &'a [i32],
    bed_origin_x: f32,
    bed_origin_y: f32,
    min_z_mm: f32,
    max_z_mm: f32,
    opacity: f32,
    depth_contrast: f32,
    reverse_for_early_z: bool,
    render_as_lines: bool,
}

fn packed_toolpath_color(red: f32, green: f32, blue: f32, alpha: f32) -> f32 {
    let channel = |value: f32| (value.clamp(0.0, 1.0) * 255.0).round() as u32;
    f32::from_bits(channel(alpha) << 24 | channel(blue) << 16 | channel(green) << 8 | channel(red))
}

fn pack_toolpath_geometry(request: ToolpathPackingRequest<'_>) -> Result<Vec<f32>, EngineError> {
    if !request
        .segments
        .len()
        .is_multiple_of(TOOLPATH_SEGMENT_FLOATS)
    {
        return Err(EngineError::Parse(
            "Toolpath segment payload is not aligned".to_owned(),
        ));
    }
    let source_segment_count = request.segments.len() / TOOLPATH_SEGMENT_FLOATS;
    if source_segment_count > MAX_PREVIEW_SEGMENTS
        || request.path_starts.len() != request.path_ends_exclusive.len()
        || request.path_starts.len() > MAX_PREVIEW_SEGMENTS
    {
        return Err(EngineError::Parse(
            "Toolpath packing request exceeds its bound".to_owned(),
        ));
    }
    let scalar_values = [
        request.bed_origin_x,
        request.bed_origin_y,
        request.min_z_mm,
        request.max_z_mm,
        request.opacity,
        request.depth_contrast,
    ];
    if scalar_values
        .iter()
        .any(|value| !value.is_finite() || value.abs() > MAX_GCODE_COORDINATE_ABS_MM)
        || request.min_z_mm > request.max_z_mm
        || !(0.0..=1.0).contains(&request.opacity)
        || !(0.0..=1.0).contains(&request.depth_contrast)
    {
        return Err(EngineError::Parse(
            "Toolpath packing parameters are invalid".to_owned(),
        ));
    }

    let mut selected_segment_count = 0usize;
    let mut previous_end = 0usize;
    for (&start, &end) in request
        .path_starts
        .iter()
        .zip(request.path_ends_exclusive.iter())
    {
        let start = usize::try_from(start)
            .map_err(|_| EngineError::Parse("Toolpath path range is invalid".to_owned()))?;
        let end = usize::try_from(end)
            .map_err(|_| EngineError::Parse("Toolpath path range is invalid".to_owned()))?;
        if start < previous_end || start >= end || end > source_segment_count {
            return Err(EngineError::Parse(
                "Toolpath path range is invalid".to_owned(),
            ));
        }
        selected_segment_count = selected_segment_count
            .checked_add(end - start)
            .ok_or_else(|| EngineError::Parse("Toolpath packing size overflow".to_owned()))?;
        previous_end = end;
    }
    if selected_segment_count > MAX_PREVIEW_SEGMENTS {
        return Err(EngineError::Parse(
            "Toolpath packing selection exceeds its bound".to_owned(),
        ));
    }
    let output_capacity = selected_segment_count
        .checked_mul(PACKED_TOOLPATH_FLOATS)
        .ok_or_else(|| EngineError::Parse("Toolpath packing size overflow".to_owned()))?;
    let mut output = Vec::with_capacity(output_capacity);
    let z_span = (request.max_z_mm - request.min_z_mm).max(0.001);
    let mut color_z_bits: Option<u32> = None;
    let mut height_shade_multiplier = 1.0f32;
    let mut packed_role_colors = [0.0f32; TOOLPATH_ROLE_COUNT];
    let mut packed_role_color_valid = [false; TOOLPATH_ROLE_COUNT];

    let mut pack_segment = |segment_index: usize| -> Result<(), EngineError> {
        let offset = segment_index * TOOLPATH_SEGMENT_FLOATS;
        let source = &request.segments[offset..offset + TOOLPATH_SEGMENT_FLOATS];
        if source[..5]
            .iter()
            .any(|value| !value.is_finite() || value.abs() > MAX_GCODE_COORDINATE_ABS_MM)
        {
            return Err(EngineError::Parse(
                "Toolpath packing coordinate is invalid".to_owned(),
            ));
        }
        let role_value = source[5];
        if !role_value.is_finite() || role_value.fract() != 0.0 {
            return Err(EngineError::Parse(
                "Toolpath packing role is invalid".to_owned(),
            ));
        }
        let role = role_value as usize;
        if role >= TOOLPATH_ROLE_COUNT {
            return Err(EngineError::Parse(
                "Toolpath packing role is invalid".to_owned(),
            ));
        }
        let x1 = source[0] - request.bed_origin_x;
        let y1 = source[1] - request.bed_origin_y;
        let x2 = source[2] - request.bed_origin_x;
        let y2 = source[3] - request.bed_origin_y;
        let z = source[4];
        let dx = x2 - x1;
        let dy = y2 - y1;
        if dx * dx + dy * dy < 0.000_001 {
            return Ok(());
        }
        if color_z_bits != Some(z.to_bits()) {
            color_z_bits = Some(z.to_bits());
            packed_role_color_valid.fill(false);
            let normalized_height = ((z - request.min_z_mm) / z_span).clamp(0.0, 1.0);
            let shade = request.depth_contrast * (1.0 - normalized_height) * 0.56;
            height_shade_multiplier = 1.0 - shade;
        }
        if !packed_role_color_valid[role] {
            let base = TOOLPATH_ROLE_COLORS[role];
            packed_role_colors[role] = packed_toolpath_color(
                base[0] * height_shade_multiplier,
                base[1] * height_shade_multiplier,
                base[2] * height_shade_multiplier,
                request.opacity,
            );
            packed_role_color_valid[role] = true;
        }
        let color = packed_role_colors[role];
        let render_z = z + TOOLPATH_Z_OFFSET_MM;
        if request.render_as_lines {
            output.extend_from_slice(&[x1, y1, render_z, color, x2, y2, render_z, color]);
        } else {
            output.extend_from_slice(&[
                x1,
                y1,
                render_z,
                x2,
                y2,
                render_z,
                TOOLPATH_ROLE_WIDTHS_MM[role] / 2.0,
                color,
            ]);
        }
        Ok(())
    };

    if request.reverse_for_early_z {
        for path_index in (0..request.path_starts.len()).rev() {
            let start = request.path_starts[path_index] as usize;
            let end = request.path_ends_exclusive[path_index] as usize;
            for segment_index in (start..end).rev() {
                pack_segment(segment_index)?;
            }
        }
    } else {
        for path_index in 0..request.path_starts.len() {
            let start = request.path_starts[path_index] as usize;
            let end = request.path_ends_exclusive[path_index] as usize;
            for segment_index in start..end {
                pack_segment(segment_index)?;
            }
        }
    }
    Ok(output)
}

struct StlInspection {
    triangles: usize,
    min_mm: [f32; 3],
    max_mm: [f32; 3],
    preview_triangles: Vec<[f32; 9]>,
    preview_triangle_indices: Vec<usize>,
}

#[derive(Clone, Copy, Debug, Hash, PartialEq, Eq, PartialOrd, Ord)]
struct PreviewCell {
    x: u16,
    y: u16,
    z: u16,
}

#[derive(Default)]
struct PreviewCellAccumulator {
    sums: [f64; 3],
    samples: u64,
}

struct PreviewClusterTriangle {
    cells: [PreviewCell; 3],
    source_index: usize,
}

struct PreviewClusterResult {
    triangles: Vec<[f32; 9]>,
    source_indices: Vec<usize>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct StlTransform {
    bed_center_mm: [f32; 2],
    offset_mm: [f32; 2],
    #[serde(default)]
    offset_z_mm: f32,
    rotation_deg: [f32; 3],
    scale: f32,
    #[serde(default)]
    scale_axes: Option<[f32; 3]>,
    #[serde(default)]
    mirror: [bool; 3],
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct StlGroupTransformRequest {
    input_paths: Vec<String>,
    output_paths: Vec<String>,
    transform: StlTransform,
}

impl StlTransform {
    fn scales(&self) -> [f32; 3] {
        self.scale_axes.unwrap_or([self.scale; 3])
    }

    fn maximum_scale(&self) -> f32 {
        self.scales().into_iter().fold(self.scale, f32::max)
    }
}

#[derive(Clone, Copy)]
struct StlTransformFrame {
    source_center_mm: [f32; 3],
    transformed_min_z: f32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StlTransformResponse {
    ok: bool,
    source_center_mm: [f32; 3],
    transformed_min_z: f32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LayOnFaceRequest {
    transform: StlTransform,
    triangle: [f32; 9],
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LayOnFaceResponse {
    ok: bool,
    rotation_deg: [f32; 3],
}

struct GcodeLayerPreview {
    start_layer: usize,
    end_layer: usize,
    layer_count: usize,
    min_z_mm: f32,
    max_z_mm: f32,
    segments: Vec<[f32; 6]>,
}

#[derive(Debug)]
struct PreviewPath {
    order: usize,
    layer: usize,
    role: ToolpathRole,
    segments: Vec<[f32; 6]>,
}

#[derive(Default)]
struct PreviewPathAccumulator {
    paths: Vec<PreviewPath>,
    current: Option<PreviewPath>,
    retained_segments: usize,
    next_order: usize,
}

impl PreviewPathAccumulator {
    fn push(&mut self, layer: usize, role: ToolpathRole, segment: [f32; 6]) {
        let continues_current = self
            .current
            .as_ref()
            .is_some_and(|path| path.layer == layer && path.role == role);
        if !continues_current {
            self.finish_current();
            self.current = Some(PreviewPath {
                order: self.next_order,
                layer,
                role,
                segments: Vec::new(),
            });
            self.next_order = self.next_order.saturating_add(1);
        }
        if let Some(current) = self.current.as_mut() {
            current.segments.push(segment);
            if current.segments.len() > PREVIEW_COMPACTION_THRESHOLD {
                current.segments = simplify_continuous_path(
                    std::mem::take(&mut current.segments),
                    MAX_PREVIEW_SEGMENTS,
                );
            }
        }
    }

    fn break_path(&mut self) {
        self.finish_current();
    }

    fn finish(mut self) -> Vec<[f32; 6]> {
        self.finish_current();
        if self.retained_segments > MAX_PREVIEW_SEGMENTS {
            self.compact();
        }
        self.paths.sort_by_key(|path| path.order);
        self.paths
            .into_iter()
            .flat_map(|path| path.segments)
            .collect()
    }

    fn finish_current(&mut self) {
        let Some(path) = self.current.take() else {
            return;
        };
        if path.segments.is_empty() {
            return;
        }
        self.retained_segments = self.retained_segments.saturating_add(path.segments.len());
        self.paths.push(path);
        if self.retained_segments > PREVIEW_COMPACTION_THRESHOLD {
            self.compact();
        }
    }

    fn compact(&mut self) {
        self.paths = compact_preview_paths(std::mem::take(&mut self.paths), MAX_PREVIEW_SEGMENTS);
        self.retained_segments = self.paths.iter().map(|path| path.segments.len()).sum();
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u8)]
enum ToolpathRole {
    OuterWall = 0,
    InnerWall = 1,
    Infill = 2,
    TopSurface = 3,
    InternalSolid = 4,
    Support = 5,
    Bridge = 6,
    Adhesion = 7,
    #[default]
    Other = 8,
    BottomSurface = 9,
}

impl ToolpathRole {
    const COUNT: usize = 10;

    fn from_label(label: &str) -> Self {
        let normalized = label.trim().to_ascii_lowercase();
        if normalized.contains("outer wall") || normalized.contains("external perimeter") {
            Self::OuterWall
        } else if normalized.contains("inner wall") || normalized.contains("perimeter") {
            Self::InnerWall
        } else if normalized.contains("bridge") || normalized.contains("overhang") {
            Self::Bridge
        } else if normalized.contains("support") {
            Self::Support
        } else if normalized.contains("skirt")
            || normalized.contains("brim")
            || normalized.contains("raft")
        {
            Self::Adhesion
        } else if normalized.contains("top surface") {
            Self::TopSurface
        } else if normalized.contains("bottom surface") {
            Self::BottomSurface
        } else if normalized.contains("solid") {
            Self::InternalSolid
        } else if normalized.contains("infill") {
            Self::Infill
        } else {
            Self::Other
        }
    }

    fn code(self) -> f32 {
        self as u8 as f32
    }
}

fn simplify_continuous_path(segments: Vec<[f32; 6]>, limit: usize) -> Vec<[f32; 6]> {
    if segments.len() <= limit {
        return segments;
    }
    if limit == 0 {
        return Vec::new();
    }

    let source_count = segments.len();
    let mut simplified = Vec::with_capacity(limit);
    let mut start_x = segments[0][0];
    let mut start_y = segments[0][1];
    for selected_index in 0..limit {
        let end_index = ((selected_index + 1) * source_count / limit)
            .saturating_sub(1)
            .min(source_count - 1);
        let source = segments[end_index];
        simplified.push([start_x, start_y, source[2], source[3], source[4], source[5]]);
        start_x = source[2];
        start_y = source[3];
    }
    simplified
}

fn compact_preview_paths(mut paths: Vec<PreviewPath>, limit: usize) -> Vec<PreviewPath> {
    if limit == 0 {
        return Vec::new();
    }
    let total_segments: usize = paths.iter().map(|path| path.segments.len()).sum();
    if total_segments <= limit {
        return paths;
    }
    if paths.len() == 1 {
        paths[0].segments = simplify_continuous_path(std::mem::take(&mut paths[0].segments), limit);
        return paths;
    }

    // A whole-path sampler leaves only a handful of complete layers on a dense
    // model. The result is technically connected but looks like floating
    // spaghetti. Give exterior/surface roles most of the budget, then simplify
    // every retained loop so the silhouette remains present across the height.
    const ROLE_WEIGHTS: [usize; ToolpathRole::COUNT] = [
        50, // outer wall
        5,  // inner wall
        2,  // sparse infill
        12, // top surface
        3,  // internal solid
        7,  // support
        8,  // bridge
        2,  // adhesion
        1,  // other
        10, // bottom surface
    ];
    let mut role_paths: [Vec<PreviewPath>; ToolpathRole::COUNT] =
        std::array::from_fn(|_| Vec::new());
    for path in paths.drain(..) {
        role_paths[path.role as usize].push(path);
    }
    let role_totals: [usize; ToolpathRole::COUNT] = std::array::from_fn(|role| {
        role_paths[role]
            .iter()
            .map(|path| path.segments.len())
            .sum()
    });
    let present_weight: usize = (0..ToolpathRole::COUNT)
        .filter(|role| role_totals[*role] > 0)
        .map(|role| ROLE_WEIGHTS[role])
        .sum();
    let mut role_budgets = [0usize; ToolpathRole::COUNT];
    if present_weight > 0 {
        for role in 0..ToolpathRole::COUNT {
            role_budgets[role] =
                role_totals[role].min(limit.saturating_mul(ROLE_WEIGHTS[role]) / present_weight);
        }
    }
    let priority = [0usize, 3, 9, 6, 5, 1, 4, 7, 2, 8];
    let mut remaining = limit.saturating_sub(role_budgets.iter().sum());
    for role in priority {
        let extra = role_totals[role]
            .saturating_sub(role_budgets[role])
            .min(remaining);
        role_budgets[role] += extra;
        remaining -= extra;
    }

    let mut compacted = Vec::new();
    for role in 0..ToolpathRole::COUNT {
        compacted.extend(compact_role_paths(
            std::mem::take(&mut role_paths[role]),
            role_budgets[role],
            role,
        ));
    }
    compacted.sort_by_key(|path| path.order);
    compacted
}

fn compact_role_paths(mut paths: Vec<PreviewPath>, budget: usize, role: usize) -> Vec<PreviewPath> {
    if budget == 0 || paths.is_empty() {
        return Vec::new();
    }
    let total: usize = paths.iter().map(|path| path.segments.len()).sum();
    if total <= budget {
        return paths;
    }

    let minimum_for = |path: &PreviewPath| {
        let preferred = if matches!(role, 0 | 3 | 5 | 6 | 9) {
            3
        } else {
            2
        };
        path.segments.len().min(preferred)
    };
    let minimum_total: usize = paths.iter().map(minimum_for).sum();
    let mut allocations = vec![0usize; paths.len()];
    if minimum_total <= budget {
        for (index, path) in paths.iter().enumerate() {
            allocations[index] = minimum_for(path);
        }
        let remaining = budget - minimum_total;
        let possible_extra: usize = paths
            .iter()
            .zip(&allocations)
            .map(|(path, allocation)| path.segments.len() - allocation)
            .sum();
        if possible_extra > 0 {
            for (index, path) in paths.iter().enumerate() {
                let extra = path.segments.len() - allocations[index];
                allocations[index] += remaining.saturating_mul(extra) / possible_extra;
            }
            let mut leftover = budget.saturating_sub(allocations.iter().sum());
            while leftover > 0 {
                let mut progressed = false;
                for (allocation, path) in allocations.iter_mut().zip(&paths) {
                    if *allocation < path.segments.len() {
                        *allocation += 1;
                        leftover -= 1;
                        progressed = true;
                        if leftover == 0 {
                            break;
                        }
                    }
                }
                if !progressed {
                    break;
                }
            }
        }
    } else {
        let average_minimum = minimum_total as f64 / paths.len() as f64;
        let target_count =
            ((budget as f64 / average_minimum).floor() as usize).clamp(1, paths.len());
        let mut used = 0usize;
        for selection in 0..target_count {
            let index = if target_count == 1 {
                paths.len() / 2
            } else {
                selection * (paths.len() - 1) / (target_count - 1)
            };
            let allocation = minimum_for(&paths[index]).min(budget - used);
            allocations[index] = allocation;
            used += allocation;
        }
    }

    paths
        .drain(..)
        .zip(allocations)
        .filter_map(|(mut path, allocation)| {
            if allocation == 0 {
                None
            } else {
                path.segments = simplify_continuous_path(path.segments, allocation);
                Some(path)
            }
        })
        .collect()
}

#[derive(Serialize)]
struct ErrorResponse<'a> {
    ok: bool,
    error: &'a str,
}

trait ReadSeek: Read + Seek {}

impl<T: Read + Seek> ReadSeek for T {}

struct LineBoundedReader<R> {
    inner: R,
    bytes_since_newline: usize,
}

impl<R> LineBoundedReader<R> {
    fn new(inner: R) -> Self {
        Self {
            inner,
            bytes_since_newline: 0,
        }
    }
}

impl<R: Read> Read for LineBoundedReader<R> {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        let count = self.inner.read(buffer)?;
        for byte in &buffer[..count] {
            if *byte == b'\n' {
                self.bytes_since_newline = 0;
            } else {
                self.bytes_since_newline = self
                    .bytes_since_newline
                    .checked_add(1)
                    .ok_or_else(|| std::io::Error::other("text line length overflow"))?;
                if self.bytes_since_newline > MAX_TEXT_LINE_BYTES {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "text line exceeds the supported limit",
                    ));
                }
            }
        }
        Ok(count)
    }
}

impl<R: Seek> Seek for LineBoundedReader<R> {
    fn seek(&mut self, position: SeekFrom) -> std::io::Result<u64> {
        let offset = self.inner.seek(position)?;
        self.bytes_since_newline = 0;
        Ok(offset)
    }
}

fn open_regular_file(path: &str) -> Result<File, EngineError> {
    let file = File::open(path)?;
    if !file.metadata()?.is_file() {
        return Err(EngineError::Parse(
            "Input must be a regular file".to_owned(),
        ));
    }
    Ok(file)
}

fn open_stl_input(path: &str) -> Result<Box<dyn ReadSeek>, EngineError> {
    let mut file = open_regular_file(path)?;
    let size = file.metadata()?.len();
    if size == 0 {
        return Err(EngineError::Empty);
    }
    if size > MAX_MODEL_IMPORT_BYTES {
        return Err(EngineError::Parse(
            "STL exceeds the supported import size".to_owned(),
        ));
    }
    let mut prefix = [0u8; 6];
    let prefix_length = file.read(&mut prefix)?;
    file.seek(SeekFrom::Start(0))?;
    if prefix_length == prefix.len() && prefix == *b"solid " {
        Ok(Box::new(LineBoundedReader::new(file)))
    } else {
        Ok(Box::new(file))
    }
}

fn validate_triangle(triangle: &stl_io::Triangle) -> Result<(), EngineError> {
    let invalid_normal = triangle
        .normal
        .0
        .iter()
        .any(|value| !value.is_finite() || value.abs() > MAX_STL_COORDINATE_ABS_MM);
    let invalid_vertex = triangle
        .vertices
        .iter()
        .flat_map(|vertex| vertex.0)
        .any(|value| !value.is_finite() || value.abs() > MAX_STL_COORDINATE_ABS_MM);
    if invalid_normal || invalid_vertex {
        return Err(EngineError::Parse(
            "STL contains an invalid or out-of-range coordinate".to_owned(),
        ));
    }
    Ok(())
}

struct TemporaryOutput {
    path: PathBuf,
    committed: bool,
}

impl Drop for TemporaryOutput {
    fn drop(&mut self) {
        if !self.committed {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

fn create_temporary_output(output_path: &Path) -> Result<(File, TemporaryOutput), EngineError> {
    let parent = output_path.parent().unwrap_or_else(|| Path::new("."));
    let file_name = output_path
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or("model.stl");
    for _ in 0..128 {
        let sequence = TEMP_OUTPUT_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let path = parent.join(format!(
            ".{file_name}.{}.{}.tmp",
            std::process::id(),
            sequence
        ));
        match OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(file) => {
                return Ok((
                    file,
                    TemporaryOutput {
                        path,
                        committed: false,
                    },
                ));
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error.into()),
        }
    }
    Err(EngineError::Parse(
        "Unable to reserve a temporary STL output".to_owned(),
    ))
}

fn inspect_stl(path: &str) -> Result<StlInspection, EngineError> {
    let mut file = open_stl_input(path)?;
    let triangles = stl_io::create_stl_reader(&mut file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let mut triangle_count = 0usize;
    let mut surface_triangle_count = 0usize;
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    let mut preview_triangles = Vec::with_capacity(PREVIEW_TRIANGLE_LIMIT);
    let mut preview_triangle_indices = Vec::with_capacity(PREVIEW_TRIANGLE_LIMIT);

    for triangle in triangles {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        validate_triangle(&triangle)?;
        let vertices = triangle.vertices.map(|vertex| vertex.0);
        let ordinal = triangle_count;
        triangle_count = triangle_count
            .checked_add(1)
            .ok_or_else(|| EngineError::Parse("STL contains too many triangles".to_owned()))?;
        if triangle_area_squared(vertices) <= f32::EPSILON {
            continue;
        }
        surface_triangle_count = surface_triangle_count.checked_add(1).ok_or_else(|| {
            EngineError::Parse("STL contains too many surface triangles".to_owned())
        })?;
        for vertex in vertices {
            for axis in 0..3 {
                min[axis] = min[axis].min(vertex[axis]);
                max[axis] = max[axis].max(vertex[axis]);
            }
        }
        if preview_triangles.len() < PREVIEW_TRIANGLE_LIMIT {
            let [a, b, c] = vertices;
            preview_triangles.push([a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]]);
            preview_triangle_indices.push(ordinal);
        }
    }
    if surface_triangle_count == 0 {
        return Err(EngineError::Empty);
    }

    if surface_triangle_count > PREVIEW_TRIANGLE_LIMIT {
        let clustered = clustered_stl_preview(path, min, max)?;
        preview_triangles = clustered.triangles;
        preview_triangle_indices = clustered.source_indices;
    }

    Ok(StlInspection {
        triangles: triangle_count,
        min_mm: min,
        max_mm: max,
        preview_triangles,
        preview_triangle_indices,
    })
}

fn clustered_stl_preview(
    path: &str,
    min: [f32; 3],
    max: [f32; 3],
) -> Result<PreviewClusterResult, EngineError> {
    // Keeping every Nth source triangle produces isolated flakes on dense meshes.
    // Vertex clustering instead collapses neighboring facets onto shared cells so
    // the bounded Android preview remains a connected, opaque surface.
    let mut resolution = PREVIEW_CLUSTER_START_RESOLUTION;
    loop {
        if let Some(result) = clustered_stl_preview_at_resolution(path, min, max, resolution)? {
            if result.triangles.len() <= PREVIEW_TRIANGLE_LIMIT || resolution <= 2 {
                return Ok(result);
            }
            let ratio =
                (PREVIEW_TRIANGLE_LIMIT as f64 / result.triangles.len() as f64).sqrt() * 0.9;
            let next =
                ((resolution as f64 * ratio).floor() as u16).clamp(2, resolution.saturating_sub(1));
            resolution = next;
        } else {
            resolution = (resolution / 2).max(2);
        }
    }
}

fn clustered_stl_preview_at_resolution(
    path: &str,
    min: [f32; 3],
    max: [f32; 3],
    resolution: u16,
) -> Result<Option<PreviewClusterResult>, EngineError> {
    let mut file = open_stl_input(path)?;
    let triangles = stl_io::create_stl_reader(&mut file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let mut cells = HashMap::<PreviewCell, PreviewCellAccumulator>::new();
    let mut seen_triangles = HashSet::<[PreviewCell; 3]>::new();
    let mut clustered_triangles = Vec::new();

    for (source_index, triangle) in triangles.enumerate() {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        validate_triangle(&triangle)?;
        let vertices = triangle.vertices.map(|vertex| vertex.0);
        if triangle_area_squared(vertices) <= f32::EPSILON {
            continue;
        }
        let triangle_cells = vertices.map(|vertex| preview_cell(vertex, min, max, resolution));
        for (cell, vertex) in triangle_cells.into_iter().zip(vertices) {
            let accumulator = cells.entry(cell).or_default();
            for (axis, value) in vertex.into_iter().enumerate() {
                accumulator.sums[axis] += value as f64;
            }
            accumulator.samples = accumulator.samples.saturating_add(1);
        }
        if triangle_cells[0] == triangle_cells[1]
            || triangle_cells[1] == triangle_cells[2]
            || triangle_cells[2] == triangle_cells[0]
        {
            continue;
        }
        let mut canonical = triangle_cells;
        canonical.sort_unstable();
        if seen_triangles.insert(canonical) {
            clustered_triangles.push(PreviewClusterTriangle {
                cells: triangle_cells,
                source_index,
            });
            if clustered_triangles.len() > PREVIEW_CLUSTER_WORK_LIMIT {
                return Ok(None);
            }
        }
    }

    let mut preview_triangles = Vec::with_capacity(clustered_triangles.len());
    let mut source_indices = Vec::with_capacity(clustered_triangles.len());
    for triangle in clustered_triangles {
        let mut vertices = [[0.0_f32; 3]; 3];
        for (index, cell) in triangle.cells.into_iter().enumerate() {
            let accumulator = cells.get(&cell).ok_or_else(|| {
                EngineError::Parse("STL preview clustering lost vertex state".to_owned())
            })?;
            if accumulator.samples == 0 {
                return Err(EngineError::Parse(
                    "STL preview clustering produced an empty vertex".to_owned(),
                ));
            }
            vertices[index] = accumulator
                .sums
                .map(|sum| (sum / accumulator.samples as f64) as f32);
        }
        if triangle_area_squared(vertices) <= f32::EPSILON {
            continue;
        }
        let [a, b, c] = vertices;
        preview_triangles.push([a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]]);
        source_indices.push(triangle.source_index);
    }
    Ok(Some(PreviewClusterResult {
        triangles: preview_triangles,
        source_indices,
    }))
}

fn preview_cell(vertex: [f32; 3], min: [f32; 3], max: [f32; 3], resolution: u16) -> PreviewCell {
    let coordinate = |axis: usize| {
        let span = max[axis] - min[axis];
        if span <= f32::EPSILON {
            return 0;
        }
        (((vertex[axis] - min[axis]) / span * resolution as f32).floor() as i32)
            .clamp(0, resolution as i32 - 1) as u16
    };
    PreviewCell {
        x: coordinate(0),
        y: coordinate(1),
        z: coordinate(2),
    }
}

fn model_preview_payload(inspection: StlInspection) -> Result<Vec<f32>, EngineError> {
    let preview_count = inspection.preview_triangles.len();
    if preview_count == 0
        || preview_count > PREVIEW_TRIANGLE_LIMIT
        || preview_count != inspection.preview_triangle_indices.len()
        || inspection.triangles > MODEL_PREVIEW_MAX_EXACT_INTEGER
    {
        return Err(EngineError::Parse(
            "STL preview payload is inconsistent".to_owned(),
        ));
    }
    let payload_floats = MODEL_PREVIEW_HEADER_FLOATS
        .checked_add(
            preview_count
                .checked_mul(10)
                .ok_or_else(|| EngineError::Parse("STL preview payload is too large".to_owned()))?,
        )
        .ok_or_else(|| EngineError::Parse("STL preview payload is too large".to_owned()))?;
    let mut payload = Vec::with_capacity(payload_floats);
    payload.extend_from_slice(&[
        MODEL_PREVIEW_PAYLOAD_MAGIC,
        MODEL_PREVIEW_PAYLOAD_VERSION,
        inspection.triangles as f32,
        inspection.min_mm[0],
        inspection.min_mm[1],
        inspection.min_mm[2],
        inspection.max_mm[0],
        inspection.max_mm[1],
        inspection.max_mm[2],
        preview_count as f32,
    ]);
    for triangle in inspection.preview_triangles {
        payload.extend_from_slice(&triangle);
    }
    for source_index in inspection.preview_triangle_indices {
        payload.push(source_index as f32);
    }
    if payload.len() != payload_floats {
        return Err(EngineError::Parse(
            "STL preview payload length is inconsistent".to_owned(),
        ));
    }
    Ok(payload)
}

fn triangle_area_squared(vertices: [[f32; 3]; 3]) -> f32 {
    let [a, b, c] = vertices;
    let ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    let ac = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    let cross = [
        ab[1] * ac[2] - ab[2] * ac[1],
        ab[2] * ac[0] - ab[0] * ac[2],
        ab[0] * ac[1] - ab[1] * ac[0],
    ];
    cross.into_iter().map(|value| value * value).sum()
}

fn rotate_vertex(mut vertex: [f32; 3], rotation_deg: [f32; 3]) -> [f32; 3] {
    let [rx, ry, rz] = rotation_deg.map(f32::to_radians);
    let (sin_x, cos_x) = rx.sin_cos();
    let (sin_y, cos_y) = ry.sin_cos();
    let (sin_z, cos_z) = rz.sin_cos();

    vertex = [
        vertex[0],
        vertex[1] * cos_x - vertex[2] * sin_x,
        vertex[1] * sin_x + vertex[2] * cos_x,
    ];
    vertex = [
        vertex[0] * cos_y + vertex[2] * sin_y,
        vertex[1],
        -vertex[0] * sin_y + vertex[2] * cos_y,
    ];
    [
        vertex[0] * cos_z - vertex[1] * sin_z,
        vertex[0] * sin_z + vertex[1] * cos_z,
        vertex[2],
    ]
}

fn transformed_vertex(
    vertex: [f32; 3],
    source_center: [f32; 3],
    transformed_min_z: f32,
    transform: &StlTransform,
) -> [f32; 3] {
    let rotated = rotate_vertex(
        local_vertex(vertex, source_center, transform),
        transform.rotation_deg,
    );
    [
        rotated[0] + transform.bed_center_mm[0] + transform.offset_mm[0],
        rotated[1] + transform.bed_center_mm[1] + transform.offset_mm[1],
        rotated[2] - transformed_min_z + transform.offset_z_mm,
    ]
}

fn local_vertex(vertex: [f32; 3], source_center: [f32; 3], transform: &StlTransform) -> [f32; 3] {
    let scales = transform.scales();
    [
        (vertex[0] - source_center[0]) * scales[0] * if transform.mirror[0] { -1.0 } else { 1.0 },
        (vertex[1] - source_center[1]) * scales[1] * if transform.mirror[1] { -1.0 } else { 1.0 },
        (vertex[2] - source_center[2]) * scales[2] * if transform.mirror[2] { -1.0 } else { 1.0 },
    ]
}

fn triangle_normal(vertices: [[f32; 3]; 3]) -> [f32; 3] {
    let u = [
        vertices[1][0] - vertices[0][0],
        vertices[1][1] - vertices[0][1],
        vertices[1][2] - vertices[0][2],
    ];
    let v = [
        vertices[2][0] - vertices[0][0],
        vertices[2][1] - vertices[0][1],
        vertices[2][2] - vertices[0][2],
    ];
    let normal = [
        u[1] * v[2] - u[2] * v[1],
        u[2] * v[0] - u[0] * v[2],
        u[0] * v[1] - u[1] * v[0],
    ];
    let length = (normal[0].powi(2) + normal[1].powi(2) + normal[2].powi(2)).sqrt();
    if length <= f32::EPSILON {
        [0.0, 0.0, 0.0]
    } else {
        [normal[0] / length, normal[1] / length, normal[2] / length]
    }
}

type Matrix3 = [[f32; 3]; 3];

fn rotation_matrix(rotation_deg: [f32; 3]) -> Matrix3 {
    let x = rotate_vertex([1.0, 0.0, 0.0], rotation_deg);
    let y = rotate_vertex([0.0, 1.0, 0.0], rotation_deg);
    let z = rotate_vertex([0.0, 0.0, 1.0], rotation_deg);
    [[x[0], y[0], z[0]], [x[1], y[1], z[1]], [x[2], y[2], z[2]]]
}

fn multiply_matrices(left: Matrix3, right: Matrix3) -> Matrix3 {
    let mut result = [[0.0; 3]; 3];
    for row in 0..3 {
        for column in 0..3 {
            result[row][column] = (0..3)
                .map(|index| left[row][index] * right[index][column])
                .sum();
        }
    }
    result
}

fn normalize_vector(vector: [f32; 3]) -> Result<[f32; 3], EngineError> {
    let length = vector.iter().map(|value| value * value).sum::<f32>().sqrt();
    if !length.is_finite() || length <= 1.0e-6 {
        return Err(EngineError::Parse(
            "Selected face has no usable normal".to_owned(),
        ));
    }
    Ok(vector.map(|value| value / length))
}

fn cross(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

fn quaternion_between(from: [f32; 3], to: [f32; 3]) -> Result<[f32; 4], EngineError> {
    let from = normalize_vector(from)?;
    let to = normalize_vector(to)?;
    let dot = from
        .iter()
        .zip(to)
        .map(|(left, right)| left * right)
        .sum::<f32>()
        .clamp(-1.0, 1.0);
    if dot >= 1.0 - 1.0e-6 {
        return Ok([1.0, 0.0, 0.0, 0.0]);
    }
    if dot <= -1.0 + 1.0e-6 {
        let basis = if from[0].abs() <= from[1].abs() && from[0].abs() <= from[2].abs() {
            [1.0, 0.0, 0.0]
        } else if from[1].abs() <= from[2].abs() {
            [0.0, 1.0, 0.0]
        } else {
            [0.0, 0.0, 1.0]
        };
        let axis = normalize_vector(cross(from, basis))?;
        return Ok([0.0, axis[0], axis[1], axis[2]]);
    }
    let axis = cross(from, to);
    let quaternion = [1.0 + dot, axis[0], axis[1], axis[2]];
    let length = quaternion
        .iter()
        .map(|value| value * value)
        .sum::<f32>()
        .sqrt();
    Ok(quaternion.map(|value| value / length))
}

fn quaternion_matrix([w, x, y, z]: [f32; 4]) -> Matrix3 {
    [
        [
            1.0 - 2.0 * (y * y + z * z),
            2.0 * (x * y - z * w),
            2.0 * (x * z + y * w),
        ],
        [
            2.0 * (x * y + z * w),
            1.0 - 2.0 * (x * x + z * z),
            2.0 * (y * z - x * w),
        ],
        [
            2.0 * (x * z - y * w),
            2.0 * (y * z + x * w),
            1.0 - 2.0 * (x * x + y * y),
        ],
    ]
}

fn matrix_to_euler_degrees(matrix: Matrix3) -> [f32; 3] {
    let rotation_y = (-matrix[2][0]).clamp(-1.0, 1.0).asin();
    let cosine_y = rotation_y.cos();
    let (rotation_x, rotation_z) = if cosine_y.abs() > 1.0e-5 {
        (
            matrix[2][1].atan2(matrix[2][2]),
            matrix[1][0].atan2(matrix[0][0]),
        )
    } else {
        ((-matrix[1][2]).atan2(matrix[1][1]), 0.0)
    };
    [rotation_x, rotation_y, rotation_z].map(|radians| {
        let normalized = (radians.to_degrees() + 180.0).rem_euclid(360.0) - 180.0;
        if normalized.abs() < 1.0e-5 {
            0.0
        } else {
            normalized
        }
    })
}

fn transformed_face_vertices(
    triangle: [f32; 9],
    transform: &StlTransform,
    rotation_deg: [f32; 3],
) -> [[f32; 3]; 3] {
    let scales = transform.scales();
    let signs = transform
        .mirror
        .map(|mirrored| if mirrored { -1.0 } else { 1.0 });
    let mut vertices = std::array::from_fn(|index| {
        rotate_vertex(
            [
                triangle[index * 3] * scales[0] * signs[0],
                triangle[index * 3 + 1] * scales[1] * signs[1],
                triangle[index * 3 + 2] * scales[2] * signs[2],
            ],
            rotation_deg,
        )
    });
    if transform
        .mirror
        .iter()
        .filter(|&&mirrored| mirrored)
        .count()
        % 2
        == 1
    {
        vertices.swap(1, 2);
    }
    vertices
}

fn lay_on_face(request: &LayOnFaceRequest) -> Result<LayOnFaceResponse, EngineError> {
    let transform = &request.transform;
    if transform
        .rotation_deg
        .iter()
        .chain(transform.scales().iter())
        .any(|value| !value.is_finite())
        || transform
            .scales()
            .iter()
            .any(|scale| !(0.05..=10.0).contains(scale))
        || request
            .triangle
            .iter()
            .any(|value| !value.is_finite() || value.abs() > MAX_STL_COORDINATE_ABS_MM)
    {
        return Err(EngineError::Parse(
            "Selected face transform is invalid".to_owned(),
        ));
    }
    let current_vertices =
        transformed_face_vertices(request.triangle, transform, transform.rotation_deg);
    let current_normal = triangle_normal(current_vertices);
    let alignment = quaternion_between(current_normal, [0.0, 0.0, -1.0])?;
    let next_matrix = multiply_matrices(
        quaternion_matrix(alignment),
        rotation_matrix(transform.rotation_deg),
    );
    let rotation_deg = matrix_to_euler_degrees(next_matrix);
    let next_normal = triangle_normal(transformed_face_vertices(
        request.triangle,
        transform,
        rotation_deg,
    ));
    if next_normal[2] > -0.999 || next_normal[0].abs() > 0.002 || next_normal[1].abs() > 0.002 {
        return Err(EngineError::Parse(
            "Selected face could not be aligned to the bed".to_owned(),
        ));
    }
    Ok(LayOnFaceResponse {
        ok: true,
        rotation_deg,
    })
}

fn write_f32(writer: &mut impl Write, value: f32) -> Result<(), EngineError> {
    writer.write_all(&value.to_le_bytes())?;
    Ok(())
}

fn transform_stl(
    input_path: &str,
    output_path: &str,
    transform: &StlTransform,
) -> Result<StlTransformFrame, EngineError> {
    let input = Path::new(input_path);
    let output = Path::new(output_path);
    if input == output
        || std::fs::canonicalize(input)
            .ok()
            .zip(std::fs::canonicalize(output).ok())
            .is_some_and(|(input, output)| input == output)
    {
        return Err(EngineError::Parse(
            "Input and output STL paths must be different".to_owned(),
        ));
    }
    if !transform.scale.is_finite()
        || transform
            .scales()
            .iter()
            .any(|scale| !scale.is_finite() || !(0.05..=10.0).contains(scale))
    {
        return Err(EngineError::Parse(
            "Axis scales must be between 5% and 1000%".to_owned(),
        ));
    }
    if transform
        .bed_center_mm
        .iter()
        .chain(transform.offset_mm.iter())
        .chain(std::iter::once(&transform.offset_z_mm))
        .chain(transform.rotation_deg.iter())
        .any(|value| !value.is_finite() || value.abs() > MAX_STL_COORDINATE_ABS_MM)
    {
        return Err(EngineError::Parse(
            "Transform contains an invalid value".to_owned(),
        ));
    }

    let mut first_pass_file = open_stl_input(input_path)?;
    let first_pass = stl_io::create_stl_reader(&mut first_pass_file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let mut triangle_count = 0u32;
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    for triangle in first_pass {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        validate_triangle(&triangle)?;
        let vertices = triangle.vertices.map(|vertex| vertex.0);
        triangle_count = triangle_count
            .checked_add(1)
            .ok_or_else(|| EngineError::Parse("STL contains too many triangles".to_owned()))?;
        for vertex in vertices {
            for axis in 0..3 {
                min[axis] = min[axis].min(vertex[axis]);
                max[axis] = max[axis].max(vertex[axis]);
            }
        }
    }
    if triangle_count == 0 {
        return Err(EngineError::Empty);
    }

    let source_center = [
        (min[0] + max[0]) / 2.0,
        (min[1] + max[1]) / 2.0,
        (min[2] + max[2]) / 2.0,
    ];
    let mut minimum_pass_file = open_stl_input(input_path)?;
    let minimum_pass = stl_io::create_stl_reader(&mut minimum_pass_file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let mut transformed_min_z = f32::INFINITY;
    for triangle in minimum_pass {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        validate_triangle(&triangle)?;
        for vertex in triangle.vertices {
            let local = local_vertex(vertex.0, source_center, transform);
            transformed_min_z =
                transformed_min_z.min(rotate_vertex(local, transform.rotation_deg)[2]);
        }
    }

    if !transformed_min_z.is_finite() {
        return Err(EngineError::Parse(
            "STL transform produced an invalid coordinate".to_owned(),
        ));
    }

    let mut output_pass_file = open_stl_input(input_path)?;
    let output_pass = stl_io::create_stl_reader(&mut output_pass_file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let (output_file, mut temporary) = create_temporary_output(output)?;
    let mut writer = BufWriter::new(output_file);
    writer.write_all(&[0u8; 80])?;
    writer.write_all(&triangle_count.to_le_bytes())?;
    let reverses_winding = transform
        .mirror
        .iter()
        .filter(|mirrored| **mirrored)
        .count()
        % 2
        == 1;
    for triangle in output_pass {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        validate_triangle(&triangle)?;
        let mut vertices = triangle.vertices.map(|vertex| {
            transformed_vertex(vertex.0, source_center, transformed_min_z, transform)
        });
        if reverses_winding {
            vertices.swap(1, 2);
        }
        if vertices.iter().flatten().any(|value| {
            !value.is_finite()
                || value.abs() > MAX_STL_COORDINATE_ABS_MM * transform.maximum_scale().max(1.0)
        }) {
            return Err(EngineError::Parse(
                "STL transform produced an invalid or out-of-range coordinate".to_owned(),
            ));
        }
        for value in triangle_normal(vertices) {
            write_f32(&mut writer, value)?;
        }
        for vertex in vertices {
            for value in vertex {
                write_f32(&mut writer, value)?;
            }
        }
        writer.write_all(&0u16.to_le_bytes())?;
    }
    writer.flush()?;
    writer.get_ref().sync_all()?;
    drop(writer);
    std::fs::rename(&temporary.path, output)?;
    temporary.committed = true;
    Ok(StlTransformFrame {
        source_center_mm: source_center,
        transformed_min_z,
    })
}

fn transform_stl_group(
    input_paths: &[String],
    output_paths: &[String],
    transform: &StlTransform,
) -> Result<StlTransformFrame, EngineError> {
    const MAX_GROUP_VOLUMES: usize = 64;
    if input_paths.is_empty()
        || input_paths.len() > MAX_GROUP_VOLUMES
        || input_paths.len() != output_paths.len()
    {
        return Err(EngineError::Parse(
            "STL volume group has an invalid size".to_owned(),
        ));
    }
    if output_paths.iter().collect::<HashSet<_>>().len() != output_paths.len() {
        return Err(EngineError::Parse(
            "STL volume group contains duplicate outputs".to_owned(),
        ));
    }
    for (input_path, output_path) in input_paths.iter().zip(output_paths) {
        let input = Path::new(input_path);
        let output = Path::new(output_path);
        if input == output
            || std::fs::canonicalize(input)
                .ok()
                .zip(std::fs::canonicalize(output).ok())
                .is_some_and(|(input, output)| input == output)
        {
            return Err(EngineError::Parse(
                "Input and output STL paths must be different".to_owned(),
            ));
        }
    }
    if !transform.scale.is_finite()
        || transform
            .scales()
            .iter()
            .any(|scale| !scale.is_finite() || !(0.05..=10.0).contains(scale))
    {
        return Err(EngineError::Parse(
            "Axis scales must be between 5% and 1000%".to_owned(),
        ));
    }
    if transform
        .bed_center_mm
        .iter()
        .chain(transform.offset_mm.iter())
        .chain(std::iter::once(&transform.offset_z_mm))
        .chain(transform.rotation_deg.iter())
        .any(|value| !value.is_finite() || value.abs() > MAX_STL_COORDINATE_ABS_MM)
    {
        return Err(EngineError::Parse(
            "Transform contains an invalid value".to_owned(),
        ));
    }

    let mut triangle_counts = Vec::with_capacity(input_paths.len());
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    for input_path in input_paths {
        let mut first_pass_file = open_stl_input(input_path)?;
        let first_pass = stl_io::create_stl_reader(&mut first_pass_file)
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        let mut triangle_count = 0u32;
        for triangle in first_pass {
            let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
            validate_triangle(&triangle)?;
            triangle_count = triangle_count
                .checked_add(1)
                .ok_or_else(|| EngineError::Parse("STL contains too many triangles".to_owned()))?;
            for vertex in triangle.vertices.map(|vertex| vertex.0) {
                for axis in 0..3 {
                    min[axis] = min[axis].min(vertex[axis]);
                    max[axis] = max[axis].max(vertex[axis]);
                }
            }
        }
        if triangle_count == 0 {
            return Err(EngineError::Empty);
        }
        triangle_counts.push(triangle_count);
    }

    let source_center = [
        (min[0] + max[0]) / 2.0,
        (min[1] + max[1]) / 2.0,
        (min[2] + max[2]) / 2.0,
    ];
    let mut transformed_min_z = f32::INFINITY;
    for input_path in input_paths {
        let mut minimum_pass_file = open_stl_input(input_path)?;
        let minimum_pass = stl_io::create_stl_reader(&mut minimum_pass_file)
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        for triangle in minimum_pass {
            let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
            validate_triangle(&triangle)?;
            for vertex in triangle.vertices {
                let local = local_vertex(vertex.0, source_center, transform);
                transformed_min_z =
                    transformed_min_z.min(rotate_vertex(local, transform.rotation_deg)[2]);
            }
        }
    }
    if !transformed_min_z.is_finite() {
        return Err(EngineError::Parse(
            "STL transform produced an invalid coordinate".to_owned(),
        ));
    }

    let reverses_winding = transform
        .mirror
        .iter()
        .filter(|mirrored| **mirrored)
        .count()
        % 2
        == 1;
    let mut temporary_outputs = Vec::with_capacity(input_paths.len());
    for ((input_path, output_path), triangle_count) in
        input_paths.iter().zip(output_paths).zip(triangle_counts)
    {
        let output = Path::new(output_path);
        let mut output_pass_file = open_stl_input(input_path)?;
        let output_pass = stl_io::create_stl_reader(&mut output_pass_file)
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        let (output_file, temporary) = create_temporary_output(output)?;
        let mut writer = BufWriter::new(output_file);
        writer.write_all(&[0u8; 80])?;
        writer.write_all(&triangle_count.to_le_bytes())?;
        for triangle in output_pass {
            let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
            validate_triangle(&triangle)?;
            let mut vertices = triangle.vertices.map(|vertex| {
                transformed_vertex(vertex.0, source_center, transformed_min_z, transform)
            });
            if reverses_winding {
                vertices.swap(1, 2);
            }
            if vertices.iter().flatten().any(|value| {
                !value.is_finite()
                    || value.abs() > MAX_STL_COORDINATE_ABS_MM * transform.maximum_scale().max(1.0)
            }) {
                return Err(EngineError::Parse(
                    "STL transform produced an invalid or out-of-range coordinate".to_owned(),
                ));
            }
            for value in triangle_normal(vertices) {
                write_f32(&mut writer, value)?;
            }
            for vertex in vertices {
                for value in vertex {
                    write_f32(&mut writer, value)?;
                }
            }
            writer.write_all(&0u16.to_le_bytes())?;
        }
        writer.flush()?;
        writer.get_ref().sync_all()?;
        drop(writer);
        temporary_outputs.push((temporary, output.to_path_buf()));
    }
    for (mut temporary, output) in temporary_outputs {
        std::fs::rename(&temporary.path, output)?;
        temporary.committed = true;
    }
    Ok(StlTransformFrame {
        source_center_mm: source_center,
        transformed_min_z,
    })
}

fn parse_axis(line: &str, axis: char) -> Option<f32> {
    line.split_ascii_whitespace()
        .find(|token| token.starts_with(axis))
        .and_then(|token| token.get(1..))
        .and_then(|value| value.parse().ok())
        .filter(|value: &f32| value.is_finite())
}

fn positioned_axis(
    command: &str,
    axis: char,
    current: f32,
    origin: f32,
    relative: bool,
    unit_scale: f32,
) -> f32 {
    parse_axis(command, axis)
        .map(|value| value * unit_scale)
        .map(|value| {
            if relative {
                current + value
            } else {
                origin + value
            }
        })
        .filter(|value| value.is_finite() && value.abs() <= MAX_GCODE_COORDINATE_ABS_MM)
        .unwrap_or(current)
}

struct ArcPreview {
    start: [f32; 2],
    end: [f32; 2],
    center: [f32; 2],
    clockwise: bool,
    z: f32,
}

fn push_arc_preview(
    preview_paths: &mut PreviewPathAccumulator,
    layer: usize,
    role: ToolpathRole,
    arc: ArcPreview,
) -> bool {
    let ArcPreview {
        start,
        end,
        center,
        clockwise,
        z,
    } = arc;
    let radius = ((start[0] - center[0]).powi(2) + (start[1] - center[1]).powi(2)).sqrt();
    if !radius.is_finite() || radius <= 0.000_001 {
        return false;
    }
    let start_angle = (start[1] - center[1]).atan2(start[0] - center[0]);
    let end_angle = (end[1] - center[1]).atan2(end[0] - center[0]);
    let same_endpoint =
        (end[0] - start[0]).abs() < 0.000_01 && (end[1] - start[1]).abs() < 0.000_01;
    let full_turn = std::f32::consts::TAU;
    let mut sweep = end_angle - start_angle;
    if same_endpoint {
        sweep = if clockwise { -full_turn } else { full_turn };
    } else if clockwise {
        if sweep >= 0.0 {
            sweep -= full_turn;
        }
    } else if sweep <= 0.0 {
        sweep += full_turn;
    }
    let segment_count = ((radius * sweep.abs() / 0.6).ceil() as usize).clamp(2, 256);
    let mut previous = start;
    for index in 1..=segment_count {
        let next = if index == segment_count {
            end
        } else {
            let angle = start_angle + sweep * index as f32 / segment_count as f32;
            [
                center[0] + radius * angle.cos(),
                center[1] + radius * angle.sin(),
            ]
        };
        preview_paths.push(
            layer,
            role,
            [previous[0], previous[1], next[0], next[1], z, role.code()],
        );
        previous = next;
    }
    true
}

fn preview_gcode(
    path: &str,
    requested_start_layer: usize,
    requested_end_layer: usize,
) -> Result<GcodeLayerPreview, EngineError> {
    let start_layer = requested_start_layer.min(requested_end_layer);
    let end_layer = requested_start_layer.max(requested_end_layer);
    let mut reader = BufReader::new(open_regular_file(path)?);
    let mut current_layer: Option<usize> = None;
    let mut layer_count = 0usize;
    let mut layer_z = 0.0f32;
    let mut min_requested_z: Option<f32> = None;
    let mut max_requested_z: Option<f32> = None;
    let mut x = 0.0f32;
    let mut y = 0.0f32;
    let mut x_origin = 0.0f32;
    let mut y_origin = 0.0f32;
    let mut e = 0.0f32;
    let mut relative_positioning = false;
    let mut relative_extrusion = false;
    let mut unit_scale = 1.0f32;
    let mut toolpath_role = ToolpathRole::Other;
    let mut preview_paths = PreviewPathAccumulator::default();
    let mut line_buffer = Vec::with_capacity(256);

    loop {
        line_buffer.clear();
        let bytes_read = (&mut reader)
            .take((MAX_TEXT_LINE_BYTES + 1) as u64)
            .read_until(b'\n', &mut line_buffer)?;
        if bytes_read == 0 {
            break;
        }
        if line_buffer.len() > MAX_TEXT_LINE_BYTES {
            return Err(EngineError::Parse(
                "G-code line exceeds the supported limit".to_owned(),
            ));
        }
        let line = std::str::from_utf8(&line_buffer)
            .map_err(|_| EngineError::Parse("G-code contains invalid UTF-8".to_owned()))?;
        let trimmed = line.trim();
        if trimmed == ";LAYER_CHANGE" {
            preview_paths.break_path();
            let next = current_layer
                .map(|layer| layer.checked_add(1))
                .unwrap_or(Some(0))
                .ok_or_else(|| EngineError::Parse("G-code has too many layers".to_owned()))?;
            if next >= MAX_PREVIEW_LAYERS {
                return Err(EngineError::Parse("G-code has too many layers".to_owned()));
            }
            current_layer = Some(next);
            layer_count = layer_count.max(
                next.checked_add(1)
                    .ok_or_else(|| EngineError::Parse("G-code has too many layers".to_owned()))?,
            );
            continue;
        }
        if let Some(value) = trimmed.strip_prefix(";Z:") {
            if let Ok(parsed) = value.parse::<f32>()
                && parsed.is_finite()
                && parsed.abs() <= MAX_GCODE_COORDINATE_ABS_MM
            {
                layer_z = parsed;
                if current_layer.is_some_and(|layer| (start_layer..=end_layer).contains(&layer)) {
                    min_requested_z =
                        Some(min_requested_z.map_or(parsed, |value| value.min(parsed)));
                    max_requested_z =
                        Some(max_requested_z.map_or(parsed, |value| value.max(parsed)));
                }
            }
            continue;
        }
        if let Some(value) = trimmed.strip_prefix(";TYPE:") {
            preview_paths.break_path();
            toolpath_role = ToolpathRole::from_label(value);
            continue;
        }

        let command = trimmed.split(';').next().unwrap_or("").trim();
        let opcode = command.split_ascii_whitespace().next().unwrap_or("");
        if opcode == "G20" {
            unit_scale = 25.4;
            continue;
        }
        if opcode == "G21" {
            unit_scale = 1.0;
            continue;
        }
        if opcode == "G90" {
            relative_positioning = false;
            continue;
        }
        if opcode == "G91" {
            relative_positioning = true;
            continue;
        }
        if opcode == "M82" {
            relative_extrusion = false;
            continue;
        }
        if opcode == "M83" {
            relative_extrusion = true;
            continue;
        }
        if opcode == "G92" {
            let has_x = parse_axis(command, 'X').is_some();
            let has_y = parse_axis(command, 'Y').is_some();
            let has_e = parse_axis(command, 'E').is_some();
            if let Some(next_x) = parse_axis(command, 'X') {
                x_origin = x - next_x * unit_scale;
            }
            if let Some(next_y) = parse_axis(command, 'Y') {
                y_origin = y - next_y * unit_scale;
            }
            if let Some(next_e) = parse_axis(command, 'E') {
                e = next_e * unit_scale;
            }
            if !has_x && !has_y && !has_e {
                x_origin = x;
                y_origin = y;
                e = 0.0;
            }
            continue;
        }
        let linear_move = matches!(opcode, "G0" | "G00" | "G1" | "G01");
        let clockwise_arc = matches!(opcode, "G2" | "G02");
        let counter_clockwise_arc = matches!(opcode, "G3" | "G03");
        if !linear_move && !clockwise_arc && !counter_clockwise_arc {
            continue;
        }

        let next_x = positioned_axis(command, 'X', x, x_origin, relative_positioning, unit_scale);
        let next_y = positioned_axis(command, 'Y', y, y_origin, relative_positioning, unit_scale);
        let next_e = parse_axis(command, 'E').map(|value| value * unit_scale);
        let relative_e = relative_positioning || relative_extrusion;
        let extruding =
            next_e.is_some_and(|value| if relative_e { value > 0.0 } else { value > e });

        let moved = next_x != x || next_y != y;
        let arc_center_offsets = if clockwise_arc || counter_clockwise_arc {
            let i = parse_axis(command, 'I');
            let j = parse_axis(command, 'J');
            (i.is_some() || j.is_some())
                .then_some([i.unwrap_or(0.0) * unit_scale, j.unwrap_or(0.0) * unit_scale])
        } else {
            None
        };
        let motion = moved || arc_center_offsets.is_some();
        let requested_layer =
            current_layer.filter(|layer| (start_layer..=end_layer).contains(layer));
        if extruding && motion {
            if let Some(layer) = requested_layer {
                let emitted_arc = arc_center_offsets.is_some_and(|offset| {
                    push_arc_preview(
                        &mut preview_paths,
                        layer,
                        toolpath_role,
                        ArcPreview {
                            start: [x, y],
                            end: [next_x, next_y],
                            center: [x + offset[0], y + offset[1]],
                            clockwise: clockwise_arc,
                            z: layer_z,
                        },
                    )
                });
                if !emitted_arc {
                    preview_paths.push(
                        layer,
                        toolpath_role,
                        [x, y, next_x, next_y, layer_z, toolpath_role.code()],
                    );
                }
            } else {
                preview_paths.break_path();
            }
        } else if motion {
            preview_paths.break_path();
        }

        x = next_x;
        y = next_y;
        if let Some(next_e) = next_e {
            let updated = if relative_e { e + next_e } else { next_e };
            if !updated.is_finite() {
                return Err(EngineError::Parse(
                    "G-code extrusion position is out of range".to_owned(),
                ));
            }
            e = updated;
        }
    }

    let last_layer = layer_count.saturating_sub(1);
    Ok(GcodeLayerPreview {
        start_layer: start_layer.min(last_layer),
        end_layer: end_layer.min(last_layer),
        layer_count,
        min_z_mm: min_requested_z.unwrap_or(0.0),
        max_z_mm: max_requested_z.unwrap_or(0.0),
        segments: preview_paths.finish(),
    })
}

fn preview_payload(preview: GcodeLayerPreview) -> Result<Vec<f32>, EngineError> {
    if preview.layer_count > MAX_PREVIEW_LAYERS || preview.segments.len() > MAX_PREVIEW_SEGMENTS {
        return Err(EngineError::Parse(
            "G-code preview exceeds the supported limit".to_owned(),
        ));
    }
    let payload_floats = preview
        .segments
        .len()
        .checked_mul(6)
        .and_then(|count| count.checked_add(PREVIEW_HEADER_FLOATS))
        .ok_or_else(|| EngineError::Parse("G-code preview size overflow".to_owned()))?;
    let mut payload = Vec::with_capacity(payload_floats);
    payload.extend_from_slice(&[
        PREVIEW_PAYLOAD_MAGIC,
        PREVIEW_PAYLOAD_VERSION,
        preview.start_layer as f32,
        preview.end_layer as f32,
        preview.layer_count as f32,
        preview.min_z_mm,
        preview.max_z_mm,
    ]);
    for segment in preview.segments {
        payload.extend_from_slice(&segment);
    }
    Ok(payload)
}

fn make_java_string(env: &JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

fn guarded_json<T, F>(operation: F) -> String
where
    T: Serialize,
    F: FnOnce() -> Result<T, EngineError>,
{
    catch_unwind(AssertUnwindSafe(|| match operation() {
        Ok(value) => serde_json::to_string(&value),
        Err(error) => {
            let message = error.to_string();
            serde_json::to_string(&ErrorResponse {
                ok: false,
                error: &message,
            })
        }
    }))
    .ok()
    .and_then(Result::ok)
    .unwrap_or_else(|| INTERNAL_ERROR_JSON.to_owned())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_version(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let version = catch_unwind(AssertUnwindSafe(|| {
        let pointer = unsafe { duckyslicer_core_version() };
        if pointer.is_null() {
            "DuckySlicer native bridge unavailable"
        } else {
            unsafe { CStr::from_ptr(pointer) }
                .to_str()
                .unwrap_or("DuckySlicer native bridge")
        }
    }))
    .unwrap_or("DuckySlicer native bridge unavailable");
    make_java_string(&env, version)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_vulkanCapabilities(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let response = catch_unwind(AssertUnwindSafe(|| serde_json::to_string(&probe_vulkan())))
        .ok()
        .and_then(Result::ok)
        .unwrap_or_else(|| INTERNAL_ERROR_JSON.to_owned());
    make_java_string(&env, &response)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_inspectStlPayload(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jfloatArray {
    let payload = catch_unwind(AssertUnwindSafe(|| {
        let path = env
            .get_string(&path)
            .map(|path| path.to_string_lossy().into_owned())
            .map_err(|error| EngineError::Parse(format!("Unable to read file path: {error}")))?;
        model_preview_payload(inspect_stl(&path)?)
    }))
    .ok()
    .and_then(Result::ok);
    let Some(payload) = payload else {
        return std::ptr::null_mut();
    };
    catch_unwind(AssertUnwindSafe(|| {
        let output = env.new_float_array(payload.len() as jint)?;
        env.set_float_array_region(&output, 0, &payload)?;
        Ok::<jfloatArray, jni::errors::Error>(output.into_raw())
    }))
    .ok()
    .and_then(Result::ok)
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_transformStl(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    output_path: JString<'_>,
    transform_json: JString<'_>,
) -> jstring {
    let response = guarded_json(|| {
        let read_string = |env: &mut JNIEnv<'_>, value: &JString<'_>| {
            env.get_string(value)
                .map(|text| text.to_string_lossy().into_owned())
                .map_err(|error| EngineError::Parse(error.to_string()))
        };
        let input_path = read_string(&mut env, &input_path)?;
        let output_path = read_string(&mut env, &output_path)?;
        let transform_json = read_string(&mut env, &transform_json)?;
        let transform: StlTransform = serde_json::from_str(&transform_json)
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        let frame = transform_stl(&input_path, &output_path, &transform)?;
        Ok(StlTransformResponse {
            ok: true,
            source_center_mm: frame.source_center_mm,
            transformed_min_z: frame.transformed_min_z,
        })
    });
    make_java_string(&env, &response)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_transformStlGroup(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_json: JString<'_>,
) -> jstring {
    let response = guarded_json(|| {
        let request_json = env
            .get_string(&request_json)
            .map(|text| text.to_string_lossy().into_owned())
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        let request: StlGroupTransformRequest = serde_json::from_str(&request_json)
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        let frame = transform_stl_group(
            &request.input_paths,
            &request.output_paths,
            &request.transform,
        )?;
        Ok(StlTransformResponse {
            ok: true,
            source_center_mm: frame.source_center_mm,
            transformed_min_z: frame.transformed_min_z,
        })
    });
    make_java_string(&env, &response)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_layOnFace(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_json: JString<'_>,
) -> jstring {
    let response = guarded_json(|| {
        let request_json = env
            .get_string(&request_json)
            .map(|text| text.to_string_lossy().into_owned())
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        let request: LayOnFaceRequest = serde_json::from_str(&request_json)
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        lay_on_face(&request)
    });
    make_java_string(&env, &response)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRange(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    start_layer: jint,
    end_layer: jint,
) -> jfloatArray {
    let payload = catch_unwind(AssertUnwindSafe(|| {
        let path = env
            .get_string(&path)
            .map(|path| path.to_string_lossy().into_owned())
            .map_err(|error| EngineError::Parse(format!("Unable to read file path: {error}")))?;
        preview_payload(preview_gcode(
            &path,
            start_layer.max(0) as usize,
            end_layer.max(0) as usize,
        )?)
    }))
    .ok()
    .and_then(Result::ok);
    let Some(payload) = payload else {
        return std::ptr::null_mut();
    };
    catch_unwind(AssertUnwindSafe(|| {
        let output = env.new_float_array(payload.len() as jint)?;
        env.set_float_array_region(&output, 0, &payload)?;
        Ok::<jfloatArray, jni::errors::Error>(output.into_raw())
    }))
    .ok()
    .and_then(Result::ok)
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_packToolpathGeometry(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    segments: JFloatArray<'_>,
    path_starts: JIntArray<'_>,
    path_ends_exclusive: JIntArray<'_>,
    bed_origin_x: jfloat,
    bed_origin_y: jfloat,
    min_z_mm: jfloat,
    max_z_mm: jfloat,
    opacity: jfloat,
    depth_contrast: jfloat,
    reverse_for_early_z: jboolean,
    render_as_lines: jboolean,
) -> jfloatArray {
    let payload = catch_unwind(AssertUnwindSafe(|| {
        let segment_float_count = usize::try_from(env.get_array_length(&segments)?)
            .map_err(|_| EngineError::Parse("Toolpath segment array is invalid".to_owned()))?;
        let path_count = usize::try_from(env.get_array_length(&path_starts)?)
            .map_err(|_| EngineError::Parse("Toolpath path array is invalid".to_owned()))?;
        let path_end_count = usize::try_from(env.get_array_length(&path_ends_exclusive)?)
            .map_err(|_| EngineError::Parse("Toolpath path array is invalid".to_owned()))?;
        if segment_float_count > MAX_PREVIEW_SEGMENTS * TOOLPATH_SEGMENT_FLOATS
            || path_count > MAX_PREVIEW_SEGMENTS
            || path_end_count != path_count
        {
            return Err(EngineError::Parse(
                "Toolpath JNI payload exceeds its bound".to_owned(),
            ));
        }
        let mut segment_values = vec![0.0f32; segment_float_count];
        let mut path_start_values = vec![0i32; path_count];
        let mut path_end_values = vec![0i32; path_count];
        env.get_float_array_region(&segments, 0, &mut segment_values)?;
        env.get_int_array_region(&path_starts, 0, &mut path_start_values)?;
        env.get_int_array_region(&path_ends_exclusive, 0, &mut path_end_values)?;
        pack_toolpath_geometry(ToolpathPackingRequest {
            segments: &segment_values,
            path_starts: &path_start_values,
            path_ends_exclusive: &path_end_values,
            bed_origin_x,
            bed_origin_y,
            min_z_mm,
            max_z_mm,
            opacity,
            depth_contrast,
            reverse_for_early_z: reverse_for_early_z != 0,
            render_as_lines: render_as_lines != 0,
        })
    }))
    .ok()
    .and_then(Result::ok);
    let Some(payload) = payload else {
        return std::ptr::null_mut();
    };
    catch_unwind(AssertUnwindSafe(|| {
        let output = env.new_float_array(payload.len() as jint)?;
        env.set_float_array_region(&output, 0, &payload)?;
        Ok::<jfloatArray, jni::errors::Error>(output.into_raw())
    }))
    .ok()
    .and_then(Result::ok)
    .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[derive(Serialize)]
    struct SuccessResponse {
        ok: bool,
    }

    fn deterministic_mutation(seed: &[u8], case: usize) -> Vec<u8> {
        let mut state = (case as u64 + 1).wrapping_mul(0x9E37_79B9_7F4A_7C15);
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        let mut mutated = seed.to_vec();
        match case % 5 {
            0 => mutated.truncate((next() as usize) % (mutated.len() + 1)),
            1 => {
                if !mutated.is_empty() {
                    let index = (next() as usize) % mutated.len();
                    mutated[index] ^= (next() as u8) | 1;
                }
            }
            2 => {
                let index = (next() as usize) % (mutated.len() + 1);
                let count = (next() as usize % 16) + 1;
                let inserted = (0..count).map(|_| next() as u8);
                mutated.splice(index..index, inserted);
            }
            3 => {
                if !mutated.is_empty() {
                    let start = (next() as usize) % mutated.len();
                    let count = ((next() as usize % 16) + 1).min(mutated.len() - start);
                    for byte in &mut mutated[start..start + count] {
                        *byte = next() as u8;
                    }
                }
            }
            4 => {
                let count = (next() as usize % 32) + 1;
                mutated.extend((0..count).map(|_| next() as u8));
            }
            _ => unreachable!(),
        }
        mutated
    }

    fn binary_triangle_stl() -> Vec<u8> {
        let mut bytes = vec![0u8; 80];
        bytes.extend_from_slice(&1u32.to_le_bytes());
        for value in [
            0.0f32, 0.0, 1.0, // normal
            0.0, 0.0, 0.0, // vertex 1
            1.0, 0.0, 0.0, // vertex 2
            0.0, 1.0, 0.0, // vertex 3
        ] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        bytes.extend_from_slice(&0u16.to_le_bytes());
        bytes
    }

    #[test]
    fn model_preview_payload_is_versioned_bounded_and_contiguous() {
        let payload = model_preview_payload(StlInspection {
            triangles: 5,
            min_mm: [-10.0, -20.0, -30.0],
            max_mm: [10.0, 10.0, 10.0],
            preview_triangles: vec![
                [-10.0, -20.0, -30.0, 10.0, -20.0, -30.0, 10.0, 10.0, -30.0],
                [-10.0, -20.0, 10.0, 10.0, 10.0, 10.0, -10.0, 10.0, 10.0],
            ],
            preview_triangle_indices: vec![0, 4],
        })
        .expect("encode model preview payload");

        assert_eq!(payload.len(), MODEL_PREVIEW_HEADER_FLOATS + 20);
        assert_eq!(payload[0], MODEL_PREVIEW_PAYLOAD_MAGIC);
        assert_eq!(payload[1], MODEL_PREVIEW_PAYLOAD_VERSION);
        assert_eq!(payload[2], 5.0);
        assert_eq!(&payload[3..9], &[-10.0, -20.0, -30.0, 10.0, 10.0, 10.0]);
        assert_eq!(payload[9], 2.0);
        assert_eq!(&payload[payload.len() - 2..], &[0.0, 4.0]);
    }

    #[test]
    fn model_preview_payload_rejects_inconsistent_source_mapping() {
        let result = model_preview_payload(StlInspection {
            triangles: 1,
            min_mm: [0.0, 0.0, 0.0],
            max_mm: [1.0, 1.0, 0.0],
            preview_triangles: vec![[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0]],
            preview_triangle_indices: Vec::new(),
        });

        assert!(matches!(result, Err(EngineError::Parse(_))));
    }

    #[test]
    fn host_vulkan_probe_never_enables_android_acceleration() {
        let capabilities = probe_vulkan();

        assert!(!capabilities.auto_enabled);
        assert!(!capabilities.auto_candidate);
        assert_eq!(capabilities.reason, "not_android");
    }

    #[test]
    fn missing_stl_is_reported_without_panicking() {
        let result = inspect_stl("/definitely/missing/duckyslicer.stl");
        assert!(matches!(result, Err(EngineError::Open(_))));
    }

    #[test]
    fn json_boundary_converts_unexpected_panics_to_a_safe_error() {
        let response = guarded_json::<SuccessResponse, _>(|| panic!("simulated parser defect"));
        assert_eq!(response, INTERNAL_ERROR_JSON);
    }

    #[test]
    fn toolpath_packing_preserves_ranges_layout_and_reverse_order() {
        let segments = [
            10.0, 20.0, 30.0, 20.0, 0.2, 0.0, // outer wall
            30.0, 20.0, 30.0, 40.0, 0.4, 1.0, // inner wall
        ];
        let path_starts = [0, 1];
        let path_ends = [1, 2];
        let pack = |reverse_for_early_z, render_as_lines| {
            pack_toolpath_geometry(ToolpathPackingRequest {
                segments: &segments,
                path_starts: &path_starts,
                path_ends_exclusive: &path_ends,
                bed_origin_x: 10.0,
                bed_origin_y: 20.0,
                min_z_mm: 0.2,
                max_z_mm: 0.4,
                opacity: 0.92,
                depth_contrast: 0.0,
                reverse_for_early_z,
                render_as_lines,
            })
            .expect("pack bounded toolpaths")
        };

        let instances = pack(false, false);
        assert_eq!(instances.len(), 2 * PACKED_TOOLPATH_FLOATS);
        assert_eq!(&instances[..7], &[0.0, 0.0, 0.224, 20.0, 0.0, 0.224, 0.26]);
        assert_eq!(
            instances[7].to_bits(),
            packed_toolpath_color(1.0, 0.812, 0.251, 0.92).to_bits(),
        );

        let lines = pack(true, true);
        assert_eq!(&lines[..3], &[20.0, 0.0, 0.424]);
        assert_eq!(&lines[4..7], &[20.0, 20.0, 0.424]);
        assert_eq!(lines[3].to_bits(), lines[7].to_bits());
        assert_eq!(&lines[8..11], &[0.0, 0.0, 0.224]);
    }

    #[test]
    fn toolpath_packing_rejects_overlapping_or_out_of_bounds_ranges() {
        let segments = [0.0, 0.0, 1.0, 0.0, 0.2, 0.0];
        for (starts, ends) in [(vec![0, 0], vec![1, 1]), (vec![0], vec![2])] {
            let result = pack_toolpath_geometry(ToolpathPackingRequest {
                segments: &segments,
                path_starts: &starts,
                path_ends_exclusive: &ends,
                bed_origin_x: 0.0,
                bed_origin_y: 0.0,
                min_z_mm: 0.2,
                max_z_mm: 0.2,
                opacity: 1.0,
                depth_contrast: 0.0,
                reverse_for_early_z: false,
                render_as_lines: false,
            });
            assert!(matches!(result, Err(EngineError::Parse(_))));
        }
    }

    #[test]
    fn toolpath_packing_rejects_invalid_visual_parameters() {
        let segments = [0.0, 0.0, 1.0, 0.0, 0.2, 0.0];
        for (opacity, depth_contrast) in [(f32::NAN, 0.5), (1.1, 0.5), (0.5, -0.1)] {
            let result = pack_toolpath_geometry(ToolpathPackingRequest {
                segments: &segments,
                path_starts: &[0],
                path_ends_exclusive: &[1],
                bed_origin_x: 0.0,
                bed_origin_y: 0.0,
                min_z_mm: 0.2,
                max_z_mm: 0.2,
                opacity,
                depth_contrast,
                reverse_for_early_z: false,
                render_as_lines: false,
            });
            assert!(matches!(result, Err(EngineError::Parse(_))));
        }
    }

    #[test]
    fn stl_inspection_streams_large_meshes_into_a_bounded_preview() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-large-inspection-{}.stl",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create large STL fixture");
        writeln!(file, "solid large").expect("write header");
        for triangle in 0..8_001 {
            let x = triangle as f32 * 0.01;
            writeln!(
                file,
                "facet normal 0 0 1\nouter loop\nvertex {x} 0 0\nvertex {x} 1 0\nvertex {x} 0 1\nendloop\nendfacet"
            )
            .expect("write triangle");
        }
        writeln!(file, "endsolid large").expect("write footer");
        drop(file);

        let inspection = inspect_stl(path.to_str().expect("utf8 path")).expect("inspect STL");
        std::fs::remove_file(path).expect("remove fixture");

        assert_eq!(inspection.triangles, 8_001);
        assert!(!inspection.preview_triangles.is_empty());
        assert!(inspection.preview_triangles.len() <= PREVIEW_TRIANGLE_LIMIT);
        assert_eq!(
            inspection.preview_triangles.len(),
            inspection.preview_triangle_indices.len()
        );
        assert!(
            inspection
                .preview_triangle_indices
                .windows(2)
                .all(|indices| indices[0] < indices[1])
        );
        assert!(inspection.max_mm[0] - inspection.min_mm[0] > 79.0);
    }

    #[test]
    fn dense_stl_preview_keeps_a_connected_surface_instead_of_scattered_facets() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-connected-inspection-{}.stl",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create dense STL fixture");
        writeln!(file, "solid grid").expect("write header");
        for y in 0..80 {
            for x in 0..80 {
                let x0 = x as f32;
                let x1 = x0 + 1.0;
                let y0 = y as f32;
                let y1 = y0 + 1.0;
                writeln!(
                    file,
                    "facet normal 0 0 1\nouter loop\nvertex {x0} {y0} 0\nvertex {x1} {y0} 0\nvertex {x1} {y1} 0\nendloop\nendfacet\nfacet normal 0 0 1\nouter loop\nvertex {x0} {y0} 0\nvertex {x1} {y1} 0\nvertex {x0} {y1} 0\nendloop\nendfacet",
                )
                .expect("write grid triangles");
            }
        }
        for line in 0..512 {
            let z = 20.0 + line as f32 * 0.01;
            writeln!(
                file,
                "facet normal 0 0 0\nouter loop\nvertex -100 -100 {z}\nvertex 0 0 {z}\nvertex 100 100 {z}\nendloop\nendfacet",
            )
            .expect("write degenerate line facet");
        }
        writeln!(file, "endsolid grid").expect("write footer");
        drop(file);

        let inspection = inspect_stl(path.to_str().expect("utf8 path")).expect("inspect STL");
        std::fs::remove_file(path).expect("remove fixture");

        assert_eq!(inspection.triangles, 80 * 80 * 2 + 512);
        assert_eq!(
            [
                inspection.max_mm[0] - inspection.min_mm[0],
                inspection.max_mm[1] - inspection.min_mm[1],
                inspection.max_mm[2] - inspection.min_mm[2],
            ],
            [80.0, 80.0, 0.0],
        );
        assert!(!inspection.preview_triangles.is_empty());
        assert!(inspection.preview_triangles.len() <= PREVIEW_TRIANGLE_LIMIT);
        let vertex = |values: &[f32; 9], offset: usize| {
            (
                values[offset].to_bits(),
                values[offset + 1].to_bits(),
                values[offset + 2].to_bits(),
            )
        };
        let mut edges = HashMap::new();
        for triangle in &inspection.preview_triangles {
            let vertices = [
                vertex(triangle, 0),
                vertex(triangle, 3),
                vertex(triangle, 6),
            ];
            for edge in [(0, 1), (1, 2), (2, 0)] {
                let mut endpoints = [vertices[edge.0], vertices[edge.1]];
                endpoints.sort_unstable();
                *edges.entry(endpoints).or_insert(0usize) += 1;
            }
        }
        let shared_edges = edges.values().filter(|count| **count >= 2).count();
        assert!(
            shared_edges > inspection.preview_triangles.len() / 2,
            "clustered preview should retain a visibly connected surface",
        );
        let preview_x = inspection
            .preview_triangles
            .iter()
            .flat_map(|triangle| [triangle[0], triangle[3], triangle[6]]);
        let preview_y = inspection
            .preview_triangles
            .iter()
            .flat_map(|triangle| [triangle[1], triangle[4], triangle[7]]);
        assert!(preview_x.clone().fold(f32::NEG_INFINITY, f32::max) > 78.0);
        assert!(preview_y.clone().fold(f32::NEG_INFINITY, f32::max) > 78.0);
        assert!(preview_x.fold(f32::INFINITY, f32::min) >= 0.0);
        assert!(preview_y.fold(f32::INFINITY, f32::min) >= 0.0);
        assert!(
            inspection
                .preview_triangles
                .iter()
                .flat_map(|triangle| [triangle[2], triangle[5], triangle[8]])
                .all(|z| z == 0.0)
        );
    }

    #[test]
    fn stl_inspection_rejects_non_finite_coordinates() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-invalid-inspection-{}.stl",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create invalid STL fixture");
        writeln!(file, "solid invalid\nfacet normal 0 0 1\nouter loop\nvertex NaN 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid invalid")
            .expect("write fixture");
        drop(file);

        let result = inspect_stl(path.to_str().expect("utf8 path"));
        std::fs::remove_file(path).expect("remove fixture");

        assert!(matches!(result, Err(EngineError::Parse(_))));
    }

    #[test]
    fn stl_inspection_rejects_extreme_finite_coordinates() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-extreme-inspection-{}.stl",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create extreme STL fixture");
        writeln!(file, "solid extreme\nfacet normal 0 0 1\nouter loop\nvertex 3e38 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid extreme")
            .expect("write fixture");
        drop(file);

        let result = inspect_stl(path.to_str().expect("utf8 path"));
        std::fs::remove_file(path).expect("remove fixture");

        assert!(matches!(result, Err(EngineError::Parse(_))));
    }

    #[test]
    fn ascii_stl_rejects_an_oversized_single_line() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-long-line-inspection-{}.stl",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create long-line STL fixture");
        file.write_all(b"solid ").expect("write STL prefix");
        file.write_all(&vec![b'x'; MAX_TEXT_LINE_BYTES + 1])
            .expect("write oversized STL line");
        drop(file);

        let result = inspect_stl(path.to_str().expect("utf8 path"));
        std::fs::remove_file(path).expect("remove fixture");

        assert!(matches!(result, Err(EngineError::Parse(_))));
    }

    #[test]
    fn mutated_stl_corpus_never_panics_or_returns_unbounded_geometry() {
        const CASES_PER_SEED: usize = 192;
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-mutated-stl-{}.stl",
            std::process::id(),
        ));
        let ascii = b"solid seed\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid seed\n".to_vec();
        let seeds = [ascii, binary_triangle_stl()];

        for (seed_index, seed) in seeds.iter().enumerate() {
            for case in 0..CASES_PER_SEED {
                std::fs::write(&path, deterministic_mutation(seed, case))
                    .expect("write mutated STL");
                let outcome = catch_unwind(AssertUnwindSafe(|| {
                    inspect_stl(path.to_str().expect("utf8 mutation path"))
                }));
                let parsed = outcome.unwrap_or_else(|_| {
                    panic!("STL parser panicked for seed {seed_index}, mutation {case}")
                });
                if let Ok(inspection) = parsed {
                    assert!(inspection.preview_triangles.len() <= PREVIEW_TRIANGLE_LIMIT);
                    assert_eq!(
                        inspection.preview_triangles.len(),
                        inspection.preview_triangle_indices.len(),
                    );
                    assert!(
                        inspection
                            .min_mm
                            .iter()
                            .chain(inspection.max_mm.iter())
                            .all(|value| value.is_finite())
                    );
                    assert!(inspection.preview_triangles.iter().flatten().all(|value| {
                        value.is_finite() && value.abs() <= MAX_STL_COORDINATE_ABS_MM
                    }));
                }
            }
        }
        std::fs::remove_file(path).expect("remove mutated STL fixture");
    }

    #[test]
    fn toolpath_roles_cover_surface_support_and_adhesion_labels() {
        assert_eq!(
            ToolpathRole::from_label("Outer wall"),
            ToolpathRole::OuterWall
        );
        assert_eq!(
            ToolpathRole::from_label("Bottom surface"),
            ToolpathRole::BottomSurface
        );
        assert_eq!(
            ToolpathRole::from_label("Internal solid infill"),
            ToolpathRole::InternalSolid
        );
        assert_ne!(
            ToolpathRole::from_label("Top surface"),
            ToolpathRole::from_label("Internal solid infill")
        );
        assert_ne!(
            ToolpathRole::from_label("Top surface"),
            ToolpathRole::from_label("Bottom surface")
        );
        assert_eq!(
            ToolpathRole::from_label("Support interface"),
            ToolpathRole::Support
        );
        assert_eq!(
            ToolpathRole::from_label("Overhang wall"),
            ToolpathRole::Bridge
        );
        assert_eq!(ToolpathRole::from_label("Raft"), ToolpathRole::Adhesion);
        assert_ne!(
            ToolpathRole::from_label("Outer wall"),
            ToolpathRole::from_label("Inner wall")
        );
    }

    #[test]
    fn gcode_preview_keeps_extrusion_paths_and_z_for_requested_range() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-preview-{}-{}.gcode",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let mut file = File::create(&path).expect("create fixture");
        writeln!(file, "M83\n;LAYER_CHANGE\n;Z:0.2\n;TYPE:Outer wall\nG1 X10 Y10\nG1 X20 Y10 E1\n;TYPE:Bottom surface\nG1 X20 Y20 E1\n;LAYER_CHANGE\n;Z:0.4\n;TYPE:Internal solid infill\nG1 X10 Y20 E1\n;TYPE:Top surface\nG1 X10 Y10 E1")
            .expect("write fixture");
        drop(file);

        let path = path.to_str().expect("utf8 path");
        let preview = preview_gcode(path, 0, 1).expect("parse gcode");
        let clamped = preview_gcode(path, 99, 120).expect("clamp layer range");
        std::fs::remove_file(path).expect("remove fixture");

        assert_eq!(preview.layer_count, 2);
        assert_eq!(preview.start_layer, 0);
        assert_eq!(preview.end_layer, 1);
        assert_eq!(preview.min_z_mm, 0.2);
        assert_eq!(preview.max_z_mm, 0.4);
        assert_eq!(
            preview.segments,
            vec![
                [10.0, 10.0, 20.0, 10.0, 0.2, 0.0],
                [20.0, 10.0, 20.0, 20.0, 0.2, 9.0],
                [20.0, 20.0, 10.0, 20.0, 0.4, 4.0],
                [10.0, 20.0, 10.0, 10.0, 0.4, 3.0],
            ]
        );
        assert_eq!(clamped.start_layer, 1);
        assert_eq!(clamped.end_layer, 1);

        let payload = preview_payload(preview).expect("encode binary preview payload");
        assert_eq!(payload.len(), PREVIEW_HEADER_FLOATS + 4 * 6);
        assert_eq!(payload[0], PREVIEW_PAYLOAD_MAGIC);
        assert_eq!(payload[1], PREVIEW_PAYLOAD_VERSION);
        assert_eq!(&payload[2..7], &[0.0, 1.0, 2.0, 0.2, 0.4]);
        assert_eq!(payload[7 + 5], ToolpathRole::OuterWall.code());
        assert_eq!(payload[7 + 6 + 5], ToolpathRole::BottomSurface.code());
    }

    #[test]
    fn gcode_preview_follows_standard_xyz_modes_and_g92_origins() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-positioning-preview-{}.gcode",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create positioning fixture");
        writeln!(
            file,
            "G90\nM83\n;LAYER_CHANGE\n;Z:0.2\n;TYPE:Outer wall\nG1 X100 Y100\nG91\nG1 X10 Y0 E1\nG90\nG92 X0 Y0\nG1 X20 Y10 E1",
        )
        .expect("write positioning fixture");
        drop(file);

        let preview = preview_gcode(path.to_str().expect("utf8 path"), 0, 0)
            .expect("parse positioning modes");
        std::fs::remove_file(path).expect("remove fixture");

        assert_eq!(
            preview.segments,
            vec![
                [100.0, 100.0, 110.0, 100.0, 0.2, 0.0],
                [110.0, 100.0, 130.0, 110.0, 0.2, 0.0],
            ],
        );
    }

    #[test]
    fn gcode_preview_tessellates_arcs_and_keeps_the_following_path_connected() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-arc-preview-{}.gcode",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create arc fixture");
        writeln!(
            file,
            "G90\nM83\n;LAYER_CHANGE\n;Z:0.2\n;TYPE:Outer wall\nG1 X0 Y0\nG3 X10 Y0 I5 E1\nG1 X20 Y0 E1",
        )
        .expect("write arc fixture");
        drop(file);

        let preview =
            preview_gcode(path.to_str().expect("utf8 path"), 0, 0).expect("parse arc fixture");
        std::fs::remove_file(path).expect("remove fixture");

        assert!(
            preview.segments.len() > 2,
            "the arc must not collapse to one chord"
        );
        assert_eq!(preview.segments.first().expect("first arc")[0], 0.0);
        assert_eq!(preview.segments.last().expect("following line")[0], 10.0);
        assert_eq!(preview.segments.last().expect("following line")[2], 20.0);
        assert!(preview.segments.windows(2).all(|pair| {
            (pair[0][2] - pair[1][0]).abs() < 0.000_1 && (pair[0][3] - pair[1][1]).abs() < 0.000_1
        }));
    }

    #[test]
    fn oversized_gcode_preview_simplifies_a_path_without_turning_it_into_particles() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-continuous-preview-{}.gcode",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create continuous G-code fixture");
        writeln!(
            file,
            "M83\n;LAYER_CHANGE\n;Z:0.2\n;TYPE:Outer wall\nG1 X0 Y0"
        )
        .expect("write preview header");
        for x in 1..=(MAX_PREVIEW_SEGMENTS + 1) {
            writeln!(file, "G1 X{x} Y0 E1").expect("write continuous extrusion");
        }
        drop(file);

        let preview = preview_gcode(path.to_str().expect("utf8 path"), 0, 0)
            .expect("parse oversized preview");
        std::fs::remove_file(path).expect("remove fixture");

        assert_eq!(preview.segments.len(), MAX_PREVIEW_SEGMENTS);
        assert_eq!(preview.segments.first().expect("first segment")[0], 0.0);
        assert_eq!(
            preview.segments.last().expect("last segment")[2],
            (MAX_PREVIEW_SEGMENTS + 1) as f32,
        );
        assert!(
            preview
                .segments
                .windows(2)
                .all(|pair| pair[0][2] == pair[1][0] && pair[0][3] == pair[1][1])
        );
    }

    #[test]
    fn path_compaction_prioritizes_connected_outer_contours_over_sparse_infill() {
        let paths = (0..8)
            .map(|path_index| PreviewPath {
                order: path_index,
                layer: path_index,
                role: if path_index % 2 == 0 {
                    ToolpathRole::OuterWall
                } else {
                    ToolpathRole::Infill
                },
                segments: (0..4)
                    .map(|segment_index| {
                        let x = (path_index * 10 + segment_index) as f32;
                        [
                            x,
                            path_index as f32,
                            x + 1.0,
                            path_index as f32,
                            path_index as f32,
                            (path_index % 2 * 2) as f32,
                        ]
                    })
                    .collect(),
            })
            .collect();

        let compacted = compact_preview_paths(paths, 12);

        assert!(
            compacted
                .iter()
                .map(|path| path.segments.len())
                .sum::<usize>()
                <= 12
        );
        assert!(
            compacted
                .iter()
                .all(|path| path.role == ToolpathRole::OuterWall)
        );
        assert!(compacted.iter().all(|path| path.segments.len() == 3));
        assert!(compacted.iter().all(|path| {
            path.segments
                .windows(2)
                .all(|pair| pair[0][2] == pair[1][0] && pair[0][3] == pair[1][1])
        }));
        assert!(compacted.iter().any(|path| path.layer == 0));
        assert!(compacted.iter().any(|path| path.layer == 6));
    }

    #[test]
    fn dense_outer_wall_compaction_keeps_every_layer_visible() {
        let paths = (0..30)
            .flat_map(|layer| {
                [ToolpathRole::OuterWall, ToolpathRole::Infill].map(|role| PreviewPath {
                    order: layer * 2 + role as usize,
                    layer,
                    role,
                    segments: (0..20)
                        .map(|segment| {
                            let x = segment as f32;
                            [
                                x,
                                layer as f32,
                                x + 1.0,
                                layer as f32,
                                layer as f32,
                                role.code(),
                            ]
                        })
                        .collect(),
                })
            })
            .collect();

        let compacted = compact_preview_paths(paths, 120);
        let outer_layers: HashSet<_> = compacted
            .iter()
            .filter(|path| path.role == ToolpathRole::OuterWall)
            .map(|path| path.layer)
            .collect();

        assert_eq!(outer_layers, (0..30).collect());
        assert!(
            compacted
                .iter()
                .map(|path| path.segments.len())
                .sum::<usize>()
                <= 120
        );
        assert!(compacted.iter().all(|path| {
            path.segments
                .windows(2)
                .all(|pair| pair[0][2] == pair[1][0] && pair[0][3] == pair[1][1])
        }));
    }

    #[test]
    fn gcode_preview_rejects_an_oversized_single_line() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-long-line-preview-{}.gcode",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create long-line G-code fixture");
        file.write_all(&vec![b';'; MAX_TEXT_LINE_BYTES + 1])
            .expect("write oversized G-code line");
        drop(file);

        let result = preview_gcode(path.to_str().expect("utf8 path"), 0, 1);
        std::fs::remove_file(path).expect("remove fixture");

        assert!(
            matches!(result, Err(EngineError::Parse(message)) if message.contains("line exceeds"))
        );
    }

    #[test]
    fn gcode_preview_ignores_non_finite_motion_coordinates() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-non-finite-preview-{}.gcode",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create non-finite G-code fixture");
        writeln!(
            file,
            "M83\n;LAYER_CHANGE\n;Z:NaN\nG1 XNaN Yinf E1\nG1 X10 Y10 E1"
        )
        .expect("write fixture");
        drop(file);

        let preview = preview_gcode(path.to_str().expect("utf8 path"), 0, 0)
            .expect("parse bounded coordinates");
        std::fs::remove_file(path).expect("remove fixture");

        assert_eq!(preview.min_z_mm, 0.0);
        assert_eq!(preview.max_z_mm, 0.0);
        assert_eq!(preview.segments, vec![[0.0, 0.0, 10.0, 10.0, 0.0, 8.0]]);
    }

    #[test]
    fn gcode_preview_rejects_relative_extrusion_overflow() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-extrusion-overflow-{}.gcode",
            std::process::id(),
        ));
        let mut file = File::create(&path).expect("create overflow G-code fixture");
        writeln!(file, "M83\n;LAYER_CHANGE\nG1 X1 E3e38\nG1 X2 E3e38").expect("write fixture");
        drop(file);

        let result = preview_gcode(path.to_str().expect("utf8 path"), 0, 0);
        std::fs::remove_file(path).expect("remove fixture");

        assert!(
            matches!(result, Err(EngineError::Parse(message)) if message.contains("out of range"))
        );
    }

    #[test]
    fn mutated_gcode_corpus_never_panics_or_exceeds_preview_limits() {
        const CASES: usize = 384;
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-mutated-preview-{}.gcode",
            std::process::id(),
        ));
        let seed = b"G90\nM83\n;LAYER_CHANGE\n;Z:0.2\n;TYPE:Outer wall\nG1 X0 Y0\nG1 X20 Y0 E1\nG2 X20 Y20 I0 J10 E1\nG1 X0 Y20 E1\n";

        for case in 0..CASES {
            std::fs::write(&path, deterministic_mutation(seed, case))
                .expect("write mutated G-code");
            let outcome = catch_unwind(AssertUnwindSafe(|| {
                preview_gcode(path.to_str().expect("utf8 mutation path"), 0, usize::MAX)
            }));
            let parsed =
                outcome.unwrap_or_else(|_| panic!("G-code parser panicked for mutation {case}"));
            if let Ok(preview) = parsed {
                assert!(preview.layer_count <= MAX_PREVIEW_LAYERS);
                assert!(preview.segments.len() <= MAX_PREVIEW_SEGMENTS);
                assert!(preview.min_z_mm.is_finite() && preview.max_z_mm.is_finite());
                assert!(preview.min_z_mm <= preview.max_z_mm);
                assert!(
                    preview
                        .segments
                        .iter()
                        .flatten()
                        .all(|value| value.is_finite())
                );
            }
        }
        std::fs::remove_file(path).expect("remove mutated G-code fixture");
    }

    #[test]
    fn stl_transform_centers_rotates_scales_and_places_on_bed() {
        let input_path = std::env::temp_dir().join(format!(
            "duckyslicer-transform-input-{}.stl",
            std::process::id(),
        ));
        let output_path = std::env::temp_dir().join(format!(
            "duckyslicer-transform-output-{}.stl",
            std::process::id(),
        ));
        let mut file = File::create(&input_path).expect("create fixture");
        writeln!(
            file,
            "solid model\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 2 0 0\nvertex 2 4 0\nendloop\nendfacet\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 2 4 0\nvertex 0 4 0\nendloop\nendfacet\nendsolid model",
        )
        .expect("write fixture");
        drop(file);

        let transform = StlTransform {
            bed_center_mm: [100.0, 100.0],
            offset_mm: [5.0, -3.0],
            offset_z_mm: 7.0,
            rotation_deg: [0.0, 0.0, 90.0],
            scale: 2.0,
            scale_axes: None,
            mirror: [false; 3],
        };
        transform_stl(
            input_path.to_str().expect("utf8 path"),
            output_path.to_str().expect("utf8 path"),
            &transform,
        )
        .expect("transform stl");
        let inspection = inspect_stl(output_path.to_str().expect("utf8 path")).expect("inspect");

        std::fs::remove_file(input_path).expect("remove fixture");
        std::fs::remove_file(output_path).expect("remove output");
        assert!((inspection.max_mm[0] - inspection.min_mm[0] - 8.0).abs() < 0.001);
        assert!((inspection.max_mm[1] - inspection.min_mm[1] - 4.0).abs() < 0.001);
        assert!((inspection.min_mm[0] - 101.0).abs() < 0.001);
        assert!((inspection.max_mm[1] - 99.0).abs() < 0.001);
        assert_eq!(inspection.min_mm[2], 7.0);
    }

    #[test]
    fn stl_group_transform_preserves_volume_separation_under_one_object_center() {
        let root = std::env::temp_dir();
        let process = std::process::id();
        let left_input = root.join(format!("duckyslicer-group-left-input-{process}.stl"));
        let right_input = root.join(format!("duckyslicer-group-right-input-{process}.stl"));
        let left_output = root.join(format!("duckyslicer-group-left-output-{process}.stl"));
        let right_output = root.join(format!("duckyslicer-group-right-output-{process}.stl"));
        let write_triangle = |path: &Path, offset_x: f32| {
            let mut file = File::create(path).expect("create group fixture");
            writeln!(
                file,
                "solid model\nfacet normal 0 0 1\nouter loop\nvertex {offset_x} 0 0\nvertex {} 0 0\nvertex {offset_x} 1 0\nendloop\nendfacet\nendsolid model",
                offset_x + 1.0,
            )
            .expect("write group fixture");
        };
        write_triangle(&left_input, 0.0);
        write_triangle(&right_input, 10.0);
        let inputs = vec![
            left_input.to_string_lossy().into_owned(),
            right_input.to_string_lossy().into_owned(),
        ];
        let outputs = vec![
            left_output.to_string_lossy().into_owned(),
            right_output.to_string_lossy().into_owned(),
        ];

        transform_stl_group(
            &inputs,
            &outputs,
            &StlTransform {
                bed_center_mm: [50.0, 50.0],
                offset_mm: [0.0, 0.0],
                offset_z_mm: 0.0,
                rotation_deg: [0.0; 3],
                scale: 1.0,
                scale_axes: None,
                mirror: [false; 3],
            },
        )
        .expect("transform volume group");
        let left = inspect_stl(&outputs[0]).expect("inspect left volume");
        let right = inspect_stl(&outputs[1]).expect("inspect right volume");

        for path in [&left_input, &right_input, &left_output, &right_output] {
            std::fs::remove_file(path).expect("remove group fixture");
        }
        assert!((left.min_mm[0] - 44.5).abs() < 0.001);
        assert!((right.min_mm[0] - 54.5).abs() < 0.001);
        assert!((right.min_mm[0] - left.min_mm[0] - 10.0).abs() < 0.001);
        assert_eq!(left.min_mm[2], 0.0);
        assert_eq!(right.min_mm[2], 0.0);
    }

    #[test]
    fn stl_transform_mirrors_axes_before_rotation() {
        let transform = StlTransform {
            bed_center_mm: [0.0, 0.0],
            offset_mm: [0.0, 0.0],
            offset_z_mm: 0.0,
            rotation_deg: [0.0; 3],
            scale: 2.0,
            scale_axes: None,
            mirror: [true, false, true],
        };

        assert_eq!(
            transformed_vertex([2.0, 4.0, 6.0], [1.0, 2.0, 3.0], -6.0, &transform),
            [-2.0, 4.0, 0.0],
        );
    }

    #[test]
    fn stl_transform_applies_independent_axis_scales() {
        let transform = StlTransform {
            bed_center_mm: [0.0, 0.0],
            offset_mm: [0.0, 0.0],
            offset_z_mm: 0.0,
            rotation_deg: [0.0; 3],
            scale: 2.0,
            scale_axes: Some([2.0, 3.0, 4.0]),
            mirror: [false; 3],
        };

        assert_eq!(
            local_vertex([2.0, 4.0, 6.0], [1.0, 2.0, 3.0], &transform),
            [2.0, 6.0, 12.0],
        );
    }

    #[test]
    fn lay_on_face_aligns_the_selected_transformed_normal_with_world_down() {
        let transform = StlTransform {
            bed_center_mm: [0.0, 0.0],
            offset_mm: [0.0, 0.0],
            offset_z_mm: 0.0,
            rotation_deg: [0.0; 3],
            scale: 1.0,
            scale_axes: Some([1.0, 1.5, 2.0]),
            mirror: [false; 3],
        };
        let request = LayOnFaceRequest {
            transform,
            triangle: [1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 1.0, 0.0, 1.0],
        };

        let result = lay_on_face(&request).expect("face should align");
        let normal = triangle_normal(transformed_face_vertices(
            request.triangle,
            &request.transform,
            result.rotation_deg,
        ));

        assert!(result.ok);
        assert!(normal[0].abs() < 0.001, "normal={normal:?}");
        assert!(normal[1].abs() < 0.001, "normal={normal:?}");
        assert!((normal[2] + 1.0).abs() < 0.001, "normal={normal:?}");
    }

    #[test]
    fn lay_on_face_handles_existing_rotation_non_uniform_scale_and_mirroring() {
        let transform = StlTransform {
            bed_center_mm: [0.0, 0.0],
            offset_mm: [0.0, 0.0],
            offset_z_mm: 0.0,
            rotation_deg: [32.0, -21.0, 47.0],
            scale: 1.4,
            scale_axes: Some([1.4, 0.7, 2.2]),
            mirror: [true, false, false],
        };
        let request = LayOnFaceRequest {
            transform,
            triangle: [0.0, 0.0, 1.0, 1.0, 0.0, 1.0, 0.0, 1.0, 1.0],
        };

        let result = lay_on_face(&request).expect("mirrored face should align");
        let normal = triangle_normal(transformed_face_vertices(
            request.triangle,
            &request.transform,
            result.rotation_deg,
        ));

        assert!(normal[0].abs() < 0.002, "normal={normal:?}");
        assert!(normal[1].abs() < 0.002, "normal={normal:?}");
        assert!((normal[2] + 1.0).abs() < 0.002, "normal={normal:?}");
    }

    #[test]
    fn lay_on_face_rejects_a_degenerate_triangle() {
        let request = LayOnFaceRequest {
            transform: StlTransform {
                bed_center_mm: [0.0, 0.0],
                offset_mm: [0.0, 0.0],
                offset_z_mm: 0.0,
                rotation_deg: [0.0; 3],
                scale: 1.0,
                scale_axes: None,
                mirror: [false; 3],
            },
            triangle: [0.0; 9],
        };

        assert!(lay_on_face(&request).is_err());
    }

    #[test]
    fn stl_transform_rejects_non_finite_coordinates() {
        let input_path = std::env::temp_dir().join(format!(
            "duckyslicer-invalid-transform-input-{}.stl",
            std::process::id(),
        ));
        let output_path = std::env::temp_dir().join(format!(
            "duckyslicer-invalid-transform-output-{}.stl",
            std::process::id(),
        ));
        let mut file = File::create(&input_path).expect("create fixture");
        writeln!(file, "solid invalid\nfacet normal 0 0 1\nouter loop\nvertex NaN 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid invalid")
            .expect("write fixture");
        drop(file);

        let result = transform_stl(
            input_path.to_str().expect("utf8 path"),
            output_path.to_str().expect("utf8 path"),
            &StlTransform {
                bed_center_mm: [100.0, 100.0],
                offset_mm: [0.0, 0.0],
                offset_z_mm: 0.0,
                rotation_deg: [0.0, 0.0, 0.0],
                scale: 1.0,
                scale_axes: None,
                mirror: [false; 3],
            },
        );

        std::fs::remove_file(input_path).expect("remove fixture");
        if output_path.exists() {
            std::fs::remove_file(output_path).expect("remove unexpected output");
        }
        assert!(matches!(result, Err(EngineError::Parse(_))));
    }

    #[test]
    fn stl_transform_rejects_same_input_and_output_without_modifying_source() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-same-path-transform-{}.stl",
            std::process::id(),
        ));
        let source = b"solid model\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid model\n";
        std::fs::write(&path, source).expect("write same-path fixture");

        let path_text = path.to_str().expect("utf8 path");
        let result = transform_stl(
            path_text,
            path_text,
            &StlTransform {
                bed_center_mm: [100.0, 100.0],
                offset_mm: [0.0, 0.0],
                offset_z_mm: 0.0,
                rotation_deg: [0.0, 0.0, 0.0],
                scale: 1.0,
                scale_axes: None,
                mirror: [false; 3],
            },
        );
        let after = std::fs::read(&path).expect("read preserved source");
        std::fs::remove_file(path).expect("remove fixture");

        assert!(matches!(result, Err(EngineError::Parse(_))));
        assert_eq!(after, source);
    }
}
