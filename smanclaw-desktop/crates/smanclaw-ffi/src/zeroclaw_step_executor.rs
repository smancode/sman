//! ZeroClaw-based Step Executor for SubClawExecutor
//!
//! This module provides a StepExecutor implementation that uses ZeroClaw
//! for actual task execution.

use async_trait::async_trait;
use smanclaw_core::StepExecutor;
use smanclaw_types::AppSettings;
use std::path::Path;
use std::sync::Arc;

use crate::ZeroclawBridge;

/// StepExecutor implementation using ZeroClaw
pub struct ZeroclawStepExecutor {
    bridge: Arc<ZeroclawBridge>,
}

impl ZeroclawStepExecutor {
    /// Create a new ZeroClaw step executor
    pub fn new(project_path: &Path) -> anyhow::Result<Self> {
        let bridge = Arc::new(ZeroclawBridge::from_project(project_path)?);
        Ok(Self { bridge })
    }

    /// Create a new ZeroClaw step executor with settings
    pub fn with_settings(project_path: &Path, settings: &AppSettings) -> anyhow::Result<Self> {
        let bridge = Arc::new(ZeroclawBridge::from_project_with_settings(project_path, settings)?);
        Ok(Self { bridge })
    }

    /// Create from an existing bridge
    pub fn from_bridge(bridge: Arc<ZeroclawBridge>) -> Self {
        Self { bridge }
    }
}

#[async_trait]
impl StepExecutor for ZeroclawStepExecutor {
    async fn execute(&self, prompt: &str) -> std::result::Result<String, String> {
        self.bridge
            .execute_task_async(prompt)
            .await
            .map(|result| result.output)
            .map_err(|e| e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn test_create_executor() {
        let temp_dir = TempDir::new().expect("temp dir");
        let executor = ZeroclawStepExecutor::new(temp_dir.path());
        // Should succeed even without real LLM config
        assert!(executor.is_ok());
    }

    #[test]
    fn test_create_executor_with_settings() {
        let temp_dir = TempDir::new().expect("temp dir");
        let settings = AppSettings::default();
        let executor = ZeroclawStepExecutor::with_settings(temp_dir.path(), &settings);
        assert!(executor.is_ok());
    }

    #[test]
    fn test_from_bridge() {
        let temp_dir = TempDir::new().expect("temp dir");
        let bridge = Arc::new(ZeroclawBridge::from_project(temp_dir.path()).expect("bridge"));
        let _executor = ZeroclawStepExecutor::from_bridge(bridge);
    }
}
