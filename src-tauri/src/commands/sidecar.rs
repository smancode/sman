// src-tauri/src/commands/sidecar.rs
//! OpenClaw Sidecar Management

use std::sync::atomic::{AtomicBool, Ordering};
use tauri_plugin_shell::ShellExt;

static SERVER_RUNNING: AtomicBool = AtomicBool::new(false);

#[tauri::command]
pub async fn start_openclaw_server(app: tauri::AppHandle) -> Result<String, String> {
    if SERVER_RUNNING.load(Ordering::SeqCst) {
        return Ok("OpenClaw server already running".to_string());
    }

    let sidecar = app
        .shell()
        .sidecar("openclaw-server")
        .map_err(|e| format!("Failed to create sidecar: {}", e))?;

    let (mut _rx, _child) = sidecar
        .spawn()
        .map_err(|e| format!("Failed to spawn sidecar: {}", e))?;

    SERVER_RUNNING.store(true, Ordering::SeqCst);
    Ok("OpenClaw server started".to_string())
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

    // Simple TCP health check
    use std::net::{SocketAddr, TcpStream};
    let addr: SocketAddr = "127.0.0.1:3000"
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
