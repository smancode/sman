//! ZeroClaw bridge for task execution

use anyhow::Result;
use smanclaw_types::{FileAction, FileChange, ProgressEvent, TaskResult};
use std::path::Path;
use tokio::sync::mpsc;

/// Bridge to ZeroClaw agent
pub struct ZeroclawBridge {
    project_path: std::path::PathBuf,
}

impl ZeroclawBridge {
    /// Create a bridge for a specific project
    pub fn from_project(project_path: &Path) -> Result<Self> {
        Ok(Self {
            project_path: project_path.to_path_buf(),
        })
    }

    /// Execute a task synchronously
    pub fn execute_task(&self, input: &str) -> Result<TaskResult> {
        // TODO: Integrate with zeroclaw::Agent
        // For now, return a placeholder result
        Ok(TaskResult {
            task_id: uuid::Uuid::new_v4().to_string(),
            success: true,
            output: format!("Executed: {}", input),
            error: None,
            files_changed: vec![],
        })
    }

    /// Execute a task with progress events
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

        // TODO: Integrate with zeroclaw::Agent
        // For now, simulate progress
        for i in 0..=10 {
            event_tx
                .send(ProgressEvent::Progress {
                    message: format!("Processing step {}...", i),
                    percent: i as f32 / 10.0,
                })
                .await?;
        }

        let result = TaskResult {
            task_id: task_id.to_string(),
            success: true,
            output: format!("Executed: {}", input),
            error: None,
            files_changed: vec![FileChange {
                path: "src/main.rs".to_string(),
                action: FileAction::Modified,
            }],
        };

        // Send completion event
        event_tx
            .send(ProgressEvent::TaskCompleted {
                result: result.clone(),
            })
            .await?;

        Ok(result)
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
    fn execute_task_returns_result() {
        let bridge = ZeroclawBridge::from_project(Path::new("/tmp/test")).expect("create");
        let result = bridge.execute_task("Test task").expect("execute");

        assert!(result.success);
        assert!(result.output.contains("Test task"));
    }

    #[tokio::test]
    async fn execute_task_stream_sends_events() {
        let bridge = ZeroclawBridge::from_project(Path::new("/tmp/test")).expect("create");
        let (tx, mut rx) = mpsc::channel(32);

        let handle = tokio::spawn(async move {
            bridge
                .execute_task_stream("task-123", "Test", tx)
                .await
                .expect("execute")
        });

        let mut events = vec![];
        while let Some(event) = rx.recv().await {
            events.push(event);
        }

        let result = handle.await.expect("join");

        // Should have TaskStarted, Progress events, and TaskCompleted
        assert!(!events.is_empty());
        assert!(result.success);
    }
}
