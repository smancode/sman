//! Task executor for managing task execution lifecycle

use anyhow::Result;
use smanclaw_types::{ProgressEvent, TaskResult, TaskStatus};
use smanclaw_core::TaskManager;
use std::sync::Arc;
use tokio::sync::mpsc;

use crate::ZeroclawBridge;

/// Task executor handles the execution of tasks
pub struct TaskExecutor {
    task_manager: Arc<TaskManager>,
    bridge: Arc<ZeroclawBridge>,
}

impl TaskExecutor {
    /// Create a new task executor
    pub fn new(task_manager: Arc<TaskManager>, bridge: Arc<ZeroclawBridge>) -> Self {
        Self {
            task_manager,
            bridge,
        }
    }

    /// Execute a task and return progress events
    pub async fn execute(
        &self,
        task_id: &str,
        input: &str,
    ) -> Result<TaskResult> {
        // Update status to running
        self.task_manager
            .update_task_status(task_id, TaskStatus::Running)?;

        // Execute with the bridge
        let result = self.bridge.execute_task(input)?;

        // Update status based on result
        let status = if result.success {
            TaskStatus::Completed
        } else {
            TaskStatus::Failed
        };
        self.task_manager.update_task_status(task_id, status)?;

        Ok(result)
    }

    /// Execute a task with progress streaming
    pub async fn execute_with_progress(
        &self,
        task_id: &str,
        input: &str,
        event_tx: mpsc::Sender<ProgressEvent>,
    ) -> Result<TaskResult> {
        // Update status to running
        self.task_manager
            .update_task_status(task_id, TaskStatus::Running)?;

        // Execute with the bridge
        let result = self
            .bridge
            .execute_task_stream(task_id, input, event_tx)
            .await?;

        // Update status based on result
        let status = if result.success {
            TaskStatus::Completed
        } else {
            TaskStatus::Failed
        };
        self.task_manager.update_task_status(task_id, status)?;

        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use smanclaw_core::TaskManager;

    #[tokio::test]
    async fn execute_task() {
        let task_manager = Arc::new(TaskManager::in_memory().expect("create"));
        let bridge = Arc::new(
            ZeroclawBridge::from_project(std::path::Path::new("/tmp/test")).expect("create"),
        );

        // Create a task first
        let task = task_manager.create_task("proj-123", "Test input").expect("create");

        let executor = TaskExecutor::new(task_manager.clone(), bridge);
        let result = executor.execute(&task.id, "Test input").await.expect("execute");

        assert!(result.success);

        // Verify task status was updated
        let updated = task_manager.get_task(&task.id).expect("get").expect("exists");
        assert_eq!(updated.status, TaskStatus::Completed);
    }

    #[tokio::test]
    async fn execute_with_progress_sends_events() {
        let task_manager = Arc::new(TaskManager::in_memory().expect("create"));
        let bridge = Arc::new(
            ZeroclawBridge::from_project(std::path::Path::new("/tmp/test")).expect("create"),
        );

        let task = task_manager.create_task("proj-123", "Test").expect("create");

        let executor = TaskExecutor::new(task_manager.clone(), bridge);
        let (tx, mut rx) = mpsc::channel(32);

        // Execute directly without tokio::spawn (TaskManager is not Send)
        let result = executor
            .execute_with_progress(&task.id, "Test", tx)
            .await
            .expect("execute");

        let mut events = vec![];
        while let Some(event) = rx.recv().await {
            events.push(event);
        }

        assert!(result.success);
        assert!(!events.is_empty());
    }
}
