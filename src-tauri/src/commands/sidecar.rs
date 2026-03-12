// src-tauri/src/commands/sidecar.rs
//! OpenClaw Sidecar Management
//!
//! Manages the OpenClaw Gateway sidecar process with isolated configuration.

use std::env;
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tauri::Manager;
use tauri_plugin_shell::ShellExt;
use tauri_plugin_shell::process::CommandChild;

/// OpenClaw Gateway port (default for SMAN)
/// Use 18790 to avoid conflict with local OpenClaw (18789)
const OPENCLAW_PORT: u16 = 18790;
const FIXED_GATEWAY_TOKEN: &str = "sman-31244d65207dcced";

static SERVER_RUNNING: AtomicBool = AtomicBool::new(false);
const OPENCLAW_PRESET_FILES: [(&str, &str); 5] = [
    ("AGENTS.md", include_str!("../../../openclaw-presets/AGENTS.md")),
    ("SOUL.md", include_str!("../../../openclaw-presets/SOUL.md")),
    ("TOOLS.md", include_str!("../../../openclaw-presets/TOOLS.md")),
    ("IDENTITY.md", include_str!("../../../openclaw-presets/IDENTITY.md")),
    ("USER.md", include_str!("../../../openclaw-presets/USER.md")),
];

/// Store the child process for graceful shutdown
static OPENCLAW_CHILD: Mutex<Option<CommandChild>> = Mutex::new(None);

/// Get SMAN local directory for isolated configuration.
///
/// Platform support:
/// - macOS/Linux: $HOME/.smanlocal
/// - Windows: %USERPROFILE%\.smanlocal
fn get_sman_local_dir() -> PathBuf {
    if let Ok(path) = env::var("SMAN_LOCAL_DIR") {
        return PathBuf::from(path);
    }

    if cfg!(debug_assertions) {
        let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let sman_root = manifest_dir
            .parent()
            .map(PathBuf::from)
            .unwrap_or(manifest_dir);
        return sman_root.join(".smanlocal");
    }

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

fn apply_workspace_presets(openclaw_dir: &PathBuf) -> Vec<PathBuf> {
    let target_dirs = [openclaw_dir.join("workspace"), openclaw_dir.join("workspace-dev")];
    let mut applied_dirs: Vec<PathBuf> = Vec::new();
    for workspace_dir in target_dirs.iter() {
        if let Err(e) = std::fs::create_dir_all(workspace_dir) {
            eprintln!(
                "[Sidecar] Warning: Failed to create workspace preset dir {:?}: {}",
                workspace_dir,
                e
            );
            continue;
        }
        let mut dir_ok = true;
        for (file_name, content) in OPENCLAW_PRESET_FILES.iter() {
            let target = workspace_dir.join(file_name);
            if let Err(e) = std::fs::write(&target, content) {
                eprintln!(
                    "[Sidecar] Warning: Failed to write workspace preset file {:?}: {}",
                    target,
                    e
                );
                dir_ok = false;
            }
        }
        if dir_ok {
            applied_dirs.push(workspace_dir.clone());
        }
    }
    applied_dirs
}

/// Generate a deterministic gateway token based on machine ID
/// This ensures the same token is used across restarts, avoiding mismatches
fn generate_gateway_token() -> String {
    FIXED_GATEWAY_TOKEN.to_string()
}

/// Configure gateway for SMAN
/// Sets controlUi options and PRE-GENERATES token (ensures Gateway and client use same token)
fn configure_gateway_for_sman(openclaw_dir: &PathBuf) -> Result<String, String> {
    let config_path = openclaw_dir.join("openclaw.json");

    // CRITICAL: Must re-read config here because configure_openclaw_llm may have modified it
    // Use a retry loop to ensure we get the latest version
    let mut config: serde_json::Value = serde_json::json!({});
    let max_retries = 10;
    for i in 0..max_retries {
        if config_path.exists() {
            match std::fs::read_to_string(&config_path) {
                Ok(content) => {
                    match serde_json::from_str(&content) {
                        Ok(parsed) => {
                            config = parsed;
                            break;
                        }
                        Err(e) => {
                            println!("[Sidecar] Warning: Failed to parse config (attempt {}): {}", i + 1, e);
                            std::thread::sleep(std::time::Duration::from_millis(50));
                        }
                    }
                }
                Err(e) => {
                    println!("[Sidecar] Warning: Failed to read config (attempt {}): {}", i + 1, e);
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
            }
        } else {
            // Config doesn't exist yet, wait a bit
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
    }

    // Ensure gateway section exists
    if config.get("gateway").is_none() {
        config["gateway"] = serde_json::json!({});
    }

    // Ensure gateway.auth section exists
    if config["gateway"].get("auth").is_none() {
        config["gateway"]["auth"] = serde_json::json!({});
    }

    // Generate a random token (same format as OpenClaw Gateway)
    let token = generate_gateway_token();

    // Set gateway auth mode and token
    // CRITICAL: Pre-generate token so Gateway and client use the SAME token
    config["gateway"]["auth"]["mode"] = serde_json::json!("token");
    config["gateway"]["auth"]["token"] = serde_json::json!(&token);
    config["gateway"]["auth"]["rateLimit"] = serde_json::json!({
        "maxAttempts": 1000,
        "windowMs": 60000,
        "lockoutMs": 1000,
        "exemptLoopback": true
    });

    // Configure controlUi for Tauri WebView
    // Use wildcard to allow all origins in local development
    // dangerouslyDisableDeviceAuth: true skips device pairing for local development
    config["gateway"]["controlUi"] = serde_json::json!({
        "allowedOrigins": ["*"],
        "dangerouslyDisableDeviceAuth": true
    });

    // Set gateway mode to local
    config["gateway"]["mode"] = serde_json::json!("local");
    config["gateway"]["bind"] = serde_json::json!("loopback");

    // Write config
    let content = serde_json::to_string_pretty(&config)
        .map_err(|e| format!("Failed to serialize config: {}", e))?;
    std::fs::write(&config_path, content)
        .map_err(|e| format!("Failed to write openclaw.json: {}", e))?;

    // DIAGNOSTIC: Verify written token
    let verify_content = std::fs::read_to_string(&config_path)
        .map_err(|e| format!("Failed to verify config: {}", e))?;
    let verify_config: serde_json::Value = serde_json::from_str(&verify_content)
        .map_err(|e| format!("Failed to parse verified config: {}", e))?;
    let written_token = verify_config
        .get("gateway")
        .and_then(|g| g.get("auth"))
        .and_then(|a| a.get("token"))
        .and_then(|t| t.as_str())
        .unwrap_or("NOT_FOUND");

    println!("[DIAGNOSTIC] configure_gateway_for_sman:");
    println!("  Generated token: {}...", &token[..20.min(token.len())]);
    println!("  Written token:   {}...", &written_token[..20.min(written_token.len())]);
    println!("  Match: {}", if token == written_token { "YES" } else { "NO" });

    Ok(token)
}

/// Get OpenClaw installation path
/// Priority: OPENCLAW_PATH env > bundled resource > sibling openclaw project path
fn get_openclaw_path(app: &tauri::AppHandle) -> PathBuf {
    // Check environment variable first
    if let Ok(path) = env::var("OPENCLAW_PATH") {
        return PathBuf::from(path);
    }

    if let Ok(resource_dir) = app.path().resource_dir() {
        let bundled_paths = [
            resource_dir.join("openclaw").join("openclaw.mjs"),
            resource_dir.join("resources").join("openclaw").join("openclaw.mjs"),
        ];
        for bundled_path in bundled_paths {
            if bundled_path.exists() {
                return bundled_path;
            }
        }
    }

    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let sman_root = manifest_dir
        .parent()
        .map(PathBuf::from)
        .unwrap_or(manifest_dir.clone());
    let sibling_openclaw = sman_root
        .parent()
        .map(|parent| parent.join("openclaw").join("openclaw.mjs"));
    if let Some(path) = sibling_openclaw {
        return path;
    }
    sman_root.join("openclaw").join("openclaw.mjs")
}

fn get_node_command(app: &tauri::AppHandle) -> String {
    if let Ok(path) = env::var("OPENCLAW_NODE_PATH") {
        return path;
    }

    if cfg!(debug_assertions) {
        return "node".to_string();
    }

    if let Ok(resource_dir) = app.path().resource_dir() {
        let candidates = if cfg!(target_os = "windows") {
            vec![
                resource_dir
                    .join("openclaw")
                    .join("runtime")
                    .join("node.exe"),
                resource_dir
                    .join("resources")
                    .join("openclaw")
                    .join("runtime")
                    .join("node.exe"),
            ]
        } else {
            vec![
                resource_dir.join("openclaw").join("runtime").join("node"),
                resource_dir
                    .join("resources")
                    .join("openclaw")
                    .join("runtime")
                    .join("node"),
            ]
        };
        for candidate in candidates {
            if candidate.exists() {
                return candidate.to_string_lossy().to_string();
            }
        }
    }
    "node".to_string()
}

/// Configure OpenClaw with complete settings (LLM + Gateway) in a single write
/// This avoids race conditions between multiple config writes
fn configure_openclaw_complete(openclaw_dir: &PathBuf) -> Result<String, String> {
    let settings = crate::commands::settings::get_app_settings().unwrap_or_default();

    // Generate gateway token first
    let gateway_token = generate_gateway_token();

    // Read existing openclaw.json or create new config
    let config_path = openclaw_dir.join("openclaw.json");
    let mut config: serde_json::Value = if config_path.exists() {
        let content = std::fs::read_to_string(&config_path)
            .map_err(|e| format!("Failed to read openclaw.json: {}", e))?;
        serde_json::from_str(&content).unwrap_or(serde_json::json!({}))
    } else {
        serde_json::json!({})
    };

    // ========== LLM Configuration ==========
    let api_url = settings.llm.apiUrl.clone();
    let api_key = settings.llm.apiKey.clone();
    let model = settings.llm.defaultModel.clone();

    if api_key.is_empty() {
        println!("[Sidecar] Warning: No LLM API Key configured. Server will start but chat won't work.");
    } else {
        // Determine provider from API URL
        let provider_id = if api_url.contains("bigmodel.cn") || api_url.contains("zhipuai") {
            "zhipu"
        } else if api_url.contains("minimaxi") {
            "minimax"
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

        // Determine API type based on provider
        // MiniMax uses OpenAI-compatible /chat/completions endpoint
        let api_type = if provider_id == "zhipu" || provider_id == "openai" || provider_id == "minimax" {
            "openai-completions"
        } else {
            "anthropic-messages"
        };

        // Add provider config
        config["models"]["providers"][provider_id] = serde_json::json!({
            "baseUrl": api_url,
            "api": api_type,
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

        // CRITICAL: Write auth-profiles.json to agent directory
        let agent_dir = openclaw_dir.join("agents").join("dev").join("agent");
        std::fs::create_dir_all(&agent_dir)
            .map_err(|e| format!("Failed to create agent dir: {}", e))?;

        let auth_profiles_path = agent_dir.join("auth-profiles.json");
        let auth_profiles = serde_json::json!({
            "profiles": {
                &profile_key: {
                    "provider": provider_id,
                    "mode": "api_key",
                    "apiKey": api_key
                }
            },
            "defaultProfile": profile_key
        });

        let auth_content = serde_json::to_string_pretty(&auth_profiles)
            .map_err(|e| format!("Failed to serialize auth-profiles: {}", e))?;
        std::fs::write(&auth_profiles_path, auth_content)
            .map_err(|e| format!("Failed to write auth-profiles.json: {}", e))?;

        println!("[Sidecar] LLM configured: provider={}, model={}", provider_id, model);
    }

    // Ensure basic agent config exists
    if config.get("agents").is_none() {
        config["agents"] = serde_json::json!({
            "defaults": {
                "identity": { "name": "SMAN Assistant" }
            }
        });
    }
    if config["agents"].get("defaults").is_none() {
        config["agents"]["defaults"] = serde_json::json!({});
    }
    let workspace_dir = openclaw_dir.join("workspace-dev");
    config["agents"]["defaults"]["workspace"] =
        serde_json::json!(workspace_dir.to_string_lossy().to_string());

    // ========== Gateway Configuration ==========
    // Ensure gateway section exists
    if config.get("gateway").is_none() {
        config["gateway"] = serde_json::json!({});
    }

    // Ensure gateway.auth section exists
    if config["gateway"].get("auth").is_none() {
        config["gateway"]["auth"] = serde_json::json!({});
    }

    // Set gateway auth mode to "token" with pre-generated token
    // Token is required for sharedAuthOk=true which allows skipping device identity
    // when dangerouslyDisableDeviceAuth=true is set
    config["gateway"]["auth"]["mode"] = serde_json::json!("token");
    config["gateway"]["auth"]["token"] = serde_json::json!(&gateway_token);
    config["gateway"]["auth"]["rateLimit"] = serde_json::json!({
        "maxAttempts": 1000,
        "windowMs": 60000,
        "lockoutMs": 1000,
        "exemptLoopback": true
    });

    // Configure controlUi for Tauri WebView
    // Note: Tauri apps load from file:// protocol in production, so include it in allowedOrigins
    config["gateway"]["controlUi"] = serde_json::json!({
        "allowedOrigins": ["*", "file://"],
        "dangerouslyDisableDeviceAuth": true
    });

    // Set gateway mode to local
    config["gateway"]["mode"] = serde_json::json!("local");
    config["gateway"]["bind"] = serde_json::json!("loopback");

    // Write config ONCE with all settings
    let content = serde_json::to_string_pretty(&config)
        .map_err(|e| format!("Failed to serialize config: {}", e))?;
    std::fs::write(&config_path, content)
        .map_err(|e| format!("Failed to write openclaw.json: {}", e))?;

    // DIAGNOSTIC: Verify written token
    let verify_content = std::fs::read_to_string(&config_path)
        .map_err(|e| format!("Failed to verify config: {}", e))?;
    let verify_config: serde_json::Value = serde_json::from_str(&verify_content)
        .map_err(|e| format!("Failed to parse verified config: {}", e))?;
    let written_token = verify_config
        .get("gateway")
        .and_then(|g| g.get("auth"))
        .and_then(|a| a.get("token"))
        .and_then(|t| t.as_str())
        .unwrap_or("NOT_FOUND");

    println!("[DIAGNOSTIC] configure_openclaw_complete:");
    println!("  Generated token: {}...", &gateway_token[..20.min(gateway_token.len())]);
    println!("  Written token:   {}...", &written_token[..20.min(written_token.len())]);
    println!("  Match: {}", if gateway_token == written_token { "YES" } else { "NO" });

    Ok(gateway_token)
}

/// Configure OpenClaw with LLM settings from SMAN settings
/// Returns Ok(false) if API key not configured (but still starts server)
fn configure_openclaw_llm(openclaw_dir: &PathBuf) -> Result<bool, String> {
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

    // If no API key, just create minimal config and return
    if api_key.is_empty() {
        println!("[Sidecar] Warning: No LLM API Key configured. Server will start but chat won't work.");

        // Ensure basic config exists
        if config.get("agents").is_none() {
            config["agents"] = serde_json::json!({
                "defaults": {
                    "identity": { "name": "SMAN Assistant" }
                }
            });
        }

        let content = serde_json::to_string_pretty(&config)
            .map_err(|e| format!("Failed to serialize config: {}", e))?;
        std::fs::write(&config_path, content)
            .map_err(|e| format!("Failed to write openclaw.json: {}", e))?;

        return Ok(false); // Started but not fully configured
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

    // Determine API type based on provider
    // OpenClaw supported types: openai-completions, openai-responses, anthropic-messages, etc.
    let api_type = if provider_id == "zhipu" || provider_id == "openai" {
        "openai-completions"  // OpenAI-compatible /chat/completions endpoint
    } else {
        "anthropic-messages"  // Anthropic native format
    };

    // Add provider config
    config["models"]["providers"][provider_id] = serde_json::json!({
        "baseUrl": api_url,
        "api": api_type,
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

    // CRITICAL: Write auth-profiles.json to agent directory
    // OpenClaw looks for this file at: .openclaw/agents/dev/agent/auth-profiles.json
    let agent_dir = openclaw_dir.join("agents").join("dev").join("agent");
    std::fs::create_dir_all(&agent_dir)
        .map_err(|e| format!("Failed to create agent dir: {}", e))?;

    let auth_profiles_path = agent_dir.join("auth-profiles.json");
    let auth_profiles = serde_json::json!({
        "profiles": {
            &profile_key: {
                "provider": provider_id,
                "mode": "api_key",
                "apiKey": api_key
            }
        },
        "defaultProfile": profile_key
    });

    let auth_content = serde_json::to_string_pretty(&auth_profiles)
        .map_err(|e| format!("Failed to serialize auth-profiles: {}", e))?;
    std::fs::write(&auth_profiles_path, auth_content)
        .map_err(|e| format!("Failed to write auth-profiles.json: {}", e))?;

    println!("[Sidecar] LLM configured: provider={}, model={}", provider_id, model);
    println!("[Sidecar] Auth profiles written to: {:?}", auth_profiles_path);

    Ok(true) // Fully configured
}

#[tauri::command]
pub async fn start_openclaw_server(app: tauri::AppHandle) -> Result<String, String> {
    if SERVER_RUNNING
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return Ok("OpenClaw server already running".to_string());
    }

    let start_result: Result<String, String> = (|| {
        // Get isolated directories
        let isolated_home = get_openclaw_isolated_home();
        let openclaw_dir = isolated_home.join(".openclaw");

        // Ensure directories exist
        std::fs::create_dir_all(&openclaw_dir)
            .map_err(|e| format!("Failed to create OpenClaw dir: {}", e))?;
        let preset_workspace_dirs = apply_workspace_presets(&openclaw_dir);
        println!("[Sidecar] Workspace presets applied to {:?}", preset_workspace_dirs);

        // Configure OpenClaw with LLM and Gateway settings
        // CRITICAL: Configure both in a single write to avoid race conditions
        let gateway_token = configure_openclaw_complete(&openclaw_dir)?;
        println!("[Sidecar] Pre-generated gateway token: {}...", &gateway_token[..20.min(gateway_token.len())]);

        // Get OpenClaw path - use sibling project path to access node_modules
        let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let sman_root = manifest_dir.parent().map(PathBuf::from).unwrap_or(manifest_dir.clone());
        let sibling_openclaw_dir = sman_root.parent().map(|parent| parent.join("openclaw"));
        let sibling_openclaw_mjs = sibling_openclaw_dir.as_ref().map(|d| d.join("openclaw.mjs"));

        // Always use sibling openclaw to have access to node_modules
        let openclaw_path = sibling_openclaw_mjs.clone().unwrap_or_else(|| {
            // Fallback to bundled if sibling doesn't exist (shouldn't happen in dev)
            get_openclaw_path(&app)
        });

        if let Some(ref sibling) = sibling_openclaw_dir {
            if sibling.exists() {
                println!("[Sidecar] Using sibling OpenClaw at {:?}", sibling);
            }
        }

        // Build command to run OpenClaw with node
        // Key: Override HOME to isolate OpenClaw's config directory
        // CRITICAL: Must pass --token to ensure Gateway uses the same token as WebSocket client.
        // This sets modeSource="override" and ensures consistent token across all Gateway components.
        // The token is also written to openclaw.json for persistence.
        let isolated_home_str = isolated_home.to_string_lossy().to_string();
        let node_command = get_node_command(&app);
        let mut command = app
            .shell()
            .command(&node_command)
            .args([
                openclaw_path.to_string_lossy().to_string(),
                "gateway".to_string(),
                "--port".to_string(),
                OPENCLAW_PORT.to_string(),
                "--allow-unconfigured".to_string(),
                "--dev".to_string(),  // Use dev mode for auto config
                "--token".to_string(),
                gateway_token.clone(),  // Explicitly pass token to ensure consistency
            ])
            .env("HOME", &isolated_home_str)  // Critical: isolate HOME
            .env("USERPROFILE", &isolated_home_str)  // Windows
            .env("OPENCLAW_HOME", &isolated_home_str)  // Explicitly set OpenClaw home
            .env("OPENCLAW_GATEWAY_TOKEN", &gateway_token)  // Also set in env for redundancy
            .env("NODE_PATH", sibling_openclaw_dir.clone().map(|d| d.join("node_modules").to_string_lossy().to_string()).unwrap_or_default())  // Point to node_modules
            .current_dir(sibling_openclaw_dir.as_ref().unwrap_or(&PathBuf::from(".")));  // Run from openclaw directory to find node_modules

        // Get WebSearch API keys from settings and add to environment
        let web_search_vars = crate::commands::settings::get_web_search_env_vars();
        for (key, value) in web_search_vars {
            command = command.env(&key, value);
        }

        // Get LLM API key from settings and add to environment
        let llm_vars = crate::commands::settings::get_llm_env_vars();
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

        // Wait for the server to be ready (port becomes available)
        println!("[Sidecar] Waiting for server to be ready on port {}...", OPENCLAW_PORT);
        let addr: SocketAddr = format!("127.0.0.1:{}", OPENCLAW_PORT)
            .parse()
            .map_err(|e: std::net::AddrParseError| e.to_string())?;

        let max_retries = 30; // 30 * 200ms = 6 seconds max
        let mut server_ready = false;
        for i in 0..max_retries {
            if TcpStream::connect_timeout(&addr, std::time::Duration::from_millis(100)).is_ok() {
                println!("[Sidecar] Server ready after {} attempts", i + 1);
                server_ready = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(200));
        }

        if !server_ready {
            {
                let mut child_lock = OPENCLAW_CHILD.lock().unwrap();
                if let Some(child) = child_lock.take() {
                    if let Err(e) = child.kill() {
                        eprintln!(
                            "[Sidecar] Failed to kill OpenClaw after startup timeout: {}",
                            e
                        );
                    }
                }
            }
            return Err(format!(
                "OpenClaw failed to listen on port {} within startup timeout",
                OPENCLAW_PORT
            ));
        }

        // Token was pre-generated before starting Gateway, no need to wait
        println!("[Sidecar] Gateway started with pre-configured token");
        Ok(format!(
            "OpenClaw server started on port {} with isolated home at {:?}",
            OPENCLAW_PORT, isolated_home
        ))
    })();

    if start_result.is_err() {
        SERVER_RUNNING.store(false, Ordering::SeqCst);
    }
    start_result
}

#[tauri::command]
pub async fn stop_openclaw_server() -> Result<String, String> {
    stop_openclaw_server_sync()
}

/// Sync version for use in window close handler
pub fn stop_openclaw_server_sync() -> Result<String, String> {
    if !SERVER_RUNNING.load(Ordering::SeqCst) {
        return Ok("OpenClaw server not running".to_string());
    }

    // Kill the child process
    {
        let mut child_lock = OPENCLAW_CHILD.lock().unwrap();
        if let Some(child) = child_lock.take() {
            if let Err(e) = child.kill() {
                eprintln!("[Sidecar] Failed to kill OpenClaw: {}", e);
            }
        }
    }

    SERVER_RUNNING.store(false, Ordering::SeqCst);
    println!("[Sidecar] OpenClaw server stopped");
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

/// Get the Gateway auth token from OpenClaw config
/// Uses SMAN's isolated OpenClaw config at ~/.smanlocal/openclaw-home/.openclaw/
#[tauri::command]
pub async fn get_gateway_token() -> Result<String, String> {
    // Use SMAN's isolated OpenClaw config directory
    let openclaw_dir = get_openclaw_isolated_home().join(".openclaw");
    let config_path = openclaw_dir.join("openclaw.json");

    if !config_path.exists() {
        return Err("OpenClaw config not found - server not started?".to_string());
    }

    // Read token from config (pre-generated by configure_openclaw_complete)
    let content = std::fs::read_to_string(&config_path)
        .map_err(|e| format!("Failed to read config: {}", e))?;

    let config: serde_json::Value = serde_json::from_str(&content)
        .map_err(|e| format!("Failed to parse config: {}", e))?;

    // Get token from gateway.auth.token
    if let Some(token) = config
        .get("gateway")
        .and_then(|g| g.get("auth"))
        .and_then(|a| a.get("token"))
        .and_then(|t| t.as_str())
    {
        println!("[DIAGNOSTIC] get_gateway_token:");
        println!("  Config file: {:?}", config_path);
        println!("  Token read: {}...", &token[..32.min(token.len())]);
        println!("  Token length: {}", token.len());
        println!("[Sidecar] Got gateway token from sidecar config: {}...", &token[..20.min(token.len())]);
        return Ok(token.to_string());
    }

    println!("[DIAGNOSTIC] get_gateway_token: TOKEN NOT FOUND in config");
    println!("  Config file: {:?}", config_path);
    println!("  Config content preview: {}", &content[..200.min(content.len())]);

    Err("Gateway token not found in sidecar config".to_string())
}
