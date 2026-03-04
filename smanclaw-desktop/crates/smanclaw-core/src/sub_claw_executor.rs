//! Sub Claw Executor for parallel task execution
//!
//! Provides execution layer for subtasks with parallel execution and automatic test running.

use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;

use crate::error::{CoreError, Result};
use crate::orchestrator::{SubTask, SubTaskId};

/// Result of a test execution
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TestResult {
    pub passed: bool,
    pub output: String,
    pub tests_run: Option<usize>,
    pub tests_passed: Option<usize>,
}

impl TestResult {
    pub fn success(output: String) -> Self {
        Self {
            passed: true,
            output,
            tests_run: None,
            tests_passed: None,
        }
    }

    pub fn failure(output: String) -> Self {
        Self {
            passed: false,
            output,
            tests_run: None,
            tests_passed: None,
        }
    }
}

/// Result of executing a subtask
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubTaskResult {
    pub task_id: SubTaskId,
    pub success: bool,
    pub output: String,
    pub error: Option<String>,
    pub test_result: Option<TestResult>,
    pub files_changed: Vec<String>,
}

impl SubTaskResult {
    pub fn success(task_id: SubTaskId, output: String) -> Self {
        Self {
            task_id,
            success: true,
            output,
            error: None,
            test_result: None,
            files_changed: Vec::new(),
        }
    }

    pub fn failure(task_id: SubTaskId, error: String) -> Self {
        Self {
            task_id,
            success: false,
            output: String::new(),
            error: Some(error),
            test_result: None,
            files_changed: Vec::new(),
        }
    }

    pub fn with_test(mut self, test_result: TestResult) -> Self {
        self.test_result = Some(test_result);
        self
    }
    pub fn with_files(mut self, files: Vec<String>) -> Self {
        self.files_changed = files;
        self
    }
}

/// Executor trait for running subtasks
#[async_trait::async_trait]
pub trait TaskRunner: Send + Sync {
    async fn execute(&self, task: &SubTask, project_path: &Path) -> Result<SubTaskResult>;
}

/// Default mock runner for testing
pub struct MockTaskRunner {
    fail_tasks: Vec<SubTaskId>,
}

impl MockTaskRunner {
    pub fn new() -> Self {
        Self {
            fail_tasks: Vec::new(),
        }
    }
    pub fn with_failures(fail_tasks: Vec<SubTaskId>) -> Self {
        Self { fail_tasks }
    }
}

impl Default for MockTaskRunner {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait::async_trait]
impl TaskRunner for MockTaskRunner {
    async fn execute(&self, task: &SubTask, _project_path: &Path) -> Result<SubTaskResult> {
        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
        if self.fail_tasks.contains(&task.id) {
            return Ok(SubTaskResult::failure(
                task.id.clone(),
                format!("Mock failure for task {}", task.id),
            ));
        }
        Ok(SubTaskResult::success(
            task.id.clone(),
            format!("Mock execution completed: {}", task.description),
        ))
    }
}

/// Events emitted during execution
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ExecutorEvent {
    TaskStarted { task_id: SubTaskId },
    TaskCompleted { result: SubTaskResult },
    AllCompleted { results: Vec<SubTaskResult> },
    Error { message: String },
}

/// Sub Claw Executor for managing parallel task execution
pub struct SubClawExecutor {
    runner: Arc<dyn TaskRunner>,
    project_path: PathBuf,
    event_tx: Option<mpsc::Sender<ExecutorEvent>>,
}

impl SubClawExecutor {
    pub fn new(runner: Arc<dyn TaskRunner>, project_path: PathBuf) -> Self {
        Self {
            runner,
            project_path,
            event_tx: None,
        }
    }

    pub fn with_mock(project_path: PathBuf) -> Self {
        Self::new(Arc::new(MockTaskRunner::new()), project_path)
    }

    pub fn with_event_channel(mut self, tx: mpsc::Sender<ExecutorEvent>) -> Self {
        self.event_tx = Some(tx);
        self
    }

    /// Execute a single subtask
    pub async fn execute_task(&self, task: &SubTask) -> Result<SubTaskResult> {
        if let Some(ref tx) = self.event_tx {
            let _ = tx
                .send(ExecutorEvent::TaskStarted {
                    task_id: task.id.clone(),
                })
                .await;
        }
        let result = self.runner.execute(task, &self.project_path).await?;
        let result = if let Some(ref test_cmd) = task.test_command {
            result.with_test(self.run_test(test_cmd).await)
        } else {
            result
        };
        if let Some(ref tx) = self.event_tx {
            let _ = tx
                .send(ExecutorEvent::TaskCompleted {
                    result: result.clone(),
                })
                .await;
        }
        Ok(result)
    }

    /// Execute multiple tasks in parallel
    pub async fn execute_parallel(&self, tasks: &[SubTask]) -> Result<Vec<SubTaskResult>> {
        let handles: Vec<_> = tasks
            .iter()
            .map(|task| {
                let task = task.clone();
                let runner = Arc::clone(&self.runner);
                let project_path = self.project_path.clone();
                let event_tx = self.event_tx.clone();
                tokio::spawn(async move {
                    if let Some(ref tx) = event_tx {
                        let _ = tx
                            .send(ExecutorEvent::TaskStarted {
                                task_id: task.id.clone(),
                            })
                            .await;
                    }
                    let result = runner.execute(&task, &project_path).await?;
                    let result = if let Some(ref test_cmd) = task.test_command {
                        result.with_test(run_test_sync(test_cmd, &project_path))
                    } else {
                        result
                    };
                    if let Some(ref tx) = event_tx {
                        let _ = tx
                            .send(ExecutorEvent::TaskCompleted {
                                result: result.clone(),
                            })
                            .await;
                    }
                    Ok::<_, CoreError>(result)
                })
            })
            .collect();

        let mut results = Vec::with_capacity(handles.len());
        for handle in handles {
            results.push(
                handle
                    .await
                    .map_err(|e| CoreError::InvalidInput(e.to_string()))??,
            );
        }
        if let Some(ref tx) = self.event_tx {
            let _ = tx
                .send(ExecutorEvent::AllCompleted {
                    results: results.clone(),
                })
                .await;
        }
        Ok(results)
    }

    pub async fn run_test(&self, test_command: &str) -> TestResult {
        let cmd = test_command.to_string();
        let path = self.project_path.clone();
        tokio::task::spawn_blocking(move || run_test_sync(&cmd, &path))
            .await
            .unwrap_or_else(|_| TestResult::failure("Failed to spawn test task".to_string()))
    }
}

fn run_test_sync(test_command: &str, project_path: &Path) -> TestResult {
    let parts: Vec<&str> = test_command.split_whitespace().collect();
    if parts.is_empty() {
        return TestResult::failure("Empty test command".to_string());
    }
    let output = match Command::new(parts[0])
        .args(&parts[1..])
        .current_dir(project_path)
        .output()
    {
        Ok(o) => o,
        Err(e) => return TestResult::failure(format!("Failed to run command: {}", e)),
    };
    let combined = format!(
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let (tests_run, tests_passed) = parse_test_counts(&combined);
    TestResult {
        passed: output.status.success(),
        output: combined,
        tests_run,
        tests_passed,
    }
}

fn parse_test_counts(output: &str) -> (Option<usize>, Option<usize>) {
    for line in output.lines() {
        if line.contains("test result:") {
            if let Some(passed_start) = line.find("passed") {
                if let Some(num) = line[..passed_start].split_whitespace().last() {
                    if let Ok(count) = num.parse::<usize>() {
                        return (Some(count), Some(count));
                    }
                }
            }
        }
    }
    (None, None)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn task(id: &str, desc: &str) -> SubTask {
        SubTask::new(id, desc)
    }

    #[tokio::test]
    async fn test_execute_single_task() {
        let executor = SubClawExecutor::with_mock(PathBuf::from("/tmp/test"));
        let result = executor
            .execute_task(&task("task-1", "Test task"))
            .await
            .unwrap();
        assert!(result.success);
        assert_eq!(result.task_id, "task-1");
    }

    #[tokio::test]
    async fn test_execute_parallel_tasks() {
        let executor = SubClawExecutor::with_mock(PathBuf::from("/tmp/test"));
        let tasks = vec![task("t1", "a"), task("t2", "b"), task("t3", "c")];
        let results = executor.execute_parallel(&tasks).await.unwrap();
        assert_eq!(results.len(), 3);
        assert!(results.iter().all(|r| r.success));
    }

    #[tokio::test]
    async fn test_execute_with_event_channel() {
        let (tx, mut rx) = mpsc::channel(10);
        let executor =
            SubClawExecutor::with_mock(PathBuf::from("/tmp/test")).with_event_channel(tx);
        let _ = executor.execute_task(&task("task-1", "Test")).await;
        assert!(matches!(
            rx.try_recv().unwrap(),
            ExecutorEvent::TaskStarted { .. }
        ));
        assert!(matches!(
            rx.try_recv().unwrap(),
            ExecutorEvent::TaskCompleted { .. }
        ));
    }

    #[tokio::test]
    async fn test_mock_runner_failure() {
        let runner = Arc::new(MockTaskRunner::with_failures(vec!["fail-1".into()]));
        let executor = SubClawExecutor::new(runner, PathBuf::from("/tmp/test"));
        let r1 = executor.execute_task(&task("ok-1", "ok")).await.unwrap();
        let r2 = executor
            .execute_task(&task("fail-1", "fail"))
            .await
            .unwrap();
        assert!(r1.success);
        assert!(!r2.success);
    }

    #[test]
    fn test_parse_test_counts() {
        let (run, passed) = parse_test_counts("test result: ok. 5 passed; 0 failed");
        assert_eq!(run, Some(5));
        assert_eq!(passed, Some(5));
    }

    #[test]
    fn test_sub_task_result_builder() {
        let result = SubTaskResult::success("t1".into(), "Done".into())
            .with_test(TestResult::success("ok".into()))
            .with_files(vec!["src/main.rs".into()]);
        assert!(result.success);
        assert!(result.test_result.is_some());
        assert_eq!(result.files_changed.len(), 1);
    }
}
