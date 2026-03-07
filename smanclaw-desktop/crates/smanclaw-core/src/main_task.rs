//! Main Task Controller for managing high-level task orchestration
//!
//! This module provides functionality to create and manage main-task.md files
//! that serve as the central control for complex multi-subtask operations.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use chrono::{DateTime, Local, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{CoreError, Result};
use crate::orchestrator::SubTaskStatus;

/// Unique identifier for a main task
pub type MainTaskId = String;

/// Status of a main task
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum MainTaskStatus {
    /// Task is being analyzed
    Analyzing,
    /// Task is being planned/broken down
    Planning,
    /// Task is being executed
    Executing,
    /// Task is being verified
    Verifying,
    /// Task completed successfully
    Completed,
    /// Task failed
    Failed,
}

impl Default for MainTaskStatus {
    fn default() -> Self {
        Self::Analyzing
    }
}

impl MainTaskStatus {
    /// Get the display text for the status
    pub fn display_text(&self) -> &'static str {
        match self {
            Self::Analyzing => "Analyzing",
            Self::Planning => "Planning",
            Self::Executing => "Executing",
            Self::Verifying => "Verifying",
            Self::Completed => "Completed",
            Self::Failed => "Failed",
        }
    }

    /// Get the emoji icon for the status
    pub fn emoji(&self) -> &'static str {
        match self {
            Self::Analyzing => "Analyzing",
            Self::Planning => "Planning",
            Self::Executing => "Executing",
            Self::Verifying => "Verifying",
            Self::Completed => "Completed",
            Self::Failed => "Failed",
        }
    }

    /// Get the Chinese display text with icon
    pub fn chinese_display(&self) -> &'static str {
        match self {
            Self::Analyzing => "Analyzing",
            Self::Planning => "Planning",
            Self::Executing => "Executing",
            Self::Verifying => "Verifying",
            Self::Completed => "Completed",
            Self::Failed => "Failed",
        }
    }
}

/// Reference to a subtask within a main task
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SubTaskRef {
    /// Unique subtask identifier
    pub id: String,
    /// Human-readable title
    pub title: String,
    /// Current status
    pub status: SubTaskStatus,
    /// IDs of tasks this task depends on
    pub dependencies: Vec<String>,
}

impl SubTaskRef {
    /// Create a new subtask reference
    pub fn new(id: impl Into<String>, title: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            title: title.into(),
            status: SubTaskStatus::Pending,
            dependencies: Vec::new(),
        }
    }

    /// Add a dependency
    pub fn depends_on(mut self, task_id: impl Into<String>) -> Self {
        self.dependencies.push(task_id.into());
        self
    }

    /// Get the status emoji for display
    pub fn status_emoji(&self) -> &'static str {
        match self.status {
            SubTaskStatus::Pending => "Pending",
            SubTaskStatus::Running => "Running",
            SubTaskStatus::Completed => "Completed",
            SubTaskStatus::Failed => "Failed",
        }
    }

    /// Get the Chinese status text
    pub fn status_chinese(&self) -> &'static str {
        match self.status {
            SubTaskStatus::Pending => "Pending",
            SubTaskStatus::Running => "Running",
            SubTaskStatus::Completed => "Completed",
            SubTaskStatus::Failed => "Failed",
        }
    }
}

/// Result of a completed main task
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MainTaskResult {
    /// Whether the task succeeded
    pub success: bool,
    /// Summary of what was accomplished
    pub summary: String,
    /// Files that were changed
    pub files_changed: Vec<String>,
    /// Error message if failed
    pub error: Option<String>,
    /// Completion timestamp
    pub completed_at: i64,
}

impl MainTaskResult {
    /// Create a successful result
    pub fn success(summary: impl Into<String>, files_changed: Vec<String>) -> Self {
        Self {
            success: true,
            summary: summary.into(),
            files_changed,
            error: None,
            completed_at: Utc::now().timestamp(),
        }
    }

    /// Create a failed result
    pub fn failure(error: impl Into<String>) -> Self {
        Self {
            success: false,
            summary: String::new(),
            files_changed: Vec::new(),
            error: Some(error.into()),
            completed_at: Utc::now().timestamp(),
        }
    }
}

/// Progress information for a main task
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskProgress {
    /// Main task ID
    pub task_id: String,
    /// Total number of subtasks
    pub total_subtasks: usize,
    /// Number of completed subtasks
    pub completed_subtasks: usize,
    /// Number of running subtasks
    pub running_subtasks: usize,
    /// Number of pending subtasks
    pub pending_subtasks: usize,
    /// Number of failed subtasks
    pub failed_subtasks: usize,
    /// Completion percentage (0-100)
    pub percentage: u8,
    /// Whether all subtasks are completed
    pub is_complete: bool,
}

impl TaskProgress {
    /// Create progress from a list of subtask references
    pub fn from_sub_tasks(task_id: String, sub_tasks: &[SubTaskRef]) -> Self {
        let total_subtasks = sub_tasks.len();
        let completed_subtasks = sub_tasks
            .iter()
            .filter(|t| t.status == SubTaskStatus::Completed)
            .count();
        let running_subtasks = sub_tasks
            .iter()
            .filter(|t| t.status == SubTaskStatus::Running)
            .count();
        let pending_subtasks = sub_tasks
            .iter()
            .filter(|t| t.status == SubTaskStatus::Pending)
            .count();
        let failed_subtasks = sub_tasks
            .iter()
            .filter(|t| t.status == SubTaskStatus::Failed)
            .count();

        let percentage = if total_subtasks == 0 {
            0
        } else {
            ((completed_subtasks as f64 / total_subtasks as f64) * 100.0) as u8
        };

        let is_complete = total_subtasks > 0 && completed_subtasks == total_subtasks;

        Self {
            task_id,
            total_subtasks,
            completed_subtasks,
            running_subtasks,
            pending_subtasks,
            failed_subtasks,
            percentage,
            is_complete,
        }
    }
}

/// Entry in the execution log
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogEntry {
    /// Timestamp of the entry
    pub timestamp: i64,
    /// Log message
    pub message: String,
}

impl LogEntry {
    /// Create a new log entry with current timestamp
    pub fn now(message: impl Into<String>) -> Self {
        Self {
            timestamp: Utc::now().timestamp(),
            message: message.into(),
        }
    }

    /// Format the entry for display
    pub fn format(&self) -> String {
        let dt: DateTime<Local> = DateTime::from_timestamp(self.timestamp, 0)
            .unwrap_or_default()
            .into();
        format!("{} {}", dt.format("%H:%M"), self.message)
    }
}

/// Main Task structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MainTask {
    /// Unique task identifier
    pub id: MainTaskId,
    /// User's original request
    pub user_request: String,
    /// Project path this task belongs to
    pub project_path: PathBuf,
    /// References to subtasks
    pub sub_tasks: Vec<SubTaskRef>,
    /// Current status
    pub status: MainTaskStatus,
    /// Acceptance criteria checklist
    pub acceptance_criteria: Vec<String>,
    /// Creation timestamp (Unix timestamp)
    pub created_at: i64,
    /// Last update timestamp (Unix timestamp)
    pub updated_at: i64,
    /// Execution log entries
    pub execution_log: Vec<LogEntry>,
    /// Final result when completed
    pub result: Option<MainTaskResult>,
}

impl MainTask {
    /// Create a new main task
    pub fn new(project_path: &Path, user_request: impl Into<String>) -> Self {
        let now = Utc::now().timestamp();
        Self {
            id: generate_main_task_id(),
            user_request: user_request.into(),
            project_path: project_path.to_path_buf(),
            sub_tasks: Vec::new(),
            status: MainTaskStatus::default(),
            acceptance_criteria: Vec::new(),
            created_at: now,
            updated_at: now,
            execution_log: vec![LogEntry::now("Task created")],
            result: None,
        }
    }

    /// Add a subtask reference
    pub fn add_sub_task(&mut self, sub_task: SubTaskRef) {
        self.updated_at = Utc::now().timestamp();
        self.execution_log
            .push(LogEntry::now(format!("Added subtask: {}", sub_task.title)));
        self.sub_tasks.push(sub_task);
    }

    /// Update a subtask's status
    pub fn update_sub_task_status(
        &mut self,
        sub_task_id: &str,
        status: SubTaskStatus,
    ) -> Result<()> {
        let sub_task = self
            .sub_tasks
            .iter_mut()
            .find(|t| t.id == sub_task_id)
            .ok_or_else(|| CoreError::TaskNotFound(sub_task_id.to_string()))?;

        let old_status = sub_task.status;
        sub_task.status = status;
        self.updated_at = Utc::now().timestamp();

        let status_text = match status {
            SubTaskStatus::Pending => "Pending",
            SubTaskStatus::Running => "Running",
            SubTaskStatus::Completed => "Completed",
            SubTaskStatus::Failed => "Failed",
        };
        self.execution_log.push(LogEntry::now(format!(
            "Subtask '{}' status: {} -> {}",
            sub_task.title,
            match old_status {
                SubTaskStatus::Pending => "Pending",
                SubTaskStatus::Running => "Running",
                SubTaskStatus::Completed => "Completed",
                SubTaskStatus::Failed => "Failed",
            },
            status_text
        )));

        Ok(())
    }

    /// Add an acceptance criterion
    pub fn add_acceptance_criterion(&mut self, criterion: impl Into<String>) {
        self.acceptance_criteria.push(criterion.into());
        self.updated_at = Utc::now().timestamp();
    }

    /// Add a log entry
    pub fn log(&mut self, message: impl Into<String>) {
        self.execution_log.push(LogEntry::now(message));
        self.updated_at = Utc::now().timestamp();
    }

    /// Get the current progress
    pub fn progress(&self) -> TaskProgress {
        TaskProgress::from_sub_tasks(self.id.clone(), &self.sub_tasks)
    }

    /// Mark the task as completed with a result
    pub fn complete(&mut self, result: MainTaskResult) {
        self.status = if result.success {
            MainTaskStatus::Completed
        } else {
            MainTaskStatus::Failed
        };
        self.result = Some(result);
        self.updated_at = Utc::now().timestamp();

        let status_text = if self.status == MainTaskStatus::Completed {
            "Completed"
        } else {
            "Failed"
        };
        self.execution_log
            .push(LogEntry::now(format!("Main task {}", status_text)));
    }

    /// Render the task to markdown format
    pub fn to_markdown(&self) -> String {
        let mut md = String::new();
        let progress = self.progress();

        // Title
        md.push_str(&format!("# Main Task: {}\n\n", self.user_request));

        // Original request section
        md.push_str("## Original Request\n\n");
        md.push_str(&format!("User input: \"{}\"\n\n", self.user_request));

        // Status section
        md.push_str("## Status\n\n");
        let created_dt: DateTime<Local> = DateTime::from_timestamp(self.created_at, 0)
            .unwrap_or_default()
            .into();
        md.push_str(&format!(
            "- Created: {}\n",
            created_dt.format("%Y-%m-%d %H:%M:%S")
        ));
        md.push_str(&format!(
            "- Current status: {} {}\n",
            self.status.emoji(),
            self.status.chinese_display()
        ));
        md.push_str(&format!(
            "- Progress: {}/{} ({}%)\n\n",
            progress.completed_subtasks, progress.total_subtasks, progress.percentage
        ));

        // Subtasks table
        md.push_str("## Subtasks\n\n");
        md.push_str("| ID | Title | Status | Dependencies |\n");
        md.push_str("|----|-------|--------|-------------|\n");

        for sub_task in &self.sub_tasks {
            let deps = if sub_task.dependencies.is_empty() {
                "-".to_string()
            } else {
                sub_task.dependencies.join(", ")
            };
            md.push_str(&format!(
                "| {} | {} | {} {} | {} |\n",
                sub_task.id,
                sub_task.title,
                sub_task.status_emoji(),
                sub_task.status_chinese(),
                deps
            ));
        }
        md.push('\n');

        // Acceptance criteria
        md.push_str("## Acceptance Criteria\n\n");
        if self.acceptance_criteria.is_empty() {
            md.push_str("(No acceptance criteria defined)\n\n");
        } else {
            for criterion in &self.acceptance_criteria {
                md.push_str(&format!("- [ ] {}\n", criterion));
            }
            md.push('\n');
        }

        // Execution log
        md.push_str("## Execution Log\n\n");
        for entry in &self.execution_log {
            md.push_str(&format!("{}\n", entry.format()));
        }
        md.push('\n');

        // Final result
        md.push_str("## Final Result\n\n");
        if let Some(ref result) = self.result {
            if result.success {
                md.push_str("**Status:** Completed\n\n");
                md.push_str(&format!("**Summary:** {}\n\n", result.summary));
                if !result.files_changed.is_empty() {
                    md.push_str("**Files Changed:**\n");
                    for file in &result.files_changed {
                        md.push_str(&format!("- {}\n", file));
                    }
                    md.push('\n');
                }
            } else {
                md.push_str("**Status:** Failed\n\n");
                if let Some(ref error) = result.error {
                    md.push_str(&format!("**Error:** {}\n\n", error));
                }
            }
        } else {
            md.push_str("(To be filled after task completion)\n");
        }

        md
    }
}

fn generate_main_task_id() -> String {
    let timestamp = Local::now().format("%y%m%d%H%M").to_string();
    let charset = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    let mut value = (Uuid::new_v4().as_u128() % 36u128.pow(4)) as u32;
    let mut suffix = ['0'; 4];
    for index in (0..4).rev() {
        let digit = (value % 36) as usize;
        suffix[index] = charset[digit] as char;
        value /= 36;
    }
    format!("main-{}-{}", timestamp, suffix.iter().collect::<String>())
}

/// Manager for main tasks
pub struct MainTaskManager {
    /// Directory to store main task files
    main_tasks_dir: PathBuf,
    /// Runtime directory (.smanclaw)
    runtime_dir: PathBuf,
    /// Project path
    project_path: PathBuf,
}

impl MainTaskManager {
    /// Create a new MainTaskManager
    ///
    /// # Arguments
    /// * `project_path` - The root path of the project
    ///
    /// # Returns
    /// A MainTaskManager instance with main-tasks directory set to `{project_path}/.smanclaw/main-tasks`
    pub fn new(project_path: &Path) -> Result<Self> {
        let runtime_dir = project_path.join(".smanclaw");
        let main_tasks_dir = runtime_dir.join("main-tasks");
        fs::create_dir_all(&main_tasks_dir).map_err(CoreError::Io)?;
        Ok(Self {
            main_tasks_dir,
            runtime_dir,
            project_path: project_path.to_path_buf(),
        })
    }

    /// Get the file path for a main task
    fn task_file_path(&self, task_id: &str) -> PathBuf {
        self.main_tasks_dir.join(format!("{}.md", task_id))
    }

    /// Create a new main task
    ///
    /// # Arguments
    /// * `user_request` - The user's original request
    ///
    /// # Returns
    /// The created MainTask
    pub fn create(&self, user_request: &str) -> Result<MainTask> {
        let task = MainTask::new(&self.project_path, user_request);
        self.save(&task)?;
        Ok(task)
    }

    /// Save a main task to disk
    fn save(&self, task: &MainTask) -> Result<()> {
        let path = self.task_file_path(&task.id);

        // Save markdown version
        let md_content = task.to_markdown();
        let mut file = fs::File::create(&path).map_err(CoreError::Io)?;
        file.write_all(md_content.as_bytes())
            .map_err(CoreError::Io)?;

        let latest_main_path = self.runtime_dir.join(format!("{}.md", task.id));
        let mut latest_main_file = fs::File::create(&latest_main_path).map_err(CoreError::Io)?;
        latest_main_file
            .write_all(md_content.as_bytes())
            .map_err(CoreError::Io)?;

        // Also save JSON version for programmatic access
        let json_path = path.with_extension("json");
        let json_content =
            serde_json::to_string_pretty(task).map_err(CoreError::Serialization)?;
        let mut json_file = fs::File::create(&json_path).map_err(CoreError::Io)?;
        json_file
            .write_all(json_content.as_bytes())
            .map_err(CoreError::Io)?;

        Ok(())
    }

    /// Load a main task by ID
    ///
    /// # Arguments
    /// * `task_id` - The task identifier
    ///
    /// # Returns
    /// The MainTask if found, None otherwise
    pub fn load(&self, task_id: &str) -> Result<Option<MainTask>> {
        let json_path = self.task_file_path(task_id).with_extension("json");

        if !json_path.exists() {
            return Ok(None);
        }

        let content = fs::read_to_string(&json_path).map_err(CoreError::Io)?;
        let task: MainTask = serde_json::from_str(&content).map_err(CoreError::Serialization)?;

        Ok(Some(task))
    }

    /// Update an existing main task
    ///
    /// # Arguments
    /// * `task` - The task to update
    pub fn update(&self, task: &MainTask) -> Result<()> {
        if !self.task_file_path(&task.id).exists() {
            return Err(CoreError::TaskNotFound(task.id.clone()));
        }
        self.save(task)
    }

    /// Add a subtask to a main task
    ///
    /// # Arguments
    /// * `main_task_id` - The main task ID
    /// * `sub_task` - The subtask reference to add
    pub fn add_sub_task(&self, main_task_id: &str, sub_task: &SubTaskRef) -> Result<()> {
        let mut task = self
            .load(main_task_id)?
            .ok_or_else(|| CoreError::TaskNotFound(main_task_id.to_string()))?;

        task.add_sub_task(sub_task.clone());
        self.update(&task)
    }

    /// Update a subtask's status
    ///
    /// # Arguments
    /// * `main_task_id` - The main task ID
    /// * `sub_task_id` - The subtask ID
    /// * `status` - The new status
    pub fn update_sub_task_status(
        &self,
        main_task_id: &str,
        sub_task_id: &str,
        status: SubTaskStatus,
    ) -> Result<()> {
        let mut task = self
            .load(main_task_id)?
            .ok_or_else(|| CoreError::TaskNotFound(main_task_id.to_string()))?;

        task.update_sub_task_status(sub_task_id, status)?;
        self.update(&task)
    }

    /// Check the progress of a main task
    ///
    /// # Arguments
    /// * `task_id` - The task identifier
    ///
    /// # Returns
    /// TaskProgress containing completion information
    pub fn check_progress(&self, task_id: &str) -> Result<TaskProgress> {
        let task = self
            .load(task_id)?
            .ok_or_else(|| CoreError::TaskNotFound(task_id.to_string()))?;

        Ok(task.progress())
    }

    /// Complete a main task with a result
    ///
    /// # Arguments
    /// * `task_id` - The task identifier
    /// * `result` - The final result
    pub fn complete(&self, task_id: &str, result: &MainTaskResult) -> Result<()> {
        let mut task = self
            .load(task_id)?
            .ok_or_else(|| CoreError::TaskNotFound(task_id.to_string()))?;

        task.complete(result.clone());
        self.update(&task)
    }

    /// Add a log entry to a main task
    ///
    /// # Arguments
    /// * `task_id` - The task identifier
    /// * `message` - The log message
    pub fn log(&self, task_id: &str, message: &str) -> Result<()> {
        let mut task = self
            .load(task_id)?
            .ok_or_else(|| CoreError::TaskNotFound(task_id.to_string()))?;

        task.log(message);
        self.update(&task)
    }

    /// Update the status of a main task
    ///
    /// # Arguments
    /// * `task_id` - The task identifier
    /// * `status` - The new status
    pub fn update_status(&self, task_id: &str, status: MainTaskStatus) -> Result<()> {
        let mut task = self
            .load(task_id)?
            .ok_or_else(|| CoreError::TaskNotFound(task_id.to_string()))?;

        task.status = status;
        task.updated_at = Utc::now().timestamp();

        let status_text = match status {
            MainTaskStatus::Analyzing => "Analyzing",
            MainTaskStatus::Planning => "Planning",
            MainTaskStatus::Executing => "Executing",
            MainTaskStatus::Verifying => "Verifying",
            MainTaskStatus::Completed => "Completed",
            MainTaskStatus::Failed => "Failed",
        };
        task.execution_log
            .push(LogEntry::now(format!("Status changed to: {}", status_text)));

        self.update(&task)
    }

    /// List all main tasks
    ///
    /// # Returns
    /// A vector of all main task IDs
    pub fn list_all(&self) -> Result<Vec<String>> {
        let mut task_ids = Vec::new();

        let entries = fs::read_dir(&self.main_tasks_dir).map_err(CoreError::Io)?;
        for entry in entries {
            let entry = entry.map_err(CoreError::Io)?;
            let path = entry.path();
            if path.extension().is_some_and(|ext| ext == "json") {
                if let Some(stem) = path.file_stem() {
                    task_ids.push(stem.to_string_lossy().to_string());
                }
            }
        }

        Ok(task_ids)
    }

    /// Delete a main task
    ///
    /// # Arguments
    /// * `task_id` - The task identifier
    pub fn delete(&self, task_id: &str) -> Result<()> {
        let md_path = self.task_file_path(task_id);
        let json_path = md_path.with_extension("json");
        let latest_main_path = self.runtime_dir.join(format!("{}.md", task_id));

        if md_path.exists() {
            fs::remove_file(&md_path).map_err(CoreError::Io)?;
        }
        if json_path.exists() {
            fs::remove_file(&json_path).map_err(CoreError::Io)?;
        }
        if latest_main_path.exists() {
            fs::remove_file(&latest_main_path).map_err(CoreError::Io)?;
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_test_manager() -> (TempDir, MainTaskManager) {
        let temp_dir = TempDir::new().expect("create temp dir");
        let manager = MainTaskManager::new(temp_dir.path()).expect("create manager");
        (temp_dir, manager)
    }

    #[test]
    fn test_main_task_status_default() {
        assert_eq!(MainTaskStatus::default(), MainTaskStatus::Analyzing);
    }

    #[test]
    fn test_main_task_status_display() {
        assert_eq!(MainTaskStatus::Executing.display_text(), "Executing");
        assert_eq!(MainTaskStatus::Completed.display_text(), "Completed");
    }

    #[test]
    fn test_sub_task_ref_new() {
        let sub_task = SubTaskRef::new("task-001", "Implement login");
        assert_eq!(sub_task.id, "task-001");
        assert_eq!(sub_task.title, "Implement login");
        assert_eq!(sub_task.status, SubTaskStatus::Pending);
        assert!(sub_task.dependencies.is_empty());
    }

    #[test]
    fn test_sub_task_ref_with_dependencies() {
        let sub_task = SubTaskRef::new("task-002", "Implement API")
            .depends_on("task-001")
            .depends_on("task-000");

        assert_eq!(sub_task.dependencies.len(), 2);
        assert_eq!(sub_task.dependencies[0], "task-001");
        assert_eq!(sub_task.dependencies[1], "task-000");
    }

    #[test]
    fn test_main_task_result_success() {
        let result = MainTaskResult::success(
            "Login feature implemented",
            vec!["src/auth.rs".to_string(), "src/api.rs".to_string()],
        );

        assert!(result.success);
        assert_eq!(result.summary, "Login feature implemented");
        assert_eq!(result.files_changed.len(), 2);
        assert!(result.error.is_none());
    }

    #[test]
    fn test_main_task_result_failure() {
        let result = MainTaskResult::failure("Database connection failed");

        assert!(!result.success);
        assert_eq!(result.error, Some("Database connection failed".to_string()));
        assert!(result.files_changed.is_empty());
    }

    #[test]
    fn test_task_progress_empty() {
        let progress = TaskProgress::from_sub_tasks("main-1".to_string(), &[]);

        assert_eq!(progress.total_subtasks, 0);
        assert_eq!(progress.completed_subtasks, 0);
        assert_eq!(progress.percentage, 0);
        assert!(!progress.is_complete);
    }

    #[test]
    fn test_task_progress_partial() {
        let sub_tasks = vec![
            SubTaskRef::new("t1", "Task 1"),
            SubTaskRef {
                id: "t2".to_string(),
                title: "Task 2".to_string(),
                status: SubTaskStatus::Completed,
                dependencies: vec![],
            },
            SubTaskRef {
                id: "t3".to_string(),
                title: "Task 3".to_string(),
                status: SubTaskStatus::Completed,
                dependencies: vec![],
            },
        ];

        let progress = TaskProgress::from_sub_tasks("main-1".to_string(), &sub_tasks);

        assert_eq!(progress.total_subtasks, 3);
        assert_eq!(progress.completed_subtasks, 2);
        assert_eq!(progress.percentage, 66);
        assert!(!progress.is_complete);
    }

    #[test]
    fn test_task_progress_complete() {
        let sub_tasks = vec![
            SubTaskRef {
                id: "t1".to_string(),
                title: "Task 1".to_string(),
                status: SubTaskStatus::Completed,
                dependencies: vec![],
            },
            SubTaskRef {
                id: "t2".to_string(),
                title: "Task 2".to_string(),
                status: SubTaskStatus::Completed,
                dependencies: vec![],
            },
        ];

        let progress = TaskProgress::from_sub_tasks("main-1".to_string(), &sub_tasks);

        assert_eq!(progress.total_subtasks, 2);
        assert_eq!(progress.completed_subtasks, 2);
        assert_eq!(progress.percentage, 100);
        assert!(progress.is_complete);
    }

    #[test]
    fn test_log_entry_format() {
        let entry = LogEntry::now("Test message");
        let formatted = entry.format();

        assert!(formatted.contains("Test message"));
    }

    #[test]
    fn test_main_task_new() {
        let temp_dir = TempDir::new().unwrap();
        let task = MainTask::new(temp_dir.path(), "Implement user login");

        assert!(task.id.starts_with("main-"));
        let parts: Vec<&str> = task.id.split('-').collect();
        assert_eq!(parts.len(), 3);
        assert_eq!(parts[1].len(), 10);
        assert_eq!(parts[2].len(), 4);
        assert!(parts[1].chars().all(|ch| ch.is_ascii_digit()));
        assert!(
            parts[2]
                .chars()
                .all(|ch| ch.is_ascii_digit() || (ch.is_ascii_uppercase() && ch.is_ascii_alphabetic()))
        );
        assert_eq!(task.user_request, "Implement user login");
        assert_eq!(task.status, MainTaskStatus::Analyzing);
        assert!(task.sub_tasks.is_empty());
        assert!(task.result.is_none());
        assert!(!task.execution_log.is_empty());
    }

    #[test]
    fn test_main_task_add_sub_task() {
        let temp_dir = TempDir::new().unwrap();
        let mut task = MainTask::new(temp_dir.path(), "Test task");

        let sub_task = SubTaskRef::new("sub-1", "Subtask 1");
        task.add_sub_task(sub_task);

        assert_eq!(task.sub_tasks.len(), 1);
        assert_eq!(task.sub_tasks[0].id, "sub-1");
    }

    #[test]
    fn test_main_task_update_sub_task_status() {
        let temp_dir = TempDir::new().unwrap();
        let mut task = MainTask::new(temp_dir.path(), "Test task");
        task.add_sub_task(SubTaskRef::new("sub-1", "Subtask 1"));

        let result = task.update_sub_task_status("sub-1", SubTaskStatus::Running);
        assert!(result.is_ok());
        assert_eq!(task.sub_tasks[0].status, SubTaskStatus::Running);

        let result = task.update_sub_task_status("nonexistent", SubTaskStatus::Running);
        assert!(result.is_err());
    }

    #[test]
    fn test_main_task_complete() {
        let temp_dir = TempDir::new().unwrap();
        let mut task = MainTask::new(temp_dir.path(), "Test task");
        task.add_sub_task(SubTaskRef {
            id: "sub-1".to_string(),
            title: "Subtask 1".to_string(),
            status: SubTaskStatus::Completed,
            dependencies: vec![],
        });

        let result = MainTaskResult::success("Done", vec![]);
        task.complete(result);

        assert_eq!(task.status, MainTaskStatus::Completed);
        assert!(task.result.is_some());
        assert!(task.result.as_ref().unwrap().success);
    }

    #[test]
    fn test_main_task_to_markdown() {
        let temp_dir = TempDir::new().unwrap();
        let mut task = MainTask::new(temp_dir.path(), "Implement user login");
        task.add_sub_task(SubTaskRef::new("task-001", "Create User entity"));
        task.add_acceptance_criterion("User can login with username and password");

        let md = task.to_markdown();

        assert!(md.contains("# Main Task: Implement user login"));
        assert!(md.contains("## Original Request"));
        assert!(md.contains("## Status"));
        assert!(md.contains("## Subtasks"));
        assert!(md.contains("task-001"));
        assert!(md.contains("Create User entity"));
        assert!(md.contains("## Acceptance Criteria"));
        assert!(md.contains("## Execution Log"));
        assert!(md.contains("## Final Result"));
    }

    #[test]
    fn test_manager_new_creates_directory() {
        let temp_dir = TempDir::new().unwrap();
        let project_path = temp_dir.path();

        let manager = MainTaskManager::new(project_path).expect("create manager");

        let expected_dir = project_path.join(".smanclaw").join("main-tasks");
        assert!(expected_dir.exists());
        assert_eq!(manager.main_tasks_dir, expected_dir);
        assert_eq!(manager.runtime_dir, project_path.join(".smanclaw"));
    }

    #[test]
    fn test_manager_create_task() {
        let (_temp_dir, manager) = create_test_manager();

        let task = manager.create("Implement login feature").expect("create task");

        assert!(task.id.starts_with("main-"));
        assert_eq!(task.user_request, "Implement login feature");

        // Verify files were created
        let md_path = manager.main_tasks_dir.join(format!("{}.md", task.id));
        let json_path = manager.main_tasks_dir.join(format!("{}.json", task.id));
        let latest_path = manager.runtime_dir.join(format!("{}.md", task.id));
        assert!(md_path.exists());
        assert!(json_path.exists());
        assert!(latest_path.exists());
    }

    #[test]
    fn test_manager_load_task() {
        let (_temp_dir, manager) = create_test_manager();

        let created = manager.create("Test task").expect("create task");
        let loaded = manager.load(&created.id).expect("load task");

        assert!(loaded.is_some());
        let loaded = loaded.unwrap();
        assert_eq!(loaded.id, created.id);
        assert_eq!(loaded.user_request, "Test task");
    }

    #[test]
    fn test_manager_load_nonexistent() {
        let (_temp_dir, manager) = create_test_manager();

        let result = manager.load("nonexistent").expect("load task");
        assert!(result.is_none());
    }

    #[test]
    fn test_manager_update_task() {
        let (_temp_dir, manager) = create_test_manager();

        let mut task = manager.create("Original").expect("create task");
        task.status = MainTaskStatus::Executing;
        manager.update(&task).expect("update task");

        let loaded = manager.load(&task.id).expect("load task").unwrap();
        assert_eq!(loaded.status, MainTaskStatus::Executing);
    }

    #[test]
    fn test_manager_add_sub_task() {
        let (_temp_dir, manager) = create_test_manager();

        let task = manager.create("Main task").expect("create task");
        let sub_task = SubTaskRef::new("sub-1", "Subtask 1");

        manager
            .add_sub_task(&task.id, &sub_task)
            .expect("add subtask");

        let loaded = manager.load(&task.id).expect("load task").unwrap();
        assert_eq!(loaded.sub_tasks.len(), 1);
        assert_eq!(loaded.sub_tasks[0].id, "sub-1");
    }

    #[test]
    fn test_manager_update_sub_task_status() {
        let (_temp_dir, manager) = create_test_manager();

        let task = manager.create("Main task").expect("create task");
        let sub_task = SubTaskRef::new("sub-1", "Subtask 1");
        manager.add_sub_task(&task.id, &sub_task).unwrap();

        manager
            .update_sub_task_status(&task.id, "sub-1", SubTaskStatus::Running)
            .expect("update status");

        let loaded = manager.load(&task.id).expect("load task").unwrap();
        assert_eq!(loaded.sub_tasks[0].status, SubTaskStatus::Running);
    }

    #[test]
    fn test_manager_check_progress() {
        let (_temp_dir, manager) = create_test_manager();

        let task = manager.create("Main task").expect("create task");
        manager
            .add_sub_task(
                &task.id,
                &SubTaskRef {
                    id: "sub-1".to_string(),
                    title: "Subtask 1".to_string(),
                    status: SubTaskStatus::Completed,
                    dependencies: vec![],
                },
            )
            .unwrap();
        manager
            .add_sub_task(&task.id, &SubTaskRef::new("sub-2", "Subtask 2"))
            .unwrap();

        let progress = manager.check_progress(&task.id).expect("check progress");

        assert_eq!(progress.total_subtasks, 2);
        assert_eq!(progress.completed_subtasks, 1);
        assert_eq!(progress.percentage, 50);
    }

    #[test]
    fn test_manager_complete_task() {
        let (_temp_dir, manager) = create_test_manager();

        let task = manager.create("Main task").expect("create task");
        let result = MainTaskResult::success("All done", vec!["file.rs".to_string()]);

        manager.complete(&task.id, &result).expect("complete task");

        let loaded = manager.load(&task.id).expect("load task").unwrap();
        assert_eq!(loaded.status, MainTaskStatus::Completed);
        assert!(loaded.result.is_some());
        assert_eq!(loaded.result.unwrap().summary, "All done");
    }

    #[test]
    fn test_manager_log() {
        let (_temp_dir, manager) = create_test_manager();

        let task = manager.create("Main task").expect("create task");
        manager.log(&task.id, "Starting analysis").unwrap();

        let loaded = manager.load(&task.id).expect("load task").unwrap();
        assert!(loaded
            .execution_log
            .iter()
            .any(|e| e.message.contains("Starting analysis")));
    }

    #[test]
    fn test_manager_update_status() {
        let (_temp_dir, manager) = create_test_manager();

        let task = manager.create("Main task").expect("create task");
        manager
            .update_status(&task.id, MainTaskStatus::Executing)
            .unwrap();

        let loaded = manager.load(&task.id).expect("load task").unwrap();
        assert_eq!(loaded.status, MainTaskStatus::Executing);
    }

    #[test]
    fn test_manager_list_all() {
        let (_temp_dir, manager) = create_test_manager();

        let task1 = manager.create("Task 1").expect("create task");
        let task2 = manager.create("Task 2").expect("create task");

        let all_tasks = manager.list_all().expect("list all");

        assert_eq!(all_tasks.len(), 2);
        assert!(all_tasks.contains(&task1.id));
        assert!(all_tasks.contains(&task2.id));
    }

    #[test]
    fn test_manager_delete() {
        let (_temp_dir, manager) = create_test_manager();

        let task = manager.create("Task to delete").expect("create task");
        assert!(manager.load(&task.id).expect("load").is_some());

        manager.delete(&task.id).expect("delete task");
        assert!(manager.load(&task.id).expect("load").is_none());
    }

    #[test]
    fn test_manager_operations_on_nonexistent_task() {
        let (_temp_dir, manager) = create_test_manager();

        let result = manager.add_sub_task("nonexistent", &SubTaskRef::new("s1", "S1"));
        assert!(result.is_err());

        let result = manager.update_sub_task_status("nonexistent", "s1", SubTaskStatus::Running);
        assert!(result.is_err());

        let result = manager.check_progress("nonexistent");
        assert!(result.is_err());

        let result = manager.complete("nonexistent", &MainTaskResult::success("", vec![]));
        assert!(result.is_err());
    }
}
