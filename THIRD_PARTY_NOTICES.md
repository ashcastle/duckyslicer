# Third-party notices

## Android OrcaSlicer runtime bootstrap

The current Android ARM64 build includes a slicer runtime bootstrap derived from
[`taylormadearmy/u1-slicer-for-android`](https://github.com/taylormadearmy/u1-slicer-for-android),
which is distributed under the GNU Affero General Public License v3.0.

- Runtime lineage: Snapmaker Orca Android port; the included binary reports Snapmaker Orca 2.3.3 in generated G-code
- Pinned Android source/build revision: [`6f64367361c4bd56bacc97a991874ce1f4b837b4`](https://github.com/taylormadearmy/u1-slicer-for-android/tree/6f64367361c4bd56bacc97a991874ce1f4b837b4)
- Pinned OrcaSlicer engine submodule revision: [`2c8a5385bc53cbc16211b4dd36ef9963ee185f4a`](https://github.com/taylormadearmy/OrcaSlicer/tree/2c8a5385bc53cbc16211b4dd36ef9963ee185f4a)
- Included bootstrap artifact: `libprusaslicer-jni.so`
- Build-time C++ runtime: `libc++_shared.so` from the pinned Android NDK
  `28.2.13676358`; it is staged during the Android build so the APK uses the NDK's
  16 KB page-size-compatible binary
- Corresponding Android build scripts: [`app/src/main/cpp`](https://github.com/taylormadearmy/u1-slicer-for-android/tree/6f64367361c4bd56bacc97a991874ce1f4b837b4/app/src/main/cpp) and [`app/build.gradle`](https://github.com/taylormadearmy/u1-slicer-for-android/blob/6f64367361c4bd56bacc97a991874ce1f4b837b4/app/build.gradle)
- Shipped `libprusaslicer-jni.so` SHA-256: `e021818843b130c9faef507e2400c09dc5a2dc7b3848340f626f49d5fc0a8344`
- Pinned NDK `libc++_shared.so` SHA-256: `ab4e6c71b96b851de45a8a9bd86369e7dbc2130a44b3b4520564be94847910f2`

These hashes and pinned revisions describe the Snapmaker Orca 2.3.3 runtime shipped
in the current APK. The bootstrap remains isolated behind a compatibility seam;
replacing it with a reproducible build from this repository's OrcaSlicer 2.4.2
source baseline is a future milestone.
