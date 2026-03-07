//! Runtime module for event-driven main loop
//!
//! This module provides the core runtime that drives the entire system
//! through an event-driven architecture.

use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, Sender};

use serde::{Deserialize, Serialize};

use crate::acceptance_evaluator::EvaluationResult;
use crate::error::{CoreError, Result};
use crate::orchestrator::{Orchestrator, OrchestratorState, SubTaskStatus, TaskDag};
use crate::sub_claw_executor::ExecutionResult;

/// Feedback stage for user notifications
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum FeedbackStage {
    /// User request received
    RequestReceived,
    /// Analyzing the request
    Analyzing,
    /// Task has been split into subtasks
    TaskSplit,
    /// Subtasks have been dispatched
    SubTaskDispatched,
    /// Progress update for a subtask
    SubTaskProgress { task_id: String, progress: f32 },
    /// Evaluation has started
    EvaluationStarted,
    /// Evaluation has completed
    EvaluationCompleted,
    /// Final result ready
    FinalResult,
    /// Error occurred
    Error,
}

/// Runtime event types
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum RuntimeEvent {
    /// User submitted a new request
    UserRequest { content: String },
    /// A subtask completed successfully
    SubTaskCompleted {
        task_id: String,
        result: ExecutionResult,
    },
    /// A subtask failed
    SubTaskFailed { task_id: String, error: String },
    /// Evaluation completed
    EvaluationCompleted { result: EvaluationResult },
    /// Task timeout
    Timeout { task_id: String },
    /// User feedback message
    UserFeedback { message: String },
}

/// Runtime state tracking
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RuntimeState {
    /// Runtime is idle, waiting for requests
    Idle,
    /// Runtime is processing a request
    Processing,
    /// Runtime is evaluating results
    Evaluating,
    /// Runtime is stopped
    Stopped,
}

impl Default for RuntimeState {
    fn default() -> Self {
        Self::Idle
    }
}

/// Runtime statistics
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct RuntimeStats {
    /// Total requests received
    pub requests_received: usize,
    /// Total subtasks completed
    pub subtasks_completed: usize,
    /// Total subtasks failed
    pub subtasks_failed: usize,
    /// Total evaluations completed
    pub evaluations_completed: usize,
    /// Total timeouts
    pub timeouts: usize,
}

/// Event-driven runtime for the system
pub struct Runtime {
    /// Path to the project being managed
    project_path: PathBuf,
    /// Orchestrator for task management
    orchestrator: Option<Orchestrator>,
    /// Current task DAG (for tracking)
    task_dag: Option<TaskDag>,
    /// Event receiver channel
    event_receiver: Receiver<RuntimeEvent>,
    /// Event sender channel
    event_sender: Sender<RuntimeEvent>,
    /// Optional feedback callback
    feedback_callback: Option<Box<dyn Fn(&str) + Send + Sync>>,
    /// Whether the runtime is running
    running: bool,
    /// Current state
    state: RuntimeState,
    /// Statistics
    stats: RuntimeStats,
}

impl Runtime {
    /// Create a new Runtime instance
    ///
    /// # Arguments
    /// * `project_path` - Path to the project directory
    ///
    /// # Returns
    /// A new Runtime instance
    pub fn new(project_path: &std::path::Path) -> Self {
        let (sender, receiver) = mpsc::channel();

        let orchestrator = Orchestrator::new(project_path).ok();

        Self {
            project_path: project_path.to_path_buf(),
            orchestrator,
            task_dag: None,
            event_receiver: receiver,
            event_sender: sender,
            feedback_callback: None,
            running: false,
            state: RuntimeState::default(),
            stats: RuntimeStats::default(),
        }
    }

    /// Set the feedback callback function
    ///
    /// # Arguments
    /// * `callback` - Function to call with feedback messages
    pub fn set_feedback_callback<F: Fn(&str) + Send + Sync + 'static>(&mut self, callback: F) {
        self.feedback_callback = Some(Box::new(callback));
    }

    /// Get a sender to send events to this runtime
    ///
    /// # Returns
    /// A clone of the event sender
    pub fn event_sender(&self) -> Sender<RuntimeEvent> {
        self.event_sender.clone()
    }

    /// Send an event to the runtime
    ///
    /// # Arguments
    /// * `event` - The event to send
    ///
    /// # Returns
    /// Ok(()) if sent successfully, error otherwise
    pub fn send_event(&self, event: RuntimeEvent) -> Result<()> {
        self.event_sender
            .send(event)
            .map_err(|e| CoreError::InvalidInput(format!("Failed to send event: {}", e)))
    }

    /// Main event loop
    ///
    /// Runs continuously, processing events until stopped
    pub async fn run(&mut self) -> Result<()> {
        self.running = true;
        self.state = RuntimeState::Idle;

        while self.running {
            match self.event_receiver.recv() {
                Ok(event) => {
                    if let Err(e) = self.handle_event(event).await {
                        self.send_feedback(&self.format_feedback(FeedbackStage::Error));
                        self.send_feedback(&format!("Error: {}", e));
                    }
                }
                Err(_) => {
                    // Channel closed, stop the runtime
                    self.running = false;
                }
            }
        }

        self.state = RuntimeState::Stopped;
        Ok(())
    }

    /// Stop the runtime
    pub fn stop(&mut self) {
        self.running = false;
        self.state = RuntimeState::Stopped;
    }

    /// Check if the runtime is running
    pub fn is_running(&self) -> bool {
        self.running
    }

    /// Get the current state
    pub fn state(&self) -> RuntimeState {
        self.state
    }

    /// Get runtime statistics
    pub fn stats(&self) -> &RuntimeStats {
        &self.stats
    }

    /// Handle a single event
    async fn handle_event(&mut self, event: RuntimeEvent) -> Result<()> {
        match event {
            RuntimeEvent::UserRequest { content } => {
                self.send_feedback(&self.format_feedback(FeedbackStage::RequestReceived));
                self.handle_user_request(&content).await?;
            }
            RuntimeEvent::SubTaskCompleted { task_id, result } => {
                self.send_feedback(&self.format_feedback(FeedbackStage::SubTaskProgress {
                    task_id: task_id.clone(),
                    progress: 1.0,
                }));
                self.handle_sub_task_completed(&task_id, result).await?;
            }
            RuntimeEvent::SubTaskFailed { task_id, error } => {
                self.send_feedback(&self.format_feedback(FeedbackStage::Error));
                self.handle_sub_task_failed(&task_id, &error).await?;
            }
            RuntimeEvent::EvaluationCompleted { result } => {
                self.send_feedback(&self.format_feedback(FeedbackStage::EvaluationCompleted));
                self.handle_evaluation(result).await?;
            }
            RuntimeEvent::Timeout { task_id } => {
                self.send_feedback(&self.format_feedback(FeedbackStage::Error));
                self.handle_timeout(&task_id).await?;
            }
            RuntimeEvent::UserFeedback { message } => {
                self.send_feedback(&message);
            }
        }
        Ok(())
    }

    /// Handle a user request
    async fn handle_user_request(&mut self, content: &str) -> Result<()> {
        self.state = RuntimeState::Processing;
        self.stats.requests_received += 1;

        // Analyze the request
        self.send_feedback(&self.format_feedback(FeedbackStage::Analyzing));

        // Parse the requirement and build a DAG using the static method
        let sub_tasks = Orchestrator::parse_requirement(content);
        let dag = TaskDag::from_tasks(sub_tasks)?;

        self.send_feedback(&self.format_feedback(FeedbackStage::TaskSplit));

        // Store the DAG
        self.task_dag = Some(dag);

        // Dispatch ready tasks
        self.send_feedback(&self.format_feedback(FeedbackStage::SubTaskDispatched));

        // For now, we just mark this as processing
        // In a real implementation, we would dispatch tasks to executors

        Ok(())
    }

    /// Handle a completed subtask
    async fn handle_sub_task_completed(
        &mut self,
        task_id: &str,
        result: ExecutionResult,
    ) -> Result<()> {
        self.stats.subtasks_completed += 1;

        // Update the DAG
        if let Some(ref mut dag) = self.task_dag {
            let result_str = if result.success {
                Some(format!(
                    "Completed {}/{} steps",
                    result.steps_completed, result.steps_total
                ))
            } else {
                result.error.clone()
            };
            dag.mark_completed(task_id, result_str)?;
        }

        Ok(())
    }

    /// Handle a failed subtask
    async fn handle_sub_task_failed(&mut self, task_id: &str, error: &str) -> Result<()> {
        self.stats.subtasks_failed += 1;

        // Update the DAG
        if let Some(ref mut dag) = self.task_dag {
            if let Some(task) = dag.get_task_mut(task_id) {
                task.status = SubTaskStatus::Failed;
                task.result = Some(error.to_string());
            }
        }

        Ok(())
    }

    /// Handle evaluation result
    async fn handle_evaluation(&mut self, result: EvaluationResult) -> Result<()> {
        self.state = RuntimeState::Evaluating;
        self.stats.evaluations_completed += 1;

        // Process the evaluation result
        if result.overall_passed {
            self.send_feedback(&self.format_feedback(FeedbackStage::FinalResult));
        } else {
            self.send_feedback(&format!(
                "Evaluation completed with {:.0}% passed",
                result.completion_percentage()
            ));
        }

        self.state = RuntimeState::Idle;
        Ok(())
    }

    /// Handle a timeout
    async fn handle_timeout(&mut self, task_id: &str) -> Result<()> {
        self.stats.timeouts += 1;

        // Mark the task as failed due to timeout
        if let Some(ref mut dag) = self.task_dag {
            if let Some(task) = dag.get_task_mut(task_id) {
                task.status = SubTaskStatus::Failed;
                task.result = Some("Task timed out".to_string());
            }
        }

        Ok(())
    }

    /// Send feedback to the callback
    fn send_feedback(&self, message: &str) {
        if let Some(ref callback) = self.feedback_callback {
            callback(message);
        }
    }

    /// Format a feedback message for a stage
    fn format_feedback(&self, stage: FeedbackStage) -> String {
        match stage {
            FeedbackStage::RequestReceived => "🎯 收到您的请求...".to_string(),
            FeedbackStage::Analyzing => "🔍 正在分析需求...".to_string(),
            FeedbackStage::TaskSplit => "📋 已拆分为子任务".to_string(),
            FeedbackStage::SubTaskDispatched => "🚀 子任务已派发".to_string(),
            FeedbackStage::SubTaskProgress { task_id, progress } => {
                format!("⏳ 子任务 {} 进度: {:.0}%", task_id, progress * 100.0)
            }
            FeedbackStage::EvaluationStarted => "✅ 开始验收评估...".to_string(),
            FeedbackStage::EvaluationCompleted => "📝 验收完成".to_string(),
            FeedbackStage::FinalResult => "🎉 任务完成!".to_string(),
            FeedbackStage::Error => "❌ 发生错误".to_string(),
        }
    }

    /// Get the project path
    pub fn project_path(&self) -> &std::path::Path {
        &self.project_path
    }

    /// Get the current task DAG
    pub fn task_dag(&self) -> Option<&TaskDag> {
        self.task_dag.as_ref()
    }

    /// Get the orchestrator state
    pub fn orchestrator_state(&self) -> Option<&OrchestratorState> {
        self.orchestrator.as_ref().map(|o| o.state())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};
    use tempfile::TempDir;

    fn create_runtime() -> (TempDir, Runtime) {
        let temp_dir = TempDir::new().expect("temp dir");
        let runtime = Runtime::new(temp_dir.path());
        (temp_dir, runtime)
    }

    // Test 1: Create Runtime
    #[test]
    fn test_runtime_creation() {
        let (_temp_dir, runtime) = create_runtime();
        assert!(!runtime.is_running());
        assert_eq!(runtime.state(), RuntimeState::Idle);
    }

    // Test 2: Send event
    #[test]
    fn test_send_event() {
        let (_temp_dir, runtime) = create_runtime();
        let event = RuntimeEvent::UserRequest {
            content: "test".to_string(),
        };
        let result = runtime.send_event(event);
        assert!(result.is_ok());
    }

    // Test 3: Get event sender
    #[test]
    fn test_get_event_sender() {
        let (_temp_dir, runtime) = create_runtime();
        let sender = runtime.event_sender();
        let event = RuntimeEvent::UserRequest {
            content: "test".to_string(),
        };
        let result = sender.send(event);
        assert!(result.is_ok());
    }

    // Test 4: Handle UserRequest event
    #[tokio::test]
    async fn test_handle_user_request() {
        let (_temp_dir, mut runtime) = create_runtime();
        let event = RuntimeEvent::UserRequest {
            content: "实现登录功能".to_string(),
        };
        let result = runtime.handle_event(event).await;
        assert!(result.is_ok());
        assert!(runtime.task_dag.is_some());
    }

    // Test 5: Handle SubTaskCompleted event
    #[tokio::test]
    async fn test_handle_sub_task_completed() {
        let (_temp_dir, mut runtime) = create_runtime();

        // First create a task DAG
        let event = RuntimeEvent::UserRequest {
            content: "实现登录功能".to_string(),
        };
        runtime.handle_event(event).await.unwrap();

        // Then complete a subtask
        let result = ExecutionResult::success(
            PathBuf::from("/test/task.md"),
            2,
            2,
            crate::sub_claw_executor::TaskExperience::new(),
        );
        let event = RuntimeEvent::SubTaskCompleted {
            task_id: "login-1".to_string(),
            result,
        };
        let result = runtime.handle_event(event).await;
        assert!(result.is_ok());
        assert_eq!(runtime.stats().subtasks_completed, 1);
    }

    // Test 6: Handle SubTaskFailed event
    #[tokio::test]
    async fn test_handle_sub_task_failed() {
        let (_temp_dir, mut runtime) = create_runtime();

        // First create a task DAG
        let event = RuntimeEvent::UserRequest {
            content: "实现登录功能".to_string(),
        };
        runtime.handle_event(event).await.unwrap();

        // Then fail a subtask
        let event = RuntimeEvent::SubTaskFailed {
            task_id: "login-1".to_string(),
            error: "Test error".to_string(),
        };
        let result = runtime.handle_event(event).await;
        assert!(result.is_ok());
        assert_eq!(runtime.stats().subtasks_failed, 1);
    }

    // Test 7: Handle EvaluationCompleted event
    #[tokio::test]
    async fn test_handle_evaluation_completed() {
        let (_temp_dir, mut runtime) = create_runtime();
        let eval_result = EvaluationResult::new("task-1".to_string());
        let event = RuntimeEvent::EvaluationCompleted {
            result: eval_result,
        };
        let result = runtime.handle_event(event).await;
        assert!(result.is_ok());
        assert_eq!(runtime.stats().evaluations_completed, 1);
    }

    // Test 8: Handle Timeout event
    #[tokio::test]
    async fn test_handle_timeout() {
        let (_temp_dir, mut runtime) = create_runtime();

        // First create a task DAG
        let event = RuntimeEvent::UserRequest {
            content: "实现登录功能".to_string(),
        };
        runtime.handle_event(event).await.unwrap();

        // Then timeout a subtask
        let event = RuntimeEvent::Timeout {
            task_id: "login-1".to_string(),
        };
        let result = runtime.handle_event(event).await;
        assert!(result.is_ok());
        assert_eq!(runtime.stats().timeouts, 1);
    }

    // Test 9: Feedback callback is called
    #[test]
    fn test_feedback_callback() {
        let (_temp_dir, mut runtime) = create_runtime();
        let messages = Arc::new(Mutex::new(Vec::<String>::new()));
        let messages_clone = messages.clone();

        runtime.set_feedback_callback(move |msg| {
            messages_clone.lock().unwrap().push(msg.to_string());
        });

        runtime.send_feedback("Test message");

        let msgs = messages.lock().unwrap();
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0], "Test message");
    }

    // Test 10: Feedback message format is correct
    #[test]
    fn test_feedback_message_format() {
        let (_temp_dir, runtime) = create_runtime();

        let msg = runtime.format_feedback(FeedbackStage::RequestReceived);
        assert!(msg.contains("收到"));

        let msg = runtime.format_feedback(FeedbackStage::Analyzing);
        assert!(msg.contains("分析"));

        let msg = runtime.format_feedback(FeedbackStage::TaskSplit);
        assert!(msg.contains("拆分"));

        let msg = runtime.format_feedback(FeedbackStage::SubTaskProgress {
            task_id: "task-1".to_string(),
            progress: 0.5,
        });
        assert!(msg.contains("task-1"));
        assert!(msg.contains("50%"));
    }

    // Test 11: Stop runtime
    #[test]
    fn test_stop_runtime() {
        let (_temp_dir, mut runtime) = create_runtime();
        assert!(!runtime.is_running());

        runtime.running = true;
        assert!(runtime.is_running());

        runtime.stop();
        assert!(!runtime.is_running());
        assert_eq!(runtime.state(), RuntimeState::Stopped);
    }

    // Test 12: State transitions are correct
    #[tokio::test]
    async fn test_state_transitions() {
        let (_temp_dir, mut runtime) = create_runtime();
        assert_eq!(runtime.state(), RuntimeState::Idle);

        // UserRequest should transition to Processing
        let event = RuntimeEvent::UserRequest {
            content: "test".to_string(),
        };
        runtime.handle_event(event).await.unwrap();
        assert_eq!(runtime.state(), RuntimeState::Processing);

        // EvaluationCompleted should transition back to Idle
        let eval_result = EvaluationResult::new("task-1".to_string());
        let event = RuntimeEvent::EvaluationCompleted {
            result: eval_result,
        };
        runtime.handle_event(event).await.unwrap();
        assert_eq!(runtime.state(), RuntimeState::Idle);
    }

    // Test 13: Multiple events sequential processing
    #[tokio::test]
    async fn test_multiple_events_sequential() {
        let (_temp_dir, mut runtime) = create_runtime();

        // Send multiple events
        let events = vec![
            RuntimeEvent::UserRequest {
                content: "实现登录".to_string(),
            },
            RuntimeEvent::SubTaskCompleted {
                task_id: "login-1".to_string(),
                result: ExecutionResult::success(
                    PathBuf::from("/test"),
                    1,
                    1,
                    crate::sub_claw_executor::TaskExperience::new(),
                ),
            },
            RuntimeEvent::EvaluationCompleted {
                result: EvaluationResult::new("main-1".to_string()),
            },
        ];

        for event in events {
            let result = runtime.handle_event(event).await;
            assert!(result.is_ok());
        }

        assert_eq!(runtime.stats().requests_received, 1);
        assert_eq!(runtime.stats().subtasks_completed, 1);
        assert_eq!(runtime.stats().evaluations_completed, 1);
    }

    // Test 14: Concurrent event sender
    #[test]
    fn test_concurrent_event_sender() {
        let (_temp_dir, runtime) = create_runtime();
        let sender1 = runtime.event_sender();
        let sender2 = runtime.event_sender();

        // Both senders should work
        let result1 = sender1.send(RuntimeEvent::UserRequest {
            content: "test1".to_string(),
        });
        let result2 = sender2.send(RuntimeEvent::UserRequest {
            content: "test2".to_string(),
        });

        assert!(result1.is_ok());
        assert!(result2.is_ok());
    }

    // Test 15: Error recovery - handle event with non-existent task
    #[tokio::test]
    async fn test_error_recovery_nonexistent_task() {
        let (_temp_dir, mut runtime) = create_runtime();

        // First create a task DAG
        let event = RuntimeEvent::UserRequest {
            content: "实现登录功能".to_string(),
        };
        runtime.handle_event(event).await.unwrap();

        // Try to complete a task that doesn't exist in the DAG
        let result = ExecutionResult::success(
            PathBuf::from("/test"),
            1,
            1,
            crate::sub_claw_executor::TaskExperience::new(),
        );
        let event = RuntimeEvent::SubTaskCompleted {
            task_id: "nonexistent-task-id".to_string(),
            result,
        };

        // This should fail because the task doesn't exist in the DAG
        let result = runtime.handle_event(event).await;
        assert!(result.is_err());
    }

    // Test 16: Runtime stats tracking
    #[tokio::test]
    async fn test_stats_tracking() {
        let (_temp_dir, mut runtime) = create_runtime();

        // Initial stats should be zero
        let stats = runtime.stats();
        assert_eq!(stats.requests_received, 0);
        assert_eq!(stats.subtasks_completed, 0);
        assert_eq!(stats.subtasks_failed, 0);
        assert_eq!(stats.evaluations_completed, 0);
        assert_eq!(stats.timeouts, 0);

        // Process some events
        runtime
            .handle_event(RuntimeEvent::UserRequest {
                content: "test".to_string(),
            })
            .await
            .unwrap();

        assert_eq!(runtime.stats().requests_received, 1);
    }

    // Test 17: UserFeedback event
    #[tokio::test]
    async fn test_user_feedback_event() {
        let (_temp_dir, mut runtime) = create_runtime();
        let messages = Arc::new(Mutex::new(Vec::<String>::new()));
        let messages_clone = messages.clone();

        runtime.set_feedback_callback(move |msg| {
            messages_clone.lock().unwrap().push(msg.to_string());
        });

        let event = RuntimeEvent::UserFeedback {
            message: "Custom feedback".to_string(),
        };
        let result = runtime.handle_event(event).await;
        assert!(result.is_ok());

        let msgs = messages.lock().unwrap();
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0], "Custom feedback");
    }

    // Test 18: Project path access
    #[test]
    fn test_project_path_access() {
        let (temp_dir, runtime) = create_runtime();
        assert_eq!(runtime.project_path(), temp_dir.path());
    }

    // Test 19: TaskDag access after request
    #[tokio::test]
    async fn test_task_dag_access() {
        let (_temp_dir, mut runtime) = create_runtime();

        // Initially no DAG
        assert!(runtime.task_dag().is_none());

        // After request, DAG should exist
        runtime
            .handle_event(RuntimeEvent::UserRequest {
                content: "实现登录".to_string(),
            })
            .await
            .unwrap();

        assert!(runtime.task_dag().is_some());
        let dag = runtime.task_dag().unwrap();
        assert!(!dag.is_empty());
    }

    // Test 20: Format all feedback stages
    #[test]
    fn test_format_all_feedback_stages() {
        let (_temp_dir, runtime) = create_runtime();

        // Test all stages have emoji
        let stages = vec![
            FeedbackStage::RequestReceived,
            FeedbackStage::Analyzing,
            FeedbackStage::TaskSplit,
            FeedbackStage::SubTaskDispatched,
            FeedbackStage::SubTaskProgress {
                task_id: "t1".to_string(),
                progress: 0.5,
            },
            FeedbackStage::EvaluationStarted,
            FeedbackStage::EvaluationCompleted,
            FeedbackStage::FinalResult,
            FeedbackStage::Error,
        ];

        for stage in stages {
            let msg = runtime.format_feedback(stage.clone());
            assert!(
                !msg.is_empty(),
                "Message for {:?} should not be empty",
                stage
            );
        }
    }
}
