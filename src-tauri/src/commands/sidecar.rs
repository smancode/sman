// src-tauri/src/commands/sidecar.rs
//! OpenClaw Sidecar Management
//!
//! Manages the OpenClaw Gateway sidecar process with isolated configuration.

use std::env;
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use tauri_plugin_shell::ShellExt;

/// OpenClaw Gateway port (default for SMAN)
const OPENCLAW_PORT: u16 = 18789;

static SERVER_RUNNING: AtomicBool = AtomicBool::new(false);

/// Get SMAN local directory for isolated configuration.
///
/// Platform support:
/// - macOS/Linux: $HOME/.smanlocal
/// - Windows: %USERPROFILE%\.smanlocal
fn get_sman_local_dir() -> PathBuf {
    let home = env::var("HOME")
        .or_else(|_| env::var("USERPROFILE"))
        .or_else(|_| env::var("HOMEPATH"))
        .unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".smanlocal")
}

#[tauri::command]
pub async fn start_openclaw_server(app: tauri::AppHandle) -> Result<String, String> {
    if SERVER_RUNNING.load(Ordering::SeqCst) {
        return Ok("OpenClaw server already running".to_string());
    }

    // Get isolated config directory
    let sman_dir = get_sman_local_dir();
    let config_path = sman_dir.join("openclaw.json");

    // Ensure config directory exists
    if !sman_dir.exists() {
        std::fs::create_dir_all(&sman_dir)
            .map_err(|e| format!("Failed to create config dir: {}", e))?;
    }

    let sidecar = app
        .shell()
        .sidecar("openclaw-server")
        .map_err(|e| format!("Failed to create sidecar: {}", e))?;

    // Set environment variables for isolated configuration
    let (mut _rx, _child) = sidecar
        .env("OPENCLAW_CONFIG_PATH", config_path.to_string_lossy().to_string())
        .env("OPENCLAW_STATE_DIR", sman_dir.to_string_lossy().to_string())
        .spawn()
        .map_err(|e| format!("Failed to spawn sidecar: {}", e))?;

    SERVER_RUNNING.store(true, Ordering::SeqCst);
    Ok(format!(
        "OpenClaw server started on port {} with config at {:?}",
        OPENCLAW_PORT, config_path
    ))
}

#[tauri::command]
pub async fn stop_openclaw_server() -> Result<String, String> {
    // TODO: Implement graceful shutdown via signal
    SERVER_RUNNING.store(false, Ordering::SeqCst);
    Ok("OpenClaw server stopped".to_string())
}

#[tauri::command]
pub async fn check_openclaw_health() -> Result<bool, String> {
    if !SERVER_RUNNING.load(Ordering::SeqCst) {
        return Ok(false);
    }

    // Simple TCP health check on OpenClaw Gateway port
    let addr: SocketAddr = format!("127.0.0.1:{}", OPENCLAW_PORT)
        .parse()
        .map_err(|e: std::net::AddrParseError| e.to_string())?;
    match TcpStream::connect_timeout(&addr, std::time::Duration::from_secs(2)) {
        Ok(_) => Ok(true),
        Err(_) => Ok(false),
    }
}

#[tauri::command]
pub fn is_server_running() -> bool {
    SERVER_RUNNING.load(Ordering::SeqCst)
}

#[tauri::command]
pub fn get_sman_local_path() -> String {
    get_sman_local_dir().to_string_lossy().to_string()
}

#[tauri::command]
pub fn get_openclaw_port() -> u16 {
    OPENCLAW_PORT
}
