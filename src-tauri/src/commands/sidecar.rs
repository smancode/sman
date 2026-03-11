// src-tauri/src/commands/sidecar.rs
//! OpenClaw Sidecar Management
//!
//! Manages the OpenClaw Gateway sidecar process with isolated configuration.

use std::env;
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tauri_plugin_shell::ShellExt;
use tauri_plugin_shell::process::CommandChild;

/// OpenClaw Gateway port (default for SMAN)
const OPENCLAW_PORT: u16 = 18789;

static SERVER_RUNNING: AtomicBool = AtomicBool::new(false);

/// Store the child process for graceful shutdown
static OPENCLAW_CHILD: Mutex<Option<CommandChild>> = Mutex::new(None);

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

/// Get OpenClaw installation path (relative to app or absolute)
fn get_openclaw_path() -> PathBuf {
    // Check environment variable first (for development)
    if let Ok(path) = env::var("OPENCLAW_PATH") {
        return PathBuf::from(path);
    }

    // Default: sibling directory ../openclaw
    let mut path = env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    path.pop(); // Go up one level
    path.push("openclaw");
    path.push("openclaw.mjs");
    path
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

    // Get OpenClaw path
    let openclaw_path = get_openclaw_path();
    if !openclaw_path.exists() {
        return Err(format!("OpenClaw not found at {:?}", openclaw_path));
    }

    // Build command to run OpenClaw with node
    let mut command = app
        .shell()
        .command("node")
        .args([openclaw_path.to_string_lossy().to_string(), "gateway".to_string()])
        .env("OPENCLAW_CONFIG_PATH", config_path.to_string_lossy().to_string())
        .env("OPENCLAW_STATE_DIR", sman_dir.to_string_lossy().to_string());

    // Get WebSearch API keys from settings and add to environment
    let web_search_vars = crate::commands::settings::get_web_search_env_vars();
    for (key, value) in web_search_vars {
        command = command.env(&key, value);
    }

    // Spawn the process
    let (_rx, child) = command
        .spawn()
        .map_err(|e| format!("Failed to spawn OpenClaw: {}", e))?;

    // Store child for later termination
    {
        let mut child_lock = OPENCLAW_CHILD.lock().unwrap();
        *child_lock = Some(child);
    }

    SERVER_RUNNING.store(true, Ordering::SeqCst);
    Ok(format!(
        "OpenClaw server started on port {} with config at {:?}",
        OPENCLAW_PORT, config_path
    ))
}

#[tauri::command]
pub async fn stop_openclaw_server() -> Result<String, String> {
    if !SERVER_RUNNING.load(Ordering::SeqCst) {
        return Ok("OpenClaw server not running".to_string());
    }

    // Kill the child process
    {
        let mut child_lock = OPENCLAW_CHILD.lock().unwrap();
        if let Some(child) = child_lock.take() {
            child.kill().map_err(|e| format!("Failed to kill OpenClaw: {}", e))?;
        }
    }

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
