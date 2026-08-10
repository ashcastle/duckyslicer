#![deny(unsafe_op_in_unsafe_fn)]

use std::ffi::{CStr, c_char};
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jfloatArray, jint, jstring};
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
const MAX_GCODE_COORDINATE_ABS_MM: f32 = 1_000_000.0;
const MAX_PREVIEW_SEGMENTS: usize = 120_000;
const MAX_PREVIEW_LAYERS: usize = 1_000_000;
const PREVIEW_PAYLOAD_MAGIC: f32 = 17_491.0;
const PREVIEW_PAYLOAD_VERSION: f32 = 1.0;
const PREVIEW_HEADER_FLOATS: usize = 7;
const INTERNAL_ERROR_JSON: &str =
    "{\"ok\":false,\"error\":\"The file could not be processed safely\"}";
static TEMP_OUTPUT_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Error)]
enum EngineError {
    #[error("Unable to open file: {0}")]
    Open(#[from] std::io::Error),
    #[error("Unable to parse STL mesh: {0}")]
    Parse(String),
    #[error("STL contains no vertices")]
    Empty,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StlInspection {
    ok: bool,
    file_name: String,
    triangles: usize,
    dimensions_mm: [f32; 3],
    min_mm: [f32; 3],
    max_mm: [f32; 3],
    preview_triangles: Vec<[f32; 9]>,
    preview_triangle_indices: Vec<usize>,
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

impl StlTransform {
    fn scales(&self) -> [f32; 3] {
        self.scale_axes.unwrap_or([self.scale; 3])
    }

    fn maximum_scale(&self) -> f32 {
        self.scales().into_iter().fold(self.scale, f32::max)
    }
}

#[derive(Serialize)]
struct SuccessResponse {
    ok: bool,
}

struct GcodeLayerPreview {
    start_layer: usize,
    end_layer: usize,
    layer_count: usize,
    min_z_mm: f32,
    max_z_mm: f32,
    segments: Vec<[f32; 6]>,
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
    const PREVIEW_TRIANGLE_LIMIT: usize = 3_500;
    let mut file = open_stl_input(path)?;
    let triangles = stl_io::create_stl_reader(&mut file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let mut triangle_count = 0usize;
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    let mut preview_triangles = Vec::with_capacity(PREVIEW_TRIANGLE_LIMIT);
    let mut preview_triangle_indices = Vec::with_capacity(PREVIEW_TRIANGLE_LIMIT);
    let mut preview_stride = 1usize;

    for triangle in triangles {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        validate_triangle(&triangle)?;
        let vertices = triangle.vertices.map(|vertex| vertex.0);
        let ordinal = triangle_count;
        triangle_count = triangle_count
            .checked_add(1)
            .ok_or_else(|| EngineError::Parse("STL contains too many triangles".to_owned()))?;
        for vertex in vertices {
            for axis in 0..3 {
                min[axis] = min[axis].min(vertex[axis]);
                max[axis] = max[axis].max(vertex[axis]);
            }
        }
        if ordinal.is_multiple_of(preview_stride) {
            let [a, b, c] = vertices;
            preview_triangles.push([a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]]);
            preview_triangle_indices.push(ordinal);
        }
        if preview_triangles.len() > PREVIEW_TRIANGLE_LIMIT {
            preview_triangles = preview_triangles.into_iter().step_by(2).collect();
            preview_triangle_indices = preview_triangle_indices.into_iter().step_by(2).collect();
            preview_stride = preview_stride.saturating_mul(2);
        }
    }
    if triangle_count == 0 {
        return Err(EngineError::Empty);
    }

    Ok(StlInspection {
        ok: true,
        file_name: Path::new(path)
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("model.stl")
            .to_owned(),
        triangles: triangle_count,
        dimensions_mm: [max[0] - min[0], max[1] - min[1], max[2] - min[2]],
        min_mm: min,
        max_mm: max,
        preview_triangles,
        preview_triangle_indices,
    })
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

fn write_f32(writer: &mut impl Write, value: f32) -> Result<(), EngineError> {
    writer.write_all(&value.to_le_bytes())?;
    Ok(())
}

fn transform_stl(
    input_path: &str,
    output_path: &str,
    transform: &StlTransform,
) -> Result<(), EngineError> {
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
    Ok(())
}

fn parse_axis(line: &str, axis: char) -> Option<f32> {
    line.split_ascii_whitespace()
        .find(|token| token.starts_with(axis))
        .and_then(|token| token.get(1..))
        .and_then(|value| value.parse().ok())
        .filter(|value: &f32| value.is_finite())
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
    let mut e = 0.0f32;
    let mut relative_extrusion = false;
    let mut toolpath_role = ToolpathRole::Other;
    let mut segments = Vec::new();
    let mut segment_stride = 1usize;
    let mut seen_segments = 0usize;
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
            toolpath_role = ToolpathRole::from_label(value);
            continue;
        }

        let command = trimmed.split(';').next().unwrap_or("").trim();
        if command == "M82" {
            relative_extrusion = false;
            continue;
        }
        if command == "M83" {
            relative_extrusion = true;
            continue;
        }
        if command.starts_with("G92") {
            if let Some(next_e) = parse_axis(command, 'E') {
                e = next_e;
            }
            continue;
        }
        if !(command.starts_with("G0 ") || command.starts_with("G1 ")) {
            continue;
        }

        let next_x = parse_axis(command, 'X')
            .filter(|value| value.abs() <= MAX_GCODE_COORDINATE_ABS_MM)
            .unwrap_or(x);
        let next_y = parse_axis(command, 'Y')
            .filter(|value| value.abs() <= MAX_GCODE_COORDINATE_ABS_MM)
            .unwrap_or(y);
        let next_e = parse_axis(command, 'E');
        let extruding = next_e.is_some_and(|value| {
            if relative_extrusion {
                value > 0.0
            } else {
                value > e
            }
        });

        let in_requested_range =
            current_layer.is_some_and(|layer| (start_layer..=end_layer).contains(&layer));
        if in_requested_range && extruding && (next_x != x || next_y != y) {
            seen_segments = seen_segments.checked_add(1).ok_or_else(|| {
                EngineError::Parse("G-code contains too many extrusion segments".to_owned())
            })?;
            if seen_segments.is_multiple_of(segment_stride) {
                segments.push([x, y, next_x, next_y, layer_z, toolpath_role.code()]);
            }
            // Keep common full-model previews intact so Android can reduce whole
            // layers instead of punching visual gaps through perimeter loops.
            if segments.len() > MAX_PREVIEW_SEGMENTS {
                segments = segments.into_iter().step_by(2).collect();
                segment_stride = segment_stride.saturating_mul(2);
            }
        }

        x = next_x;
        y = next_y;
        if let Some(next_e) = next_e {
            let updated = if relative_extrusion {
                e + next_e
            } else {
                next_e
            };
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
        segments,
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
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_inspectStl(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jstring {
    let response = guarded_json(|| {
        let path = env
            .get_string(&path)
            .map(|path| path.to_string_lossy().into_owned())
            .map_err(|error| EngineError::Parse(format!("Unable to read file path: {error}")))?;
        inspect_stl(&path)
    });

    make_java_string(&env, &response)
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
        transform_stl(&input_path, &output_path, &transform)?;
        Ok(SuccessResponse { ok: true })
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

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
        assert!(inspection.preview_triangles.len() <= 3_500);
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
        assert!(inspection.dimensions_mm[0] > 79.0);
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
        assert!((inspection.dimensions_mm[0] - 8.0).abs() < 0.001);
        assert!((inspection.dimensions_mm[1] - 4.0).abs() < 0.001);
        assert!((inspection.min_mm[0] - 101.0).abs() < 0.001);
        assert!((inspection.max_mm[1] - 99.0).abs() < 0.001);
        assert_eq!(inspection.min_mm[2], 7.0);
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
