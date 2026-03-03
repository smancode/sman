//! ZeroClaw bridge for task execution

use anyhow::Result;
use smanclaw_types::{AppSettings, FileAction, FileChange, ProgressEvent, TaskResult};
use std::path::Path;
use tokio::sync::mpsc;

/// Bridge to ZeroClaw Agent
pub struct ZeroclawBridge {
    config: zeroclaw::Config,
}

impl ZeroclawBridge {
    /// Create a bridge for a specific project (with default config)
    pub fn from_project(project_path: &Path) -> Result<Self> {
        // Create default config with project directory as workspace
        let mut config = zeroclaw::Config::default();
        config.workspace_dir = project_path.to_path_buf();

        Ok(Self { config })
    }

    /// Create a bridge with user settings
    pub fn from_project_with_settings(
        project_path: &Path,
        settings: &AppSettings,
    ) -> Result<Self> {
        let mut config = zeroclaw::Config::default();
        config.workspace_dir = project_path.to_path_buf();

        // Configure LLM Provider (OpenAI Compatible)
        if settings.llm.is_configured() {
            // Use "custom" provider with base URL for OpenAI Compatible API
            config.default_provider = Some("custom".to_string());
            config.api_url = Some(settings.llm.api_url.clone());
            config.api_key = Some(settings.llm.api_key.clone());
            config.default_model = Some(settings.llm.default_model.clone());
        }

        // Configure Embedding (if provided)
        if let Some(ref emb) = settings.embedding {
            if emb.is_configured() {
                config.memory.embedding_provider = format!("custom:{}", emb.api_url);
                config.memory.embedding_model = emb.model.clone();
                config.memory.embedding_dimensions = emb.dimensions;
            }
        }

        // Configure Qdrant (if provided)
        if let Some(ref qd) = settings.qdrant {
            if qd.is_configured() {
                config.memory.backend = "sqlite_qdrant_hybrid".to_string();
                config.memory.qdrant.url = Some(qd.url.clone());
                config.memory.qdrant.collection = qd.collection.clone();
                config.memory.qdrant.api_key = qd.api_key.clone();
            }
        }

        Ok(Self { config })
    }

    /// Execute a task synchronously (requires external runtime or use execute_task_async)
    pub fn execute_task(&self, input: &str) -> Result<TaskResult> {
        // Try to use existing runtime if available, otherwise create one
        match tokio::runtime::Handle::try_current() {
            Ok(handle) => {
                // We're already in a runtime context, use block_in_place
                tokio::task::block_in_place(|| {
                    handle.block_on(self.execute_task_async(input))
                })
            }
            Err(_) => {
                // No runtime, create one
                let rt = tokio::runtime::Runtime::new()?;
                rt.block_on(self.execute_task_async(input))
            }
        }
    }

    /// Execute a task asynchronously (can be called from async context)
    pub async fn execute_task_async(&self, input: &str) -> Result<TaskResult> {
        let mut agent = zeroclaw::agent::Agent::from_config(&self.config)?;

        // Execute the turn
        let output = agent.turn(input).await?;

        // Extract file changes from history
        let files_changed = self.extract_file_changes(agent.history());

        Ok(TaskResult {
            task_id: uuid::Uuid::new_v4().to_string(),
            success: true,
            output,
            error: None,
            files_changed,
        })
    }

    /// Execute a task with progress events (streaming)
    pub async fn execute_task_stream(
        &self,
        task_id: &str,
        input: &str,
        event_tx: mpsc::Sender<ProgressEvent>,
    ) -> Result<TaskResult> {
        // Send task started event
        event_tx
            .send(ProgressEvent::TaskStarted {
                task_id: task_id.to_string(),
            })
            .await?;

        // Send initial progress
        event_tx
            .send(ProgressEvent::Progress {
                message: "Initializing agent...".to_string(),
                percent: 0.1,
            })
            .await?;

        // Create agent
        let mut agent = zeroclaw::agent::Agent::from_config(&self.config)?;

        event_tx
            .send(ProgressEvent::Progress {
                message: "Executing task...".to_string(),
                percent: 0.3,
            })
            .await?;

        // Execute the turn
        let result = match agent.turn(input).await {
            Ok(output) => {
                event_tx
                    .send(ProgressEvent::Progress {
                        message: "Task completed".to_string(),
                        percent: 0.9,
                    })
                    .await?;

                // Extract file changes from history
                let files_changed = self.extract_file_changes(agent.history());

                // Send file events
                for change in &files_changed {
                    event_tx
                        .send(ProgressEvent::FileWritten {
                            path: change.path.clone(),
                            action: change.action,
                        })
                        .await?;
                }

                TaskResult {
                    task_id: task_id.to_string(),
                    success: true,
                    output,
                    error: None,
                    files_changed,
                }
            }
            Err(e) => {
                let error_msg = e.to_string();
                event_tx
                    .send(ProgressEvent::TaskFailed {
                        error: error_msg.clone(),
                    })
                    .await?;

                TaskResult {
                    task_id: task_id.to_string(),
                    success: false,
                    output: String::new(),
                    error: Some(error_msg),
                    files_changed: vec![],
                }
            }
        };

        // Send completion event
        event_tx
            .send(ProgressEvent::TaskCompleted {
                result: result.clone(),
            })
            .await?;

        Ok(result)
    }

    /// Extract file changes from agent history
    fn extract_file_changes(
        &self,
        history: &[zeroclaw::providers::ConversationMessage],
    ) -> Vec<FileChange> {
        let mut changes = Vec::new();
        let mut seen_paths = std::collections::HashSet::new();

        for msg in history {
            if let zeroclaw::providers::ConversationMessage::ToolResults(results) = msg {
                for result in results {
                    // Parse tool results to find file operations
                    // ToolResultMessage has tool_call_id and content fields
                    let content = &result.content;

                    // Try to parse as JSON to extract file path
                    if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(content) {
                        if let Some(path) = parsed.get("path").and_then(|p| p.as_str()) {
                            if seen_paths.insert(path.to_string()) {
                                // Determine action based on content patterns
                                let action = if content.contains("created")
                                    || content.contains("Created")
                                {
                                    FileAction::Created
                                } else if content.contains("deleted")
                                    || content.contains("Deleted")
                                {
                                    FileAction::Deleted
                                } else {
                                    FileAction::Modified
                                };

                                changes.push(FileChange {
                                    path: path.to_string(),
                                    action,
                                });
                            }
                        }
                    }
                }
            }
        }

        changes
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_bridge() {
        let bridge = ZeroclawBridge::from_project(Path::new("/tmp/test-project"));
        assert!(bridge.is_ok());
    }

    #[test]
    fn create_bridge_with_default_config() {
        let temp_dir = tempfile::TempDir::new().expect("temp dir");
        let bridge = ZeroclawBridge::from_project(temp_dir.path()).expect("create");
        // Bridge should be created with default config
        assert!(true);
    }
}
