//! Tauri build script
//!
//! This script runs during the build process to generate Tauri configuration.

fn main() {
    // Only run tauri_build::build() when building the Tauri app,
    // not when building as a library for tests.
    #[cfg(feature = "custom-protocol")]
    tauri_build::build();

    // Tell Cargo to rerun this script if the tauri.conf.json changes
    println!("cargo:rerun-if-changed=tauri.conf.json");
    println!("cargo:rerun-if-changed=Cargo.toml");
}
