//! Tauri build script
//!
//! This script runs during the build process to generate Tauri configuration.

fn main() {
    tauri_build::build();

    // Tell Cargo to rerun this script if the tauri.conf.json changes
    println!("cargo:rerun-if-changed=tauri.conf.json");
    println!("cargo:rerun-if-changed=Cargo.toml");
}
