//! Task Poller for monitoring task.md file completion status
//!
//! Provides polling mechanism to check checkbox status in markdown task files.

use std::path::{Path, PathBuf};
use std::time::Duration;

use futures::stream::Stream;

use crate::error::{CoreError, Result};

/// Task polling status
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskPollStatus {
    /// No items completed yet
    Pending,
    /// Some items completed, but not all
    InProgress,
    /// All items completed
    Completed,
    /// Task failed (error or invalid state)
    Failed,
}

impl TaskPollStatus {
    /// Determine status from completion ratio
    pub fn from_completion(total: usize, completed: usize) -> Self {
        if total == 0 {
            return Self::Pending;
        }
        match completed {
            0 => Self::Pending,
            n if n >= total => Self::Completed,
            _ => Self::InProgress,
        }
    }
}

/// Task completion status for a single task
#[derive(Debug, Clone)]
pub struct TaskCompletionStatus {
    /// Task identifier
    pub task_id: String,
    /// Total number of checkbox items
    pub total_items: usize,
    /// Number of completed checkbox items
    pub completed_items: usize,
    /// Current status
    pub status: TaskPollStatus,
}

impl TaskCompletionStatus {
    /// Create a new completion status
    pub fn new(task_id: String, total_items: usize, completed_items: usize) -> Self {
        let status = TaskPollStatus::from_completion(total_items, completed_items);
        Self {
            task_id,
            total_items,
            completed_items,
            status,
        }
    }

    /// Check if task is fully completed
    pub fn is_completed(&self) -> bool {
        self.status == TaskPollStatus::Completed
    }

    /// Get completion percentage (0-100)
    pub fn percentage(&self) -> u8 {
        if self.total_items == 0 {
            return 0;
        }
        ((self.completed_items * 100) / self.total_items) as u8
    }
}

/// Result of polling multiple tasks
#[derive(Debug, Clone)]
pub struct PollResult {
    /// Whether all tasks are completed
    pub all_completed: bool,
    /// Status of each task
    pub statuses: Vec<TaskCompletionStatus>,
    /// Whether the poll timed out
    pub timed_out: bool,
}

impl PollResult {
    /// Create a successful poll result
    pub fn completed(statuses: Vec<TaskCompletionStatus>) -> Self {
        let all_completed = statuses.iter().all(|s| s.is_completed());
        Self {
            all_completed,
            statuses,
            timed_out: false,
        }
    }

    /// Create a timed out poll result
    pub fn timeout(statuses: Vec<TaskCompletionStatus>) -> Self {
        let all_completed = statuses.iter().all(|s| s.is_completed());
        Self {
            all_completed,
            statuses,
            timed_out: true,
        }
    }
}

/// Parsed checkbox item from markdown
#[derive(Debug, Clone)]
struct CheckboxItem {
    checked: bool,
    #[allow(dead_code)]
    content: String,
}

/// Parse checkbox items from markdown content
fn parse_checkboxes(content: &str) -> Vec<CheckboxItem> {
    content
        .lines()
        .filter_map(|line| {
            let trimmed = line.trim();
            // Match both "- [ ]" and "- [x]" patterns (case insensitive for x)
            if trimmed.starts_with("- [ ]") {
                Some(CheckboxItem {
                    checked: false,
                    content: trimmed[5..].trim().to_string(),
                })
            } else if trimmed.starts_with("- [x]") || trimmed.starts_with("- [X]") {
                Some(CheckboxItem {
                    checked: true,
                    content: trimmed[5..].trim().to_string(),
                })
            } else {
                None
            }
        })
        .collect()
}

/// Count completed and total checkboxes
fn count_checkboxes(content: &str) -> (usize, usize) {
    let items = parse_checkboxes(content);
    let total = items.len();
    let completed = items.iter().filter(|i| i.checked).count();
    (completed, total)
}

/// Task Poller for monitoring task.md files
pub struct TaskPoller {
    /// Root directory containing tasks
    tasks_dir: PathBuf,
    /// Polling interval for async operations
    poll_interval: Duration,
}

impl TaskPoller {
    /// Create a new TaskPoller
    ///
    /// # Arguments
    /// * `project_path` - Path to the project root
    /// * `poll_interval` - Interval between polls in async operations
    ///
    /// # Returns
    /// A new TaskPoller instance
    ///
    /// # Errors
    /// Returns error if the tasks directory cannot be determined
    pub fn new(project_path: &Path, poll_interval: Duration) -> Result<Self> {
        let tasks_dir = project_path.join(".sman").join("tasks");
        Ok(Self {
            tasks_dir,
            poll_interval,
        })
    }

    /// Get the path to a task file
    fn task_file_path(&self, task_id: &str) -> PathBuf {
        self.tasks_dir.join(task_id).join("task.md")
    }

    /// Read and parse a task file
    fn read_task_file(&self, task_id: &str) -> Result<String> {
        let path = self.task_file_path(task_id);
        std::fs::read_to_string(&path).map_err(|e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                CoreError::TaskFileNotFound(path.display().to_string())
            } else {
                CoreError::Io(e)
            }
        })
    }

    /// Check the status of a single task
    ///
    /// # Arguments
    /// * `task_id` - The task identifier
    ///
    /// # Returns
    /// The completion status of the task
    ///
    /// # Errors
    /// Returns error if the task file cannot be read
    pub fn check_task_status(&self, task_id: &str) -> Result<TaskCompletionStatus> {
        let content = self.read_task_file(task_id)?;
        let (completed, total) = count_checkboxes(&content);
        Ok(TaskCompletionStatus::new(
            task_id.to_string(),
            total,
            completed,
        ))
    }

    /// Check the status of multiple tasks
    ///
    /// # Arguments
    /// * `task_ids` - Slice of task identifiers
    ///
    /// # Returns
    /// A vector of completion statuses
    ///
    /// # Errors
    /// Returns error if any task file cannot be read
    pub fn check_all(&self, task_ids: &[&str]) -> Result<Vec<TaskCompletionStatus>> {
        task_ids
            .iter()
            .map(|id| self.check_task_status(id))
            .collect()
    }

    /// Wait for all tasks to complete with timeout
    ///
    /// # Arguments
    /// * `task_ids` - Slice of task identifiers
    /// * `timeout` - Maximum time to wait
    ///
    /// # Returns
    /// PollResult containing final statuses and timeout flag
    ///
    /// # Errors
    /// Returns error if any task file cannot be read
    pub async fn wait_all(&self, task_ids: &[&str], timeout: Duration) -> Result<PollResult> {
        let start = std::time::Instant::now();

        loop {
            let statuses = self.check_all(task_ids)?;
            let all_completed = statuses.iter().all(|s| s.is_completed());

            if all_completed {
                return Ok(PollResult::completed(statuses));
            }

            if start.elapsed() >= timeout {
                return Ok(PollResult::timeout(statuses));
            }

            tokio::time::sleep(self.poll_interval).await;
        }
    }

    /// Watch tasks and emit status changes as a stream
    ///
    /// # Arguments
    /// * `task_ids` - Vector of task identifiers to watch
    ///
    /// # Returns
    /// A stream of TaskCompletionStatus items
    pub fn watch(&self, task_ids: Vec<String>) -> impl Stream<Item = TaskCompletionStatus> {
        let poll_interval = self.poll_interval;
        let tasks_dir = self.tasks_dir.clone();

        async_stream::stream! {
            let mut last_states: std::collections::HashMap<String, (usize, usize)> =
                std::collections::HashMap::new();

            loop {
                let mut all_completed = true;

                for task_id in &task_ids {
                    let path = tasks_dir.join(task_id).join("task.md");
                    if let Ok(content) = std::fs::read_to_string(&path) {
                        let (completed, total) = count_checkboxes(&content);
                        let last = last_states.get(task_id).copied().unwrap_or((0, 0));

                        if (completed, total) != last {
                            last_states.insert(task_id.clone(), (completed, total));
                            yield TaskCompletionStatus::new(task_id.clone(), total, completed);
                        }

                        if completed < total || total == 0 {
                            all_completed = false;
                        }
                    } else {
                        all_completed = false;
                    }
                }

                if all_completed {
                    break;
                }

                tokio::time::sleep(poll_interval).await;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    fn setup_task_file(dir: &TempDir, task_id: &str, content: &str) -> PathBuf {
        let task_dir = dir.path().join(".sman").join("tasks").join(task_id);
        fs::create_dir_all(&task_dir).unwrap();
        let file_path = task_dir.join("task.md");
        fs::write(&file_path, content).unwrap();
        file_path
    }

    #[test]
    fn test_parse_checkbox_pending() {
        let content = "- [ ] Implement feature A";
        let items = parse_checkboxes(content);
        assert_eq!(items.len(), 1);
        assert!(!items[0].checked);
        assert_eq!(items[0].content, "Implement feature A");
    }

    #[test]
    fn test_parse_checkbox_completed() {
        let content = "- [x] Implement feature A";
        let items = parse_checkboxes(content);
        assert_eq!(items.len(), 1);
        assert!(items[0].checked);
        assert_eq!(items[0].content, "Implement feature A");
    }

    #[test]
    fn test_parse_checkbox_completed_uppercase() {
        let content = "- [X] Implement feature A";
        let items = parse_checkboxes(content);
        assert_eq!(items.len(), 1);
        assert!(items[0].checked);
    }

    #[test]
    fn test_parse_mixed_checkboxes() {
        let content = r#"
# Task List

- [x] First item done
- [ ] Second item pending
- [x] Third item done
- [ ] Fourth item pending
"#;
        let items = parse_checkboxes(content);
        assert_eq!(items.len(), 4);
        assert!(items[0].checked);
        assert!(!items[1].checked);
        assert!(items[2].checked);
        assert!(!items[3].checked);
    }

    #[test]
    fn test_count_checkboxes() {
        let content = r#"
- [x] Done 1
- [ ] Pending 1
- [x] Done 2
"#;
        let (completed, total) = count_checkboxes(content);
        assert_eq!(total, 3);
        assert_eq!(completed, 2);
    }

    #[test]
    fn test_status_pending_when_zero_complete() {
        let status = TaskPollStatus::from_completion(5, 0);
        assert_eq!(status, TaskPollStatus::Pending);
    }

    #[test]
    fn test_status_in_progress_when_partial() {
        let status = TaskPollStatus::from_completion(5, 2);
        assert_eq!(status, TaskPollStatus::InProgress);
    }

    #[test]
    fn test_status_completed_when_all_done() {
        let status = TaskPollStatus::from_completion(5, 5);
        assert_eq!(status, TaskPollStatus::Completed);
    }

    #[test]
    fn test_status_pending_when_zero_total() {
        let status = TaskPollStatus::from_completion(0, 0);
        assert_eq!(status, TaskPollStatus::Pending);
    }

    #[test]
    fn test_completion_status_percentage() {
        let status = TaskCompletionStatus::new("task-1".to_string(), 4, 1);
        assert_eq!(status.percentage(), 25);

        let status = TaskCompletionStatus::new("task-2".to_string(), 4, 3);
        assert_eq!(status.percentage(), 75);

        let status = TaskCompletionStatus::new("task-3".to_string(), 0, 0);
        assert_eq!(status.percentage(), 0);
    }

    #[test]
    fn test_check_task_status() {
        let dir = TempDir::new().unwrap();
        let content = r#"
- [x] Done
- [ ] Pending
"#;
        setup_task_file(&dir, "task-1", content);

        let poller = TaskPoller::new(dir.path(), Duration::from_millis(100)).unwrap();
        let status = poller.check_task_status("task-1").unwrap();

        assert_eq!(status.task_id, "task-1");
        assert_eq!(status.total_items, 2);
        assert_eq!(status.completed_items, 1);
        assert_eq!(status.status, TaskPollStatus::InProgress);
    }

    #[test]
    fn test_check_task_status_file_not_found() {
        let dir = TempDir::new().unwrap();
        let poller = TaskPoller::new(dir.path(), Duration::from_millis(100)).unwrap();

        let result = poller.check_task_status("nonexistent");
        assert!(matches!(result, Err(CoreError::TaskFileNotFound(_))));
    }

    #[test]
    fn test_check_all_tasks() {
        let dir = TempDir::new().unwrap();
        setup_task_file(&dir, "task-1", "- [x] A\n- [x] B");
        setup_task_file(&dir, "task-2", "- [ ] A\n- [ ] B");

        let poller = TaskPoller::new(dir.path(), Duration::from_millis(100)).unwrap();
        let statuses = poller.check_all(&["task-1", "task-2"]).unwrap();

        assert_eq!(statuses.len(), 2);
        assert!(statuses[0].is_completed());
        assert!(!statuses[1].is_completed());
    }

    #[tokio::test]
    async fn test_wait_all_success() {
        let dir = TempDir::new().unwrap();
        setup_task_file(&dir, "task-1", "- [x] A\n- [x] B");

        let poller = TaskPoller::new(dir.path(), Duration::from_millis(10)).unwrap();
        let result = poller
            .wait_all(&["task-1"], Duration::from_millis(100))
            .await
            .unwrap();

        assert!(result.all_completed);
        assert!(!result.timed_out);
    }

    #[tokio::test]
    async fn test_wait_all_timeout() {
        let dir = TempDir::new().unwrap();
        setup_task_file(&dir, "task-1", "- [ ] A\n- [ ] B");

        let poller = TaskPoller::new(dir.path(), Duration::from_millis(50)).unwrap();
        let result = poller
            .wait_all(&["task-1"], Duration::from_millis(100))
            .await
            .unwrap();

        assert!(!result.all_completed);
        assert!(result.timed_out);
    }

    #[test]
    fn test_poll_result_completed() {
        let statuses = vec![
            TaskCompletionStatus::new("t1".to_string(), 2, 2),
            TaskCompletionStatus::new("t2".to_string(), 3, 3),
        ];
        let result = PollResult::completed(statuses);

        assert!(result.all_completed);
        assert!(!result.timed_out);
    }

    #[test]
    fn test_poll_result_timeout() {
        let statuses = vec![TaskCompletionStatus::new("t1".to_string(), 2, 1)];
        let result = PollResult::timeout(statuses);

        assert!(!result.all_completed);
        assert!(result.timed_out);
    }
}
