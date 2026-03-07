//! Sub Claw Executor for task.md based task execution
//!
//! This module provides the execution layer for processing task.md files
//! with checklist-based step execution and automatic skill updates.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};
use crate::skill_store::SkillStore;

/// Represents a parsed task.md content
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TaskContent {
    /// Task title (from # heading)
    pub title: String,
    /// Task description (content before checklist)
    pub description: String,
    /// Checklist items parsed from the markdown
    pub checklist: Vec<ChecklistItem>,
    /// Acceptance criteria (if present in task.md)
    pub acceptance_criteria: Vec<String>,
}

impl Default for TaskContent {
    fn default() -> Self {
        Self {
            title: String::new(),
            description: String::new(),
            checklist: Vec::new(),
            acceptance_criteria: Vec::new(),
        }
    }
}

impl TaskContent {
    /// Create a new empty TaskContent
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a TaskContent with a title
    pub fn with_title(title: impl Into<String>) -> Self {
        Self {
            title: title.into(),
            ..Default::default()
        }
    }

    /// Add a checklist item
    pub fn add_item(&mut self, content: impl Into<String>, checked: bool) {
        let index = self.checklist.len();
        self.checklist.push(ChecklistItem {
            index,
            content: content.into(),
            checked,
        });
    }
}

/// Represents a single checklist item in a task.md file
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ChecklistItem {
    /// Zero-based index in the checklist
    pub index: usize,
    /// The content of the checklist item
    pub content: String,
    /// Whether the item is checked
    pub checked: bool,
}

impl ChecklistItem {
    /// Create a new checklist item
    pub fn new(index: usize, content: impl Into<String>, checked: bool) -> Self {
        Self {
            index,
            content: content.into(),
            checked,
        }
    }
}

/// Result of executing a single step
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StepResult {
    /// Whether the step execution succeeded
    pub success: bool,
    /// Output or message from the execution
    pub output: String,
    /// Files that were modified during execution
    pub files_modified: Vec<PathBuf>,
}

impl StepResult {
    /// Create a successful step result
    pub fn success(output: impl Into<String>) -> Self {
        Self {
            success: true,
            output: output.into(),
            files_modified: Vec::new(),
        }
    }

    /// Create a failed step result
    pub fn failure(output: impl Into<String>) -> Self {
        Self {
            success: false,
            output: output.into(),
            files_modified: Vec::new(),
        }
    }

    /// Add a modified file
    pub fn with_file(mut self, path: PathBuf) -> Self {
        self.files_modified.push(path);
        self
    }

    /// Add multiple modified files
    pub fn with_files(mut self, paths: Vec<PathBuf>) -> Self {
        self.files_modified.extend(paths);
        self
    }
}

/// Experience extracted from a completed task
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
pub struct TaskExperience {
    /// Items learned during task execution
    pub learned: Vec<String>,
    /// Problems encountered and solutions applied
    pub problems_solved: Vec<(String, String)>,
    /// Reusable patterns identified
    pub patterns_found: Vec<String>,
}

impl TaskExperience {
    /// Create a new empty TaskExperience
    pub fn new() -> Self {
        Self::default()
    }

    /// Add a learned item
    pub fn add_learned(&mut self, item: impl Into<String>) {
        self.learned.push(item.into());
    }

    /// Add a problem-solution pair
    pub fn add_problem_solution(
        &mut self,
        problem: impl Into<String>,
        solution: impl Into<String>,
    ) {
        self.problems_solved.push((problem.into(), solution.into()));
    }

    /// Add a pattern
    pub fn add_pattern(&mut self, pattern: impl Into<String>) {
        self.patterns_found.push(pattern.into());
    }

    /// Check if the experience has any valuable content
    pub fn is_valuable(&self) -> bool {
        !self.learned.is_empty()
            || !self.problems_solved.is_empty()
            || !self.patterns_found.is_empty()
    }
}

/// Result of running a complete task
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionResult {
    /// Path to the task file
    pub task_path: PathBuf,
    /// Whether the task completed successfully
    pub success: bool,
    /// Number of steps completed
    pub steps_completed: usize,
    /// Total number of steps
    pub steps_total: usize,
    /// Experience extracted from the task
    pub experience: Option<TaskExperience>,
    /// Error message if failed
    pub error: Option<String>,
}

impl ExecutionResult {
    /// Create a successful execution result
    pub fn success(
        task_path: PathBuf,
        steps_completed: usize,
        steps_total: usize,
        experience: TaskExperience,
    ) -> Self {
        Self {
            task_path,
            success: true,
            steps_completed,
            steps_total,
            experience: Some(experience),
            error: None,
        }
    }

    /// Create a failed execution result
    pub fn failure(
        task_path: PathBuf,
        steps_completed: usize,
        steps_total: usize,
        error: String,
    ) -> Self {
        Self {
            task_path,
            success: false,
            steps_completed,
            steps_total,
            experience: None,
            error: Some(error),
        }
    }

    /// Create a result for a task with no steps
    pub fn empty(task_path: PathBuf) -> Self {
        Self {
            task_path,
            success: true,
            steps_completed: 0,
            steps_total: 0,
            experience: None,
            error: None,
        }
    }
}

/// LLM Client trait for step execution (internal to SubClawExecutor)
#[async_trait]
pub trait StepExecutor: Send + Sync {
    /// Execute a prompt and return the response
    async fn execute(&self, prompt: &str) -> std::result::Result<String, String>;
}

/// Mock step executor for testing
pub struct MockStepExecutor {
    response: String,
    should_fail: bool,
}

impl MockStepExecutor {
    /// Create a mock executor that returns the given response
    pub fn new(response: impl Into<String>) -> Self {
        Self {
            response: response.into(),
            should_fail: false,
        }
    }

    /// Create a mock executor that fails
    pub fn failing() -> Self {
        Self {
            response: String::new(),
            should_fail: true,
        }
    }
}

#[async_trait]
impl StepExecutor for MockStepExecutor {
    async fn execute(&self, _prompt: &str) -> std::result::Result<String, String> {
        if self.should_fail {
            Err("Mock executor failure".to_string())
        } else {
            Ok(self.response.clone())
        }
    }
}

/// Sub Claw Executor for processing task.md files
pub struct SubClawExecutor {
    /// Path to the task.md file
    task_path: PathBuf,
    /// Skill store for updating skills after task completion
    skill_store: SkillStore,
    /// Optional step executor for step execution
    step_executor: Option<Arc<dyn StepExecutor>>,
}

impl SubClawExecutor {
    /// Create a new SubClawExecutor
    ///
    /// # Arguments
    /// * `task_path` - Path to the task.md file
    /// * `skill_store` - SkillStore instance for skill updates
    ///
    /// # Returns
    /// A new SubClawExecutor instance
    pub fn new(task_path: &Path, skill_store: SkillStore) -> Self {
        Self {
            task_path: task_path.to_path_buf(),
            skill_store,
            step_executor: None,
        }
    }

    /// Set the step executor for step execution
    pub fn set_step_executor(&mut self, executor: Arc<dyn StepExecutor>) {
        self.step_executor = Some(executor);
    }

    /// Create a SubClawExecutor with a mock executor for testing
    pub fn with_mock(project_path: &Path) -> Self {
        use std::sync::Arc;
        let task_path = project_path.join("task.md");
        let skill_store = SkillStore::new(project_path).expect("Failed to create SkillStore");
        let mut executor = Self::new(&task_path, skill_store);
        executor.step_executor = Some(Arc::new(MockStepExecutor::new("Mock response")));
        executor
    }

    /// Read and parse the task.md file
    ///
    /// # Returns
    /// Parsed TaskContent or an error
    fn read_task_md(&self) -> Result<TaskContent> {
        if !self.task_path.exists() {
            return Err(CoreError::TaskFileNotFound(
                self.task_path.display().to_string(),
            ));
        }

        let content = fs::read_to_string(&self.task_path)?;
        self.parse_task_content(&content)
    }

    /// Parse task.md content into TaskContent
    fn parse_task_content(&self, content: &str) -> Result<TaskContent> {
        let mut task = TaskContent::new();
        let mut in_description = true;
        let mut description_lines: Vec<String> = Vec::new();

        for line in content.lines() {
            let trimmed = line.trim();

            // Parse title (first # heading)
            if trimmed.starts_with("# ") && task.title.is_empty() {
                task.title = trimmed[2..].to_string();
                continue;
            }

            // Parse checklist items
            if trimmed.starts_with("- [ ]") || trimmed.starts_with("- [x]") {
                in_description = false;
                let checked = trimmed.starts_with("- [x]");
                let item_content = if checked {
                    trimmed[6..].trim().to_string()
                } else {
                    trimmed[5..].trim().to_string()
                };
                let index = task.checklist.len();
                task.checklist.push(ChecklistItem {
                    index,
                    content: item_content,
                    checked,
                });
                continue;
            }

            // Parse acceptance criteria (## Acceptance Criteria section)
            if trimmed.starts_with("## ") && trimmed.to_lowercase().contains("acceptance") {
                in_description = false;
                continue;
            }

            // Collect description lines (before checklist)
            if in_description && !trimmed.starts_with("#") && !trimmed.is_empty() {
                description_lines.push(trimmed.to_string());
            }
        }

        task.description = description_lines.join("\n");
        Ok(task)
    }

    /// Find the next uncompleted step in the checklist
    ///
    /// # Returns
    /// The next ChecklistItem or None if all steps are completed
    fn find_next_step(&self, content: &TaskContent) -> Option<ChecklistItem> {
        content.checklist.iter().find(|item| !item.checked).cloned()
    }

    /// Execute a single step
    ///
    /// # Arguments
    /// * `step` - The checklist item to execute
    ///
    /// # Returns
    /// StepResult indicating success or failure
    async fn execute_step(&self, step: &ChecklistItem) -> Result<StepResult> {
        // If LLM client is available, use it
        if let Some(ref client) = self.step_executor {
            let task = self.read_task_md()?;
            let prompt = format!(
                "你是子 Claw 执行器，必须严格按 TDD 进行当前步骤。\n\n任务标题：{}\n任务目标：{}\n当前步骤：{}\n\n执行要求：\n1. 所有测试案例必须放在 tests 目录中管理\n2. 先补充或编写测试，先看到失败（Red）\n3. 再做最小实现使测试通过（Green）\n4. 必要时重构并保持测试通过（Refactor）\n5. 输出本步骤的执行结果、测试命令与通过/失败证据\n\n只返回执行结果，不要省略验证信息。",
                task.title, task.description, step.content
            );
            match client.execute(&prompt).await {
                Ok(response) => Ok(StepResult::success(response)),
                Err(error) => Ok(StepResult::failure(error)),
            }
        } else {
            // Without LLM client, return a placeholder success
            // This allows testing without an actual LLM
            Ok(StepResult::success(format!(
                "[Placeholder] Step executed: {}",
                step.content
            )))
        }
    }

    /// Mark a step as completed in the task.md file
    ///
    /// # Arguments
    /// * `step_index` - The index of the step to mark as completed
    fn mark_step_done(&self, step_index: usize) -> Result<()> {
        let content = fs::read_to_string(&self.task_path)?;
        let lines: Vec<&str> = content.lines().collect();
        let mut checklist_count = 0;
        let mut modified_lines: Vec<String> = Vec::new();

        for line in &lines {
            let trimmed = line.trim();
            if trimmed.starts_with("- [ ]") || trimmed.starts_with("- [x]") {
                if checklist_count == step_index && trimmed.starts_with("- [ ]") {
                    // Replace - [ ] with - [x]
                    let new_line = line.replace("- [ ]", "- [x]");
                    modified_lines.push(new_line);
                } else {
                    modified_lines.push(line.to_string());
                }
                checklist_count += 1;
            } else {
                modified_lines.push(line.to_string());
            }
        }

        if step_index >= checklist_count {
            return Err(CoreError::InvalidInput(format!(
                "Step index {} out of bounds (total: {})",
                step_index, checklist_count
            )));
        }

        let new_content = modified_lines.join("\n");
        fs::write(&self.task_path, new_content)?;
        Ok(())
    }

    /// Update skills based on task experience
    ///
    /// # Arguments
    /// * `experience` - The experience extracted from the completed task
    fn update_skills(&self, experience: &TaskExperience) -> Result<()> {
        if !experience.is_valuable() {
            return Ok(());
        }

        // Create a skill from the experience
        let skill_id = format!("task-{}", chrono::Utc::now().timestamp());
        let skill_path = format!("auto/{}.md", skill_id);

        let mut content = String::new();
        content.push_str(&format!("# Auto-generated Skill: {}\n\n", skill_id));

        if !experience.learned.is_empty() {
            content.push_str("## Learned\n\n");
            for item in &experience.learned {
                content.push_str(&format!("- {}\n", item));
            }
            content.push('\n');
        }

        if !experience.problems_solved.is_empty() {
            content.push_str("## Problems Solved\n\n");
            for (problem, solution) in &experience.problems_solved {
                content.push_str(&format!("### Problem\n{}\n\n", problem));
                content.push_str(&format!("### Solution\n{}\n\n", solution));
            }
        }

        if !experience.patterns_found.is_empty() {
            content.push_str("## Patterns\n\n");
            for pattern in &experience.patterns_found {
                content.push_str(&format!("- {}\n", pattern));
            }
        }

        let skill = crate::skill_store::Skill {
            meta: crate::skill_store::SkillMeta {
                id: skill_id,
                path: skill_path,
                tags: vec!["auto-generated".to_string()],
                learned_from: self.task_path.display().to_string(),
                updated_at: chrono::Utc::now().timestamp(),
            },
            content,
        };

        self.skill_store.create(&skill)
    }

    /// Run the complete task execution loop
    ///
    /// This method reads the task.md file, executes each unchecked step
    /// sequentially, and updates the file after each step completes.
    ///
    /// # Returns
    /// ExecutionResult with the final status
    pub async fn run(&mut self) -> Result<ExecutionResult> {
        let content = self.read_task_md()?;
        let total_steps = content.checklist.len();

        if total_steps == 0 {
            return Ok(ExecutionResult::empty(self.task_path.clone()));
        }

        let mut steps_completed = 0;
        let mut experience = TaskExperience::new();

        loop {
            let content = self.read_task_md()?;

            match self.find_next_step(&content) {
                Some(step) => {
                    let result = self.execute_step(&step).await?;
                    if result.success {
                        self.mark_step_done(step.index)?;
                        steps_completed += 1;

                        // Collect experience from successful step
                        experience.add_learned(format!("Completed step: {}", step.content));
                    } else {
                        // Step failed
                        experience.add_problem_solution(
                            format!("Step failed: {}", step.content),
                            result.output.clone(),
                        );
                        return Ok(ExecutionResult::failure(
                            self.task_path.clone(),
                            steps_completed,
                            total_steps,
                            result.output,
                        ));
                    }
                }
                None => {
                    // All steps completed
                    if experience.is_valuable() {
                        self.update_skills(&experience)?;
                    }
                    return Ok(ExecutionResult::success(
                        self.task_path.clone(),
                        steps_completed,
                        total_steps,
                        experience,
                    ));
                }
            }
        }
    }

    /// Get the task path
    pub fn task_path(&self) -> &Path {
        &self.task_path
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_test_task_file(dir: &TempDir, content: &str) -> PathBuf {
        let path = dir.path().join("task.md");
        fs::write(&path, content).expect("write task file");
        path
    }

    fn create_test_skill_store(dir: &TempDir) -> SkillStore {
        SkillStore::new(dir.path()).expect("create skill store")
    }

    // Test 1: Create SubClawExecutor
    #[test]
    fn test_create_executor() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let task_path = temp_dir.path().join("task.md");
        let skill_store = create_test_skill_store(&temp_dir);

        let executor = SubClawExecutor::new(&task_path, skill_store);
        assert_eq!(executor.task_path(), task_path);
    }

    // Test 2: Read task.md file
    #[test]
    fn test_read_task_md() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Test Task

This is a description.

- [ ] Step 1
- [ ] Step 2
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let result = executor.read_task_md().expect("read task");
        assert_eq!(result.title, "Test Task");
        assert_eq!(result.checklist.len(), 2);
    }

    // Test 3: Parse checkbox list
    #[test]
    fn test_parse_checklist() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] Unchecked item
- [x] Checked item
- [ ] Another unchecked
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let result = executor.read_task_md().expect("read task");
        assert_eq!(result.checklist.len(), 3);
        assert_eq!(result.checklist[0].content, "Unchecked item");
        assert!(!result.checklist[0].checked);
        assert_eq!(result.checklist[1].content, "Checked item");
        assert!(result.checklist[1].checked);
        assert_eq!(result.checklist[2].content, "Another unchecked");
        assert!(!result.checklist[2].checked);
    }

    // Test 4: Find first uncompleted step
    #[test]
    fn test_find_first_uncompleted_step() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] First step
- [ ] Second step
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let task_content = executor.read_task_md().expect("read task");
        let step = executor.find_next_step(&task_content);

        assert!(step.is_some());
        let step = step.unwrap();
        assert_eq!(step.index, 0);
        assert_eq!(step.content, "First step");
    }

    // Test 5: Find middle uncompleted step
    #[test]
    fn test_find_middle_uncompleted_step() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [x] First step
- [ ] Second step
- [ ] Third step
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let task_content = executor.read_task_md().expect("read task");
        let step = executor.find_next_step(&task_content);

        assert!(step.is_some());
        let step = step.unwrap();
        assert_eq!(step.index, 1);
        assert_eq!(step.content, "Second step");
    }

    // Test 6: Return None when all steps completed
    #[test]
    fn test_find_next_step_returns_none_when_all_completed() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [x] First step
- [x] Second step
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let task_content = executor.read_task_md().expect("read task");
        let step = executor.find_next_step(&task_content);

        assert!(step.is_none());
    }

    // Test 7: Mark step done updates file
    #[test]
    fn test_mark_step_done() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] First step
- [ ] Second step
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        executor.mark_step_done(0).expect("mark step done");

        let updated = fs::read_to_string(&task_path).expect("read updated file");
        assert!(updated.contains("- [x] First step"));
        assert!(updated.contains("- [ ] Second step"));
    }

    // Test 8: Execute step returns result
    #[tokio::test]
    async fn test_execute_step() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] Test step
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let step = ChecklistItem::new(0, "Test step", false);
        let result = executor.execute_step(&step).await.expect("execute step");

        assert!(result.success);
        assert!(result.output.contains("Test step"));
    }

    // Test 9: Execute step with mock LLM
    #[tokio::test]
    async fn test_execute_step_with_llm() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] Test step
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let mut executor = SubClawExecutor::new(&task_path, skill_store);
        executor.set_step_executor(Arc::new(MockStepExecutor::new("LLM response")));

        let step = ChecklistItem::new(0, "Test step", false);
        let result = executor.execute_step(&step).await.expect("execute step");

        assert!(result.success);
        assert_eq!(result.output, "LLM response");
    }

    // Test 10: Execute step with failing LLM
    #[tokio::test]
    async fn test_execute_step_with_failing_llm() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] Test step
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let mut executor = SubClawExecutor::new(&task_path, skill_store);
        executor.set_step_executor(Arc::new(MockStepExecutor::failing()));

        let step = ChecklistItem::new(0, "Test step", false);
        let result = executor.execute_step(&step).await.expect("execute step");

        assert!(!result.success);
        assert!(result.output.contains("Mock executor failure"));
    }

    // Test 11: Complete run loop
    #[tokio::test]
    async fn test_run_complete() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] Step 1
- [ ] Step 2
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let mut executor = SubClawExecutor::new(&task_path, skill_store);

        let result = executor.run().await.expect("run executor");

        assert!(result.success);
        assert_eq!(result.steps_completed, 2);
        assert_eq!(result.steps_total, 2);
        assert!(result.experience.is_some());

        // Verify file was updated
        let updated = fs::read_to_string(&task_path).expect("read file");
        assert!(updated.contains("- [x] Step 1"));
        assert!(updated.contains("- [x] Step 2"));
    }

    // Test 12: TaskContent parsing
    #[test]
    fn test_task_content_parsing() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# My Task Title

This is the description.
It can span multiple lines.

- [ ] First item
- [x] Second item (already done)
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let result = executor.read_task_md().expect("read task");

        assert_eq!(result.title, "My Task Title");
        assert!(result.description.contains("This is the description"));
        assert_eq!(result.checklist.len(), 2);
    }

    // Test 13: ChecklistItem structure
    #[test]
    fn test_checklist_item() {
        let item = ChecklistItem::new(5, "Test content", true);

        assert_eq!(item.index, 5);
        assert_eq!(item.content, "Test content");
        assert!(item.checked);
    }

    // Test 14: StepResult success/failure
    #[test]
    fn test_step_result() {
        let success = StepResult::success("Done");
        assert!(success.success);
        assert_eq!(success.output, "Done");

        let failure = StepResult::failure("Error occurred");
        assert!(!failure.success);
        assert_eq!(failure.output, "Error occurred");

        let with_file = StepResult::success("Done").with_file(PathBuf::from("test.rs"));
        assert_eq!(with_file.files_modified.len(), 1);
    }

    // Test 15: ExecutionResult structure
    #[test]
    fn test_execution_result() {
        let path = PathBuf::from("/test/task.md");
        let experience = TaskExperience::new();

        let success = ExecutionResult::success(path.clone(), 3, 3, experience.clone());
        assert!(success.success);
        assert_eq!(success.steps_completed, 3);
        assert_eq!(success.steps_total, 3);
        assert!(success.error.is_none());

        let failure = ExecutionResult::failure(path.clone(), 1, 3, "Failed".to_string());
        assert!(!failure.success);
        assert_eq!(failure.steps_completed, 1);
        assert_eq!(failure.error, Some("Failed".to_string()));

        let empty = ExecutionResult::empty(path);
        assert!(empty.success);
        assert_eq!(empty.steps_completed, 0);
        assert_eq!(empty.steps_total, 0);
    }

    // Test 16: TaskExperience
    #[test]
    fn test_task_experience() {
        let mut experience = TaskExperience::new();
        assert!(!experience.is_valuable());

        experience.add_learned("Learned something");
        assert!(experience.is_valuable());
        assert_eq!(experience.learned.len(), 1);

        experience.add_problem_solution("Problem", "Solution");
        assert_eq!(experience.problems_solved.len(), 1);
        assert_eq!(
            experience.problems_solved[0],
            ("Problem".to_string(), "Solution".to_string())
        );

        experience.add_pattern("Pattern found");
        assert_eq!(experience.patterns_found.len(), 1);
    }

    // Test 17: Read non-existent file
    #[test]
    fn test_read_nonexistent_file() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let task_path = temp_dir.path().join("nonexistent.md");
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let result = executor.read_task_md();
        assert!(result.is_err());
    }

    // Test 18: Empty checklist
    #[tokio::test]
    async fn test_run_empty_checklist() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task

No checklist here.
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let mut executor = SubClawExecutor::new(&task_path, skill_store);

        let result = executor.run().await.expect("run executor");

        assert!(result.success);
        assert_eq!(result.steps_completed, 0);
        assert_eq!(result.steps_total, 0);
    }

    // Test 19: Mark step out of bounds
    #[test]
    fn test_mark_step_out_of_bounds() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] Only one item
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let executor = SubClawExecutor::new(&task_path, skill_store);

        let result = executor.mark_step_done(5);
        assert!(result.is_err());
    }

    // Test 20: Set LLM client
    #[test]
    fn test_set_step_executor() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let task_path = temp_dir.path().join("task.md");
        fs::write(&task_path, "# Task\n- [ ] Step").expect("write");
        let skill_store = create_test_skill_store(&temp_dir);
        let mut executor = SubClawExecutor::new(&task_path, skill_store);

        assert!(executor.step_executor.is_none());

        executor.set_step_executor(Arc::new(MockStepExecutor::new("test")));
        assert!(executor.step_executor.is_some());
    }

    // Test 21: Update skills after completion
    #[tokio::test]
    async fn test_update_skills_after_completion() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] Step 1
- [ ] Step 2
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let mut executor = SubClawExecutor::new(&task_path, skill_store);

        let result = executor.run().await.expect("run executor");
        assert!(result.success);
        assert!(result.experience.is_some());

        // Experience should have learned items
        let experience = result.experience.unwrap();
        assert!(!experience.learned.is_empty());
    }

    // Test 22: TaskContent with add_item
    #[test]
    fn test_task_content_add_item() {
        let mut content = TaskContent::with_title("Test");
        assert_eq!(content.title, "Test");
        assert_eq!(content.checklist.len(), 0);

        content.add_item("Item 1", false);
        content.add_item("Item 2", true);

        assert_eq!(content.checklist.len(), 2);
        assert_eq!(content.checklist[0].content, "Item 1");
        assert!(!content.checklist[0].checked);
        assert_eq!(content.checklist[1].content, "Item 2");
        assert!(content.checklist[1].checked);
    }

    // Test 23: Partial completion (stop on failure)
    #[tokio::test]
    async fn test_partial_completion_on_failure() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let content = r#"# Task
- [ ] Step 1
- [ ] Step 2
- [ ] Step 3
"#;
        let task_path = create_test_task_file(&temp_dir, content);
        let skill_store = create_test_skill_store(&temp_dir);
        let mut executor = SubClawExecutor::new(&task_path, skill_store);
        executor.set_step_executor(Arc::new(MockStepExecutor::failing()));

        let result = executor.run().await.expect("run executor");

        assert!(!result.success);
        assert_eq!(result.steps_completed, 0); // First step fails
        assert_eq!(result.steps_total, 3);
        assert!(result.error.is_some());
    }
}
