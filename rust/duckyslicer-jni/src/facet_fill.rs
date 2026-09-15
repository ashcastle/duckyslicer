//! Connected source-facet selection. Never operates on a decimated preview.
use super::{EngineError, open_stl_input, validate_triangle};
use serde::{Deserialize, Serialize};

const MAX_SOURCE_FACETS: usize = 1_000_000;
const MAX_SELECTED_FACETS: usize = 100_000;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Request {
    path: String,
    seed: usize,
    angle_degrees: f64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Selection {
    facets: Vec<usize>,
}

fn invalid(message: &str) -> EngineError {
    EngineError::Parse(message.to_owned())
}

pub(super) fn select(request: &Request) -> Result<Selection, EngineError> {
    let mut input = open_stl_input(&request.path)?;
    let reader =
        stl_io::create_stl_reader(&mut input).map_err(|error| invalid(&error.to_string()))?;
    select_triangles(reader, request.seed, request.angle_degrees)
}

fn select_triangles(
    triangles: impl Iterator<Item = std::io::Result<stl_io::Triangle>>,
    seed: usize,
    angle: f64,
) -> Result<Selection, EngineError> {
    if !angle.is_finite() || !(0.0..=90.0).contains(&angle) {
        return Err(invalid("Fill angle must be between 0 and 90 degrees"));
    }
    // Sorted edges avoid one heap allocation per edge and retain exact STL ordinals.
    let mut edges: Vec<([u32; 6], usize)> = Vec::new();
    let mut normals: Vec<Option<[f64; 3]>> = Vec::new();
    for triangle in triangles {
        let triangle = triangle.map_err(|error| invalid(&error.to_string()))?;
        validate_triangle(&triangle)?;
        if normals.len() == MAX_SOURCE_FACETS {
            return Err(invalid(
                "Model exceeds connected-fill memory limit; use the brush",
            ));
        }
        let index = normals.len();
        let vertices = triangle.vertices.map(|vertex| vertex.0);
        let u: [f64; 3] = std::array::from_fn(|i| vertices[1][i] as f64 - vertices[0][i] as f64);
        let v: [f64; 3] = std::array::from_fn(|i| vertices[2][i] as f64 - vertices[0][i] as f64);
        let n = [
            u[1] * v[2] - u[2] * v[1],
            u[2] * v[0] - u[0] * v[2],
            u[0] * v[1] - u[1] * v[0],
        ];
        let length = n.iter().map(|x| x * x).sum::<f64>().sqrt();
        if length <= f64::EPSILON {
            normals.push(None);
            continue;
        }
        normals.push(Some(n.map(|x| x / length)));
        let keys = vertices.map(|v| v.map(|x| if x == 0.0 { 0 } else { x.to_bits() }));
        for side in 0..3 {
            let (a, b) = (keys[side], keys[(side + 1) % 3]);
            let (a, b) = if a <= b { (a, b) } else { (b, a) };
            edges.push(([a[0], a[1], a[2], b[0], b[1], b[2]], index));
        }
    }
    if seed >= normals.len() || normals[seed].is_none() {
        return Err(invalid("Selected source facet is unavailable"));
    }
    edges.sort_unstable_by_key(|edge| edge.0);
    let mut neighbors = vec![[usize::MAX; 3]; normals.len()];
    let mut counts = vec![0usize; normals.len()];
    let mut start = 0;
    while start < edges.len() {
        let mut end = start + 1;
        while end < edges.len() && edges[start].0 == edges[end].0 {
            end += 1;
        }
        // A non-manifold edge is an ambiguous boundary, not a bridge between shells.
        if end - start == 2 {
            let (a, b) = (edges[start].1, edges[start + 1].1);
            neighbors[a][counts[a]] = b;
            neighbors[b][counts[b]] = a;
            counts[a] += 1;
            counts[b] += 1;
        }
        start = end;
    }
    drop(edges);
    let threshold = angle.to_radians().cos() - 1e-9;
    let mut visited = vec![false; normals.len()];
    let mut facets = vec![seed];
    visited[seed] = true;
    let mut cursor = 0;
    while cursor < facets.len() {
        let current = facets[cursor];
        let Some(normal) = normals[current] else {
            return Err(invalid("Selected facet has no usable normal"));
        };
        for &neighbor in &neighbors[current][..counts[current]] {
            if visited[neighbor] {
                continue;
            }
            let Some(other) = normals[neighbor] else {
                continue;
            };
            let dot = normal.iter().zip(other).map(|(a, b)| a * b).sum::<f64>();
            if dot < threshold {
                continue;
            }
            if facets.len() == MAX_SELECTED_FACETS {
                return Err(invalid(
                    "Connected region exceeds paint capacity; use the brush",
                ));
            }
            visited[neighbor] = true;
            facets.push(neighbor);
        }
        cursor += 1;
    }
    facets.sort_unstable();
    Ok(Selection { facets })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn triangle(v: [[f32; 3]; 3]) -> std::io::Result<stl_io::Triangle> {
        Ok(stl_io::Triangle {
            normal: stl_io::Normal::new([0.0; 3]),
            vertices: v.map(stl_io::Vertex::new),
        })
    }
    #[test]
    fn fills_shared_edges_but_not_corners_or_sharp_folds() {
        let mesh = vec![
            triangle([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.]]),
            triangle([[1., 0., 0.], [1., 1., 0.], [0., 1., 0.]]),
            triangle([[1., 0., 0.], [1., 0., 1.], [1., 1., 0.]]),
            triangle([[0., 0., 0.], [-1., 0., 0.], [0., -1., 0.]]),
        ];
        assert_eq!(
            select_triangles(mesh.into_iter(), 0, 30.).unwrap().facets,
            vec![0, 1]
        );
    }
    #[test]
    fn preserves_ordinals_after_degenerate_faces_and_signed_zero() {
        let mesh = vec![
            triangle([[0.; 3]; 3]),
            triangle([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.]]),
            triangle([[1., -0., 0.], [1., 1., 0.], [-0., 1., 0.]]),
        ];
        assert_eq!(
            select_triangles(mesh.into_iter(), 1, 0.).unwrap().facets,
            vec![1, 2]
        );
    }
    #[test]
    fn invalid_requests_do_not_return_partial_selection() {
        assert!(select_triangles(std::iter::empty(), 0, 30.).is_err());
        assert!(select_triangles(std::iter::empty(), 0, f64::NAN).is_err());
    }

    #[test]
    fn non_manifold_edge_does_not_connect_unrelated_shells() {
        let mesh = vec![
            triangle([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.]]),
            triangle([[1., 0., 0.], [0., 0., 0.], [0., -1., 0.]]),
            triangle([[1., 0., 0.], [0., 0., 0.], [0., -2., 0.]]),
        ];
        assert_eq!(select_triangles(mesh.into_iter(), 0, 90.).unwrap().facets, vec![0]);
    }
}
