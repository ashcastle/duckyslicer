use std::path::PathBuf;

fn main() {
    let crate_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").expect("manifest directory"));
    let repository_root = crate_dir
        .parent()
        .and_then(|path| path.parent())
        .expect("crate must live under rust/");
    let bridge_dir = repository_root.join("native/duckyslicer_bridge");

    cc::Build::new()
        .cpp(true)
        .std("c++17")
        .include(bridge_dir.join("include"))
        .file(bridge_dir.join("src/duckyslicer_bridge.cpp"))
        .warnings(true)
        .compile("duckyslicer_bridge");

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=dylib=vulkan");
    }

    println!("cargo:rerun-if-changed={}", bridge_dir.display());
}
