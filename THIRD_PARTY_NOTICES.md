# Third-party notices

## Android OrcaSlicer runtime bootstrap

The MVP currently includes an Android ARM64 OrcaSlicer runtime bootstrap derived from
[`taylormadearmy/u1-slicer-for-android`](https://github.com/taylormadearmy/u1-slicer-for-android),
which is distributed under the GNU Affero General Public License v3.0.

- Runtime lineage: Snapmaker Orca Android port; the included binary reports Snapmaker Orca 2.3.3 in generated G-code
- Included artifacts: `libprusaslicer-jni.so`, `libc++_shared.so`
- Upstream source and corresponding Android build scripts: the repository linked above
- Runtime SHA-256: `e021818843b130c9faef507e2400c09dc5a2dc7b3848340f626f49d5fc0a8344`
- C++ runtime SHA-256: `4e843755cda12ed65cd2b450be720b122d6657b24b690bf32de74fdc3f529447`

This bootstrap is isolated behind a compatibility seam while DuckySlicer's native
runtime is rebuilt from the repository's OrcaSlicer 2.4.2 source baseline.
