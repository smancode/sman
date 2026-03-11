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
/// Use 18790 to avoid conflict with local OpenClaw (18789)
const OPENCLAW_PORT: u16 = 18790;

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

/// Get isolated home directory for OpenClaw
/// This ensures OpenClaw runs with its own independent config
fn get_openclaw_isolated_home() -> PathBuf {
    get_sman_local_dir().join("openclaw-home")
}

/// Get OpenClaw installation path
/// Priority: OPENCLAW_PATH env > hardcoded development path
fn get_openclaw_path() -> PathBuf {
    // Check environment variable first
    if let Ok(path) = env::var("OPENCLAW_PATH") {
        return PathBuf::from(path);
    }

    // Development: hardcoded path (sibling to sman project)
    PathBuf::from("/Users/nasakim/projects/openclaw/openclaw.mjs")
}

/// Configure OpenClaw with LLM settings from SMAN settings
fn configure_openclaw_llm(openclaw_dir: &PathBuf) -> Result<(), String> {
    let settings = crate::commands::settings::get_app_settings().unwrap_or_default();

    // Read existing openclaw.json or create new config
    let config_path = openclaw_dir.join("openclaw.json");
    let mut config: serde_json::Value = if config_path.exists() {
        let content = std::fs::read_to_string(&config_path)
            .map_err(|e| format!("Failed to read openclaw.json: {}", e))?;
        serde_json::from_str(&content).unwrap_or(serde_json::json!({}))
    } else {
        serde_json::json!({})
    };

    // Configure auth profile for the LLM provider
    let api_url = settings.llm.apiUrl.clone();
    let api_key = settings.llm.apiKey.clone();
    let model = settings.llm.defaultModel.clone();

    if api_key.is_empty() {
        return Err("LLM API Key not configured. Please configure in Settings.".to_string());
    }

    // Determine provider from API URL
    let provider_id = if api_url.contains("bigmodel.cn") || api_url.contains("zhipuai") {
        "zhipu"
    } else if api_url.contains("openai") {
        "openai"
    } else if api_url.contains("anthropic") {
        "anthropic"
    } else {
        "custom"
    };

    // Build auth profile
    let profile_key = format!("{}:default", provider_id);

    // Ensure auth.profiles exists
    if config.get("auth").is_none() {
        config["auth"] = serde_json::json!({});
    }
    if config["auth"].get("profiles").is_none() {
        config["auth"]["profiles"] = serde_json::json!({});
    }

    config["auth"]["profiles"][&profile_key] = serde_json::json!({
        "provider": provider_id,
        "mode": "api_key"
    });

    // Configure models
    if config.get("models").is_none() {
        config["models"] = serde_json::json!({
            "mode": "merge",
            "providers": {}
        });
    }
    if config["models"].get("providers").is_none() {
        config["models"]["providers"] = serde_json::json!({});
    }

    // Add provider config
    config["models"]["providers"][provider_id] = serde_json::json!({
        "baseUrl": api_url,
        "api": "anthropic-messages",
        "models": [{
            "id": model,
            "name": model,
            "input": ["text"],
            "contextWindow": 128000,
            "maxTokens": 8192
        }]
    });

    // Configure agent defaults to use this model
    if config.get("agents").is_none() {
        config["agents"] = serde_json::json!({});
    }
    if config["agents"].get("defaults").is_none() {
        config["agents"]["defaults"] = serde_json::json!({});
    }

    let model_ref = format!("{}/{}", provider_id, model);
    config["agents"]["defaults"]["model"] = serde_json::json!({
        "primary": model_ref
    });

    // Write config
    let content = serde_json::to_string_pretty(&config)
        .map_err(|e| format!("Failed to serialize config: {}", e))?;
    std::fs::write(&config_path, content)
        .map_err(|e| format!("Failed to write openclaw.json: {}", e))?;

    Ok(())
}

/// Get LLM environment variables for OpenClaw sidecar
fn get_llm_env_vars() -> Vec<(String, String)> {
    let settings = crate::commands::settings::get_app_settings().unwrap_or_default();
    let mut vars = Vec::new();

    let api_url = settings.llm.apiUrl.clone();
    let api_key = settings.llm.apiKey.clone();

    if !api_key.is_empty() {
        // Determine provider from API URL
        let provider_id = if api_url.contains("bigmodel.cn") || api_url.contains("zhipuai") {
            "zhipu"
        } else if api_url.contains("openai") {
            "openai"
        } else if api_url.contains("anthropic") {
            "anthropic"
        } else {
            "custom"
        };

        // Set API key env var: <PROVIDER>_API_KEY
        let env_key = format!("{}_API_KEY", provider_id.to_uppercase());
        vars.push((env_key, api_key));

        // Also set OPENCLAW_LLM_API_URL for custom providers
        if provider_id == "custom" {
            vars.push(("OPENCLAW_LLM_API_URL".to_string(), api_url));
        }
    }

    vars
}

#[tauri::command]
pub async fn start_openclaw_server(app: tauri::AppHandle) -> Result<String, String> {
    if SERVER_RUNNING.load(Ordering::SeqCst) {
        return Ok("OpenClaw server already running".to_string());
    }

    // Get isolated directories
    let isolated_home = get_openclaw_isolated_home();
    let openclaw_dir = isolated_home.join(".openclaw");

    // Ensure directories exist
    std::fs::create_dir_all(&openclaw_dir)
        .map_err(|e| format!("Failed to create OpenClaw dir: {}", e))?;

    // Configure OpenClaw with LLM settings
    configure_openclaw_llm(&openclaw_dir)?;

    // Get OpenClaw path
    let openclaw_path = get_openclaw_path();
    if !openclaw_path.exists() {
        return Err(format!("OpenClaw not found at {:?}", openclaw_path));
    }

    // Build command to run OpenClaw with node
    // Key: Override HOME to isolate OpenClaw's config directory
    let mut command = app
        .shell()
        .command("node")
        .args([
            openclaw_path.to_string_lossy().to_string(),
            "gateway".to_string(),
            "--port".to_string(),
            OPENCLAW_PORT.to_string(),
            "--auth".to_string(),
            "none".to_string(),  // Disable auth for local development
            "--dev".to_string(),  // Use dev mode for auto config
        ])
        .env("HOME", isolated_home.to_string_lossy().to_string())  // Critical: isolate HOME
        .env("USERPROFILE", isolated_home.to_string_lossy().to_string())  // Windows
        .current_dir(&isolated_home);  // Run from isolated directory

    // Get WebSearch API keys from settings and add to environment
    let web_search_vars = crate::commands::settings::get_web_search_env_vars();
    for (key, value) in web_search_vars {
        command = command.env(&key, value);
    }

    // Get LLM API key and add to environment
    let llm_vars = get_llm_env_vars();
    for (key, value) in llm_vars {
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
        "OpenClaw server started on port {} with isolated home at {:?}",
        OPENCLAW_PORT, isolated_home
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
