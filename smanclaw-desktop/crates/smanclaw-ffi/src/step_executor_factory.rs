use smanclaw_core::StepExecutor;
use smanclaw_types::AppSettings;
use std::path::Path;
use std::sync::Arc;

use crate::{ClaudeCodeStepExecutor, ZeroclawBridge, ZeroclawStepExecutor};

pub fn build_step_executor(
    project_path: &Path,
    settings: &AppSettings,
    shared_bridge: Option<Arc<ZeroclawBridge>>,
) -> anyhow::Result<Arc<dyn StepExecutor>> {
    let engine = std::env::var("SMANCLAW_STEP_EXECUTOR")
        .ok()
        .map(|value| value.trim().to_ascii_lowercase())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "auto".to_string());

    if matches!(engine.as_str(), "auto" | "claudecode" | "claude") {
        if let Some(executor) = ClaudeCodeStepExecutor::discover(project_path) {
            tracing::info!("Using ClaudeCode step executor");
            return Ok(Arc::new(executor));
        }
        if matches!(engine.as_str(), "claudecode" | "claude") {
            tracing::warn!("ClaudeCode step executor unavailable, falling back to ZeroClaw");
        }
    }

    let executor = if let Some(bridge) = shared_bridge {
        ZeroclawStepExecutor::from_bridge(bridge)
    } else {
        ZeroclawStepExecutor::with_settings(project_path, settings)?
    };
    Ok(Arc::new(executor))
}
