# Third-party notices

## Android OrcaSlicer runtime bootstrap

The MVP currently includes an Android ARM64 OrcaSlicer runtime bootstrap derived from
[`taylormadearmy/u1-slicer-for-android`](https://github.com/taylormadearmy/u1-slicer-for-android),
which is distributed under the GNU Affero General Public License v3.0.

- Runtime lineage: Snapmaker Orca Android port; the included binary reports Snapmaker Orca 2.3.3 in generated G-code
- Pinned Android source/build revision: [`6f64367361c4bd56bacc97a991874ce1f4b837b4`](https://github.com/taylormadearmy/u1-slicer-for-android/tree/6f64367361c4bd56bacc97a991874ce1f4b837b4)
- Pinned OrcaSlicer engine submodule revision: [`2c8a5385bc53cbc16211b4dd36ef9963ee185f4a`](https://github.com/taylormadearmy/OrcaSlicer/tree/2c8a5385bc53cbc16211b4dd36ef9963ee185f4a)
- Included artifacts: `libprusaslicer-jni.so`, `libc++_shared.so`
- Corresponding Android build scripts: [`app/src/main/cpp`](https://github.com/taylormadearmy/u1-slicer-for-android/tree/6f64367361c4bd56bacc97a991874ce1f4b837b4/app/src/main/cpp) and [`app/build.gradle`](https://github.com/taylormadearmy/u1-slicer-for-android/blob/6f64367361c4bd56bacc97a991874ce1f4b837b4/app/build.gradle)
- Runtime SHA-256: `e021818843b130c9faef507e2400c09dc5a2dc7b3848340f626f49d5fc0a8344`
- C++ runtime SHA-256: `4e843755cda12ed65cd2b450be720b122d6657b24b690bf32de74fdc3f529447`

This bootstrap is isolated behind a compatibility seam while DuckySlicer's native
runtime is rebuilt from the repository's OrcaSlicer 2.4.2 source baseline.
