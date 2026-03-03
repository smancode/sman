//! Application configuration

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Application configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    /// Data directory for storing databases
    pub data_dir: PathBuf,
    /// Theme preference
    pub theme: Theme,
    /// Auto-save interval in seconds
    pub auto_save_interval: u64,
}

/// Theme preference
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
pub enum Theme {
    /// System default
    #[default]
    System,
    /// Light theme
    Light,
    /// Dark theme
    Dark,
}

impl Default for AppConfig {
    fn default() -> Self {
        let data_dir = dirs::data_local_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("smanclaw-desktop");

        Self {
            data_dir,
            theme: Theme::default(),
            auto_save_interval: 30,
        }
    }
}

impl AppConfig {
    /// Create config with custom data directory
    pub fn with_data_dir(data_dir: PathBuf) -> Self {
        Self {
            data_dir,
            ..Self::default()
        }
    }

    /// Get database path
    pub fn db_path(&self) -> PathBuf {
        self.data_dir.join("smanclaw.db")
    }
}
