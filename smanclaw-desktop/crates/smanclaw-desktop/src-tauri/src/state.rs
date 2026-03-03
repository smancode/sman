//! Application state management

use smanclaw_core::{ProjectManager, SqliteHistoryStore, TaskManager};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Mutex;

use crate::error::{TauriError, TauriResult};

/// Application state shared across all Tauri commands
pub struct AppState {
    /// Project manager (wrapped in Mutex for thread safety)
    pub project_manager: Arc<Mutex<ProjectManager>>,
    /// Task manager (wrapped in Mutex for thread safety)
    pub task_manager: Arc<Mutex<TaskManager>>,
    /// History store (wrapped in Mutex for thread safety)
    pub history_store: Arc<Mutex<SqliteHistoryStore>>,
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
        let history_store = Arc::new(Mutex::new(SqliteHistoryStore::new(&config_dir.join("history.db"))?));

        Ok(Self {
            project_manager,
            task_manager,
            history_store,
            config_dir,
        })
    }
}

/// Create default config directory
pub fn default_config_dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("smanclaw-desktop")
}
