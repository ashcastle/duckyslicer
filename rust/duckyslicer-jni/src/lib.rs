#![deny(unsafe_op_in_unsafe_fn)]

use std::ffi::{CStr, c_char};
use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::path::Path;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use serde::{Deserialize, Serialize};
use thiserror::Error;

unsafe extern "C" {
    fn duckyslicer_core_version() -> *const c_char;
}

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
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct StlTransform {
    bed_center_mm: [f32; 2],
    offset_mm: [f32; 2],
    rotation_deg: [f32; 3],
    scale: f32,
}

#[derive(Serialize)]
struct SuccessResponse {
    ok: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GcodeLayerPreview {
    ok: bool,
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
    Solid = 3,
    Support = 4,
    Bridge = 5,
    Adhesion = 6,
    #[default]
    Other = 7,
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
        } else if normalized.contains("skirt") || normalized.contains("brim") {
            Self::Adhesion
        } else if normalized.contains("solid") || normalized.contains("top surface") {
            Self::Solid
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

fn inspect_stl(path: &str) -> Result<StlInspection, EngineError> {
    let file = File::open(path)?;
    let mesh = stl_io::read_stl(&mut BufReader::new(file))
        .map_err(|error| EngineError::Parse(error.to_string()))?;

    let first = mesh.vertices.first().ok_or(EngineError::Empty)?;
    let mut min = [first[0], first[1], first[2]];
    let mut max = min;

    for vertex in &mesh.vertices[1..] {
        for axis in 0..3 {
            min[axis] = min[axis].min(vertex[axis]);
            max[axis] = max[axis].max(vertex[axis]);
        }
    }

    const PREVIEW_TRIANGLE_LIMIT: usize = 3_500;
    let sample_step = mesh.faces.len().div_ceil(PREVIEW_TRIANGLE_LIMIT).max(1);
    let preview_triangles = mesh
        .faces
        .iter()
        .step_by(sample_step)
        .take(PREVIEW_TRIANGLE_LIMIT)
        .map(|face| {
            let a = mesh.vertices[face.vertices[0]];
            let b = mesh.vertices[face.vertices[1]];
            let c = mesh.vertices[face.vertices[2]];
            [a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]]
        })
        .collect();

    Ok(StlInspection {
        ok: true,
        file_name: Path::new(path)
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("model.stl")
            .to_owned(),
        triangles: mesh.faces.len(),
        dimensions_mm: [max[0] - min[0], max[1] - min[1], max[2] - min[2]],
        min_mm: min,
        max_mm: max,
        preview_triangles,
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
    let local = [
        (vertex[0] - source_center[0]) * transform.scale,
        (vertex[1] - source_center[1]) * transform.scale,
        (vertex[2] - source_center[2]) * transform.scale,
    ];
    let rotated = rotate_vertex(local, transform.rotation_deg);
    [
        rotated[0] + transform.bed_center_mm[0] + transform.offset_mm[0],
        rotated[1] + transform.bed_center_mm[1] + transform.offset_mm[1],
        rotated[2] - transformed_min_z,
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
    if !transform.scale.is_finite() || !(0.05..=10.0).contains(&transform.scale) {
        return Err(EngineError::Parse(
            "Scale must be between 5% and 1000%".to_owned(),
        ));
    }
    if transform
        .bed_center_mm
        .iter()
        .chain(transform.offset_mm.iter())
        .chain(transform.rotation_deg.iter())
        .any(|value| !value.is_finite())
    {
        return Err(EngineError::Parse(
            "Transform contains an invalid value".to_owned(),
        ));
    }

    let mut first_pass_file = BufReader::new(File::open(input_path)?);
    let first_pass = stl_io::create_stl_reader(&mut first_pass_file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let mut triangle_count = 0u32;
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    for triangle in first_pass {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        triangle_count = triangle_count
            .checked_add(1)
            .ok_or_else(|| EngineError::Parse("STL contains too many triangles".to_owned()))?;
        for vertex in triangle.vertices {
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
    let mut minimum_pass_file = BufReader::new(File::open(input_path)?);
    let minimum_pass = stl_io::create_stl_reader(&mut minimum_pass_file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let mut transformed_min_z = f32::INFINITY;
    for triangle in minimum_pass {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        for vertex in triangle.vertices {
            let local = [
                (vertex[0] - source_center[0]) * transform.scale,
                (vertex[1] - source_center[1]) * transform.scale,
                (vertex[2] - source_center[2]) * transform.scale,
            ];
            transformed_min_z =
                transformed_min_z.min(rotate_vertex(local, transform.rotation_deg)[2]);
        }
    }

    let mut output_pass_file = BufReader::new(File::open(input_path)?);
    let output_pass = stl_io::create_stl_reader(&mut output_pass_file)
        .map_err(|error| EngineError::Parse(error.to_string()))?;
    let output_file = File::create(output_path)?;
    let mut writer = BufWriter::new(output_file);
    writer.write_all(&[0u8; 80])?;
    writer.write_all(&triangle_count.to_le_bytes())?;
    for triangle in output_pass {
        let triangle = triangle.map_err(|error| EngineError::Parse(error.to_string()))?;
        let vertices = triangle.vertices.map(|vertex| {
            transformed_vertex(vertex.0, source_center, transformed_min_z, transform)
        });
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
    Ok(())
}

fn parse_axis(line: &str, axis: char) -> Option<f32> {
    line.split_ascii_whitespace()
        .find(|token| token.starts_with(axis))
        .and_then(|token| token.get(1..))
        .and_then(|value| value.parse().ok())
}

fn preview_gcode(
    path: &str,
    requested_start_layer: usize,
    requested_end_layer: usize,
) -> Result<GcodeLayerPreview, EngineError> {
    let start_layer = requested_start_layer.min(requested_end_layer);
    let end_layer = requested_start_layer.max(requested_end_layer);
    let reader = BufReader::new(File::open(path)?);
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

    for line in reader.lines() {
        let line = line?;
        let trimmed = line.trim();
        if trimmed == ";LAYER_CHANGE" {
            let next = current_layer.map_or(0, |layer| layer + 1);
            current_layer = Some(next);
            layer_count = layer_count.max(next + 1);
            continue;
        }
        if let Some(value) = trimmed.strip_prefix(";Z:") {
            if let Ok(parsed) = value.parse::<f32>() {
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

        let next_x = parse_axis(command, 'X').unwrap_or(x);
        let next_y = parse_axis(command, 'Y').unwrap_or(y);
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
            seen_segments += 1;
            if seen_segments.is_multiple_of(segment_stride) {
                segments.push([x, y, next_x, next_y, layer_z, toolpath_role.code()]);
            }
            const SEGMENT_LIMIT: usize = 60_000;
            if segments.len() > SEGMENT_LIMIT {
                segments = segments.into_iter().step_by(2).collect();
                segment_stride = segment_stride.saturating_mul(2);
            }
        }

        x = next_x;
        y = next_y;
        if let Some(next_e) = next_e {
            e = if relative_extrusion {
                e + next_e
            } else {
                next_e
            };
        }
    }

    let last_layer = layer_count.saturating_sub(1);
    Ok(GcodeLayerPreview {
        ok: true,
        start_layer: start_layer.min(last_layer),
        end_layer: end_layer.min(last_layer),
        layer_count,
        min_z_mm: min_requested_z.unwrap_or(0.0),
        max_z_mm: max_requested_z.unwrap_or(0.0),
        segments,
    })
}

fn make_java_string(env: &JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_version(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let pointer = unsafe { duckyslicer_core_version() };
    let version = if pointer.is_null() {
        "DuckySlicer native bridge unavailable"
    } else {
        unsafe { CStr::from_ptr(pointer) }
            .to_str()
            .unwrap_or("DuckySlicer native bridge")
    };
    make_java_string(&env, version)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_inspectStl(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jstring {
    let path = match env.get_string(&path) {
        Ok(path) => path.to_string_lossy().into_owned(),
        Err(error) => {
            let message = format!("Unable to read file path: {error}");
            let response = serde_json::to_string(&ErrorResponse {
                ok: false,
                error: &message,
            })
            .unwrap_or_else(|_| "{\"ok\":false}".to_owned());
            return make_java_string(&env, &response);
        }
    };

    let response = match inspect_stl(&path) {
        Ok(inspection) => serde_json::to_string(&inspection),
        Err(error) => serde_json::to_string(&ErrorResponse {
            ok: false,
            error: &error.to_string(),
        }),
    }
    .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"Serialization failed\"}".to_owned());

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
    let read_string = |env: &mut JNIEnv<'_>, value: &JString<'_>| {
        env.get_string(value)
            .map(|text| text.to_string_lossy().into_owned())
            .map_err(|error| EngineError::Parse(error.to_string()))
    };
    let result = (|| {
        let input_path = read_string(&mut env, &input_path)?;
        let output_path = read_string(&mut env, &output_path)?;
        let transform_json = read_string(&mut env, &transform_json)?;
        let transform: StlTransform = serde_json::from_str(&transform_json)
            .map_err(|error| EngineError::Parse(error.to_string()))?;
        transform_stl(&input_path, &output_path, &transform)
    })();

    let response = match result {
        Ok(()) => serde_json::to_string(&SuccessResponse { ok: true }),
        Err(error) => serde_json::to_string(&ErrorResponse {
            ok: false,
            error: &error.to_string(),
        }),
    }
    .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"Serialization failed\"}".to_owned());
    make_java_string(&env, &response)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRange(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    start_layer: jint,
    end_layer: jint,
) -> jstring {
    let path = match env.get_string(&path) {
        Ok(path) => path.to_string_lossy().into_owned(),
        Err(error) => {
            let message = format!("invalid path: {error}");
            let response = serde_json::to_string(&ErrorResponse {
                ok: false,
                error: &message,
            })
            .unwrap_or_else(|_| "{\"ok\":false}".to_owned());
            return make_java_string(&env, &response);
        }
    };

    let response = match preview_gcode(
        &path,
        start_layer.max(0) as usize,
        end_layer.max(0) as usize,
    ) {
        Ok(preview) => serde_json::to_string(&preview),
        Err(error) => serde_json::to_string(&ErrorResponse {
            ok: false,
            error: &error.to_string(),
        }),
    }
    .unwrap_or_else(|_| "{\"ok\":false}".to_owned());
    make_java_string(&env, &response)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn missing_stl_is_reported_without_panicking() {
        let result = inspect_stl("/definitely/missing/duckyslicer.stl");
        assert!(matches!(result, Err(EngineError::Open(_))));
    }

    #[test]
    fn gcode_preview_keeps_extrusion_paths_and_z_for_requested_range() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-preview-{}-{}.gcode",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let mut file = File::create(&path).expect("create fixture");
        writeln!(file, "M83\n;LAYER_CHANGE\n;Z:0.2\n;TYPE:Outer wall\nG1 X10 Y10\nG1 X20 Y10 E1\n;LAYER_CHANGE\n;Z:0.4\n;TYPE:Internal solid infill\nG1 X20 Y20 E1")
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
                [20.0, 10.0, 20.0, 20.0, 0.4, 3.0],
            ]
        );
        assert_eq!(clamped.start_layer, 1);
        assert_eq!(clamped.end_layer, 1);
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
            rotation_deg: [0.0, 0.0, 90.0],
            scale: 2.0,
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
        assert_eq!(inspection.min_mm[2], 0.0);
    }
}
