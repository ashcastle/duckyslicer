#![deny(unsafe_op_in_unsafe_fn)]

use std::ffi::{CStr, c_char};
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::path::Path;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use serde::Serialize;
use thiserror::Error;

unsafe extern "C" {
    fn duckyslicer_core_version() -> *const c_char;
}

#[derive(Debug, Error)]
enum EngineError {
    #[error("STL 파일을 열 수 없습니다: {0}")]
    Open(#[from] std::io::Error),
    #[error("STL 메시를 읽을 수 없습니다: {0}")]
    Parse(String),
    #[error("STL에 정점이 없습니다")]
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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GcodeLayerPreview {
    ok: bool,
    layer: usize,
    layer_count: usize,
    z_mm: f32,
    segments: Vec<[f32; 4]>,
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

fn parse_axis(line: &str, axis: char) -> Option<f32> {
    line.split_ascii_whitespace()
        .find(|token| token.starts_with(axis))
        .and_then(|token| token.get(1..))
        .and_then(|value| value.parse().ok())
}

fn preview_gcode(path: &str, requested_layer: usize) -> Result<GcodeLayerPreview, EngineError> {
    let reader = BufReader::new(File::open(path)?);
    let mut current_layer: Option<usize> = None;
    let mut layer_count = 0usize;
    let mut layer_z = 0.0f32;
    let mut requested_z = 0.0f32;
    let mut x = 0.0f32;
    let mut y = 0.0f32;
    let mut e = 0.0f32;
    let mut relative_extrusion = false;
    let mut segments = Vec::new();

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
                if current_layer == Some(requested_layer) {
                    requested_z = parsed;
                }
            }
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

        if current_layer == Some(requested_layer) && extruding && (next_x != x || next_y != y) {
            segments.push([x, y, next_x, next_y]);
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
        if current_layer == Some(requested_layer) {
            requested_z = layer_z;
        }
    }

    const SEGMENT_LIMIT: usize = 30_000;
    if segments.len() > SEGMENT_LIMIT {
        let step = segments.len().div_ceil(SEGMENT_LIMIT);
        segments = segments.into_iter().step_by(step).collect();
    }

    Ok(GcodeLayerPreview {
        ok: true,
        layer: requested_layer,
        layer_count,
        z_mm: requested_z,
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
            let message = format!("파일 경로를 읽을 수 없습니다: {error}");
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
    .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"직렬화 오류\"}".to_owned());

    make_java_string(&env, &response)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ashcastle_duckyslicer_NativeEngine_previewGcode(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    layer: jint,
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

    let response = match preview_gcode(&path, layer.max(0) as usize) {
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
    fn gcode_preview_keeps_only_extrusion_paths_for_requested_layer() {
        let path = std::env::temp_dir().join(format!(
            "duckyslicer-preview-{}-{}.gcode",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let mut file = File::create(&path).expect("create fixture");
        writeln!(file, "M83\n;LAYER_CHANGE\n;Z:0.2\nG1 X10 Y10\nG1 X20 Y10 E1\n;LAYER_CHANGE\n;Z:0.4\nG1 X20 Y20 E1")
            .expect("write fixture");
        drop(file);

        let preview = preview_gcode(path.to_str().expect("utf8 path"), 0).expect("parse gcode");
        std::fs::remove_file(path).expect("remove fixture");

        assert_eq!(preview.layer_count, 2);
        assert_eq!(preview.z_mm, 0.2);
        assert_eq!(preview.segments, vec![[10.0, 10.0, 20.0, 10.0]]);
    }
}
