//! Application state management

use smanclaw_core::{ProjectManager, SettingsStore, SqliteHistoryStore, TaskManager};
use smanclaw_ffi::ZeroclawBridge;
use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Mutex;

use crate::error::TauriResult;

/// Application state shared across all Tauri commands
pub struct AppState {
    /// Project manager (wrapped in Mutex for thread safety)
    pub project_manager: Arc<Mutex<ProjectManager>>,
    /// Task manager (wrapped in Mutex for thread safety)
    pub task_manager: Arc<Mutex<TaskManager>>,
    /// History store (wrapped in Mutex for thread safety)
    pub history_store: Arc<Mutex<SqliteHistoryStore>>,
    /// Settings store (wrapped in Mutex for thread safety)
    pub settings_store: Arc<Mutex<SettingsStore>>,
    /// ZeroClaw bridges per project (project_id -> bridge)
    /// This maintains persistent Agent instances for multi-turn conversations
    pub zeroclaw_bridges: Arc<Mutex<HashMap<String, Arc<ZeroclawBridge>>>>,
    /// Configuration directory
    pub config_dir: PathBuf,
}

// Manually implement Send and Sync since we use Mutex for interior mutability
// This is safe because all access goes through the Mutex
unsafe impl Send for AppState {}
unsafe impl Sync for AppState {}

impl AppState {
    /// Create a new application state
    pub fn new(config_dir: PathBuf) -> TauriResult<Self> {
        // Ensure config directory exists
        std::fs::create_dir_all(&config_dir)?;

        // Initialize managers
        let project_manager = Arc::new(Mutex::new(ProjectManager::new(config_dir.clone())?));
        let task_manager = Arc::new(Mutex::new(TaskManager::new(&config_dir.join("tasks.db"))?));
        let history_store = Arc::new(Mutex::new(SqliteHistoryStore::new(
            &config_dir.join("history.db"),
        )?));
        let settings_store = Arc::new(Mutex::new(SettingsStore::new(config_dir.clone())?));
        let zeroclaw_bridges = Arc::new(Mutex::new(HashMap::new()));

        Ok(Self {
            project_manager,
            task_manager,
            history_store,
            settings_store,
            zeroclaw_bridges,
            config_dir,
        })
    }
}

/// Create default config directory
pub fn default_config_dir() -> PathBuf {
    if let Ok(dir) = std::env::var("SMANCLAW_DESKTOP_CONFIG_DIR") {
        let path = PathBuf::from(dir);
        if ensure_writable_dir(&path).is_ok() {
            return path;
        }
    }

    let preferred = dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("smanclaw-desktop");
    if ensure_writable_dir(&preferred).is_ok() {
        return preferred;
    }

    let current_dir_fallback = std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join(".smanclaw-desktop");
    if ensure_writable_dir(&current_dir_fallback).is_ok() {
        return current_dir_fallback;
    }

    let temp_fallback = std::env::temp_dir().join("smanclaw-desktop");
    if ensure_writable_dir(&temp_fallback).is_ok() {
        return temp_fallback;
    }

    PathBuf::from(".")
}

fn ensure_writable_dir(path: &PathBuf) -> std::io::Result<()> {
    fs::create_dir_all(path)?;
    let probe = path.join(".write_test");
    OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&probe)?;
    fs::remove_file(probe)?;
    Ok(())
}
