//! Task markdown generator for sub-task execution
//!
//! This module provides functionality to generate and parse task.md files
//! that are used by the main Claw to communicate with sub-Claws.

use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;

use crate::error::{CoreError, Result};
use crate::orchestrator::SubTask;

/// Task status containing completion progress
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskStatus {
    /// Total number of checklist items
    pub total_items: usize,
    /// Number of completed items
    pub completed_items: usize,
    /// Completion percentage (0-100)
    pub percentage: u8,
}

impl TaskStatus {
    /// Create a new TaskStatus
    pub fn new(total_items: usize, completed_items: usize) -> Self {
        let percentage = if total_items == 0 {
            0
        } else {
            ((completed_items as f64 / total_items as f64) * 100.0) as u8
        };
        Self {
            total_items,
            completed_items,
            percentage,
        }
    }

    /// Check if all items are completed
    pub fn is_fully_completed(&self) -> bool {
        self.total_items > 0 && self.completed_items == self.total_items
    }
}

impl Default for TaskStatus {
    fn default() -> Self {
        Self::new(0, 0)
    }
}

/// Generator for task.md files
pub struct TaskGenerator {
    /// Directory to store task files
    tasks_dir: PathBuf,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DependencyInput {
    pub task_id: String,
    pub summary: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SubTaskContextContract {
    pub writable_scope: Vec<String>,
    pub readonly_context: Vec<String>,
    pub dependency_inputs: Vec<DependencyInput>,
    pub acceptance_checks: Vec<String>,
    pub constraints: Vec<String>,
}

impl TaskGenerator {
    /// Create a new TaskGenerator
    ///
    /// # Arguments
    /// * `project_path` - The root path of the project
    ///
    /// # Returns
    /// A TaskGenerator instance with tasks directory set to `{project_path}/.smanclaw/tasks`
    pub fn new(project_path: &std::path::Path) -> Result<Self> {
        let tasks_dir = project_path.join(".smanclaw").join("tasks");
        fs::create_dir_all(&tasks_dir).map_err(CoreError::Io)?;
        Ok(Self { tasks_dir })
    }

    fn task_dir(&self, task_id: &str) -> PathBuf {
        self.tasks_dir.join(task_id)
    }

    fn task_file(&self, task_id: &str) -> PathBuf {
        self.task_dir(task_id).join("task.md")
    }

    /// Generate a task.md file for a given subtask
    ///
    /// # Arguments
    /// * `task` - The subtask to generate markdown for
    ///
    /// # Returns
    /// Path to the generated task.md file
    pub fn generate(&self, task: &SubTask) -> Result<PathBuf> {
        let task_dir = self.task_dir(&task.id);
        fs::create_dir_all(&task_dir).map_err(CoreError::Io)?;
        let task_file = task_dir.join("task.md");

        let content = self.render_task_markdown(task);

        let mut file = fs::File::create(&task_file).map_err(CoreError::Io)?;
        file.write_all(content.as_bytes()).map_err(CoreError::Io)?;

        Ok(task_file)
    }

    pub fn generate_named(&self, task: &SubTask, file_stem: &str) -> Result<PathBuf> {
        let task_file = self.tasks_dir.join(format!("{}.md", file_stem));
        let content = self.render_task_markdown(task);
        let mut file = fs::File::create(&task_file).map_err(CoreError::Io)?;
        file.write_all(content.as_bytes()).map_err(CoreError::Io)?;
        Ok(task_file)
    }

    pub fn generate_named_with_contract(
        &self,
        task: &SubTask,
        file_stem: &str,
        contract: &SubTaskContextContract,
    ) -> Result<PathBuf> {
        let task_file = self.tasks_dir.join(format!("{}.md", file_stem));
        let content = self.render_task_markdown_with_contract(task, contract);
        let mut file = fs::File::create(&task_file).map_err(CoreError::Io)?;
        file.write_all(content.as_bytes()).map_err(CoreError::Io)?;
        Ok(task_file)
    }

    /// Render task to markdown format
    fn render_task_markdown(&self, task: &SubTask) -> String {
        self.render_task_markdown_with_contract(task, &SubTaskContextContract::default())
    }

    fn render_task_markdown_with_contract(
        &self,
        task: &SubTask,
        contract: &SubTaskContextContract,
    ) -> String {
        let mut md = String::new();

        // Title
        md.push_str(&format!("# Task: {}\n\n", task.description));

        // Goal section
        md.push_str("## 目标\n\n");
        md.push_str(&format!("{}\n\n", task.description));

        // Delivery standards section
        md.push_str("## 交付标准\n\n");
        md.push_str("- 结果满足任务目标并可被验证\n");
        md.push_str("- 必须包含可复现的测试证据\n");
        if let Some(ref test_cmd) = task.test_command {
            md.push_str(&format!("- 必须通过验证命令: `{}`\n", test_cmd));
        } else {
            md.push_str("- 必须补充并通过针对本任务的测试\n");
        }
        md.push_str("- 代码需通过项目既有质量门禁（lint/类型检查）\n\n");

        md.push_str("## 子任务上下文契约\n\n");
        md.push_str("### 可写范围\n\n");
        if contract.writable_scope.is_empty() {
            md.push_str("- 与本子任务直接相关的源码文件\n");
            md.push_str("- tests/ 下与本任务相关的测试文件\n\n");
        } else {
            for item in &contract.writable_scope {
                md.push_str(&format!("- {}\n", item));
            }
            md.push('\n');
        }
        md.push_str("### 只读上下文\n\n");
        if contract.readonly_context.is_empty() {
            md.push_str("- 项目现有架构与公共约束\n");
            md.push_str("- 依赖任务的最终输出摘要\n\n");
        } else {
            for item in &contract.readonly_context {
                md.push_str(&format!("- {}\n", item));
            }
            md.push('\n');
        }
        md.push_str("### 依赖输入\n\n");
        if contract.dependency_inputs.is_empty() {
            md.push_str("- 无前置依赖输入\n\n");
        } else {
            for dep in &contract.dependency_inputs {
                md.push_str(&format!("- {}: {}\n", dep.task_id, dep.summary));
            }
            md.push('\n');
        }
        md.push_str("### 子任务约束\n\n");
        if contract.constraints.is_empty() {
            md.push_str("- 本子任务必须在独立上下文内完成，不依赖与其他子任务实时沟通\n");
            md.push_str("- 遇到缺失信息时，先在当前任务范围内补齐再执行\n\n");
        } else {
            for item in &contract.constraints {
                md.push_str(&format!("- {}\n", item));
            }
            md.push('\n');
        }
        md.push_str("### 验收检查\n\n");
        if contract.acceptance_checks.is_empty() {
            if let Some(ref test_cmd) = task.test_command {
                md.push_str(&format!("- 执行并通过 `{}`\n", test_cmd));
            } else {
                md.push_str("- 执行与任务相关的测试并通过\n");
            }
        } else {
            for item in &contract.acceptance_checks {
                md.push_str(&format!("- {}\n", item));
            }
        }
        md.push('\n');

        // Context section
        md.push_str("## 上下文\n\n");
        md.push_str("- 项目类型: 未指定\n");
        md.push_str("- 技术栈: 未指定\n");
        md.push_str("- 相关模块: 未指定\n\n");

        // TDD strategy section
        md.push_str("## TDD 执行策略\n\n");
        md.push_str("1. 所有测试案例必须放在 tests 目录中管理\n");
        md.push_str("2. 先写或补充测试，明确失败行为（Red）\n");
        md.push_str("3. 最小实现使测试通过（Green）\n");
        md.push_str("4. 重构并保持测试持续通过（Refactor）\n\n");

        // Execution checklist section (Sub-Claw updates this)
        md.push_str("## 执行清单 (Sub-Claw 更新此区域)\n\n");
        md.push_str("- [ ] 在 tests 目录中补充/编写失败测试（Red）\n");
        md.push_str("- [ ] 运行测试并确认失败符合预期（Red）\n");
        md.push_str("- [ ] 以最小改动实现功能直至测试通过（Green）\n");
        md.push_str("- [ ] 重构代码并保持测试通过（Refactor）\n");
        md.push_str("- [ ] 运行验证命令并记录结果\n\n");

        // Experience section (Sub-Claw fills after completion)
        md.push_str("## 经验沉淀区域 (Sub-Claw 完成后填写)\n\n");
        md.push_str("### 新增经验\n\n");
        md.push_str("(待填写)\n\n");
        md.push_str("### 更新的 Skill\n\n");
        md.push_str("(待填写)\n\n");
        md.push_str("### 遇到的问题和解决方案\n\n");
        md.push_str("(待填写)\n");

        md
    }

    /// Parse the status of a task from its markdown file
    ///
    /// # Arguments
    /// * `task_id` - The ID of the task to parse
    ///
    /// # Returns
    /// TaskStatus containing completion information
    pub fn parse_status(&self, task_id: &str) -> Result<TaskStatus> {
        let task_file = self.task_file(task_id);

        if !task_file.exists() {
            return Err(CoreError::TaskNotFound(task_id.to_string()));
        }

        let file = fs::File::open(&task_file).map_err(CoreError::Io)?;
        let reader = BufReader::new(file);

        let mut total_items = 0usize;
        let mut completed_items = 0usize;

        for line in reader.lines() {
            let line = line.map_err(CoreError::Io)?;
            let trimmed = line.trim();

            // Match checklist items: "- [ ]" or "- [x]"
            if trimmed.starts_with("- [ ]") || trimmed.starts_with("- [x]") {
                total_items += 1;
                if trimmed.starts_with("- [x]") {
                    completed_items += 1;
                }
            }
        }

        Ok(TaskStatus::new(total_items, completed_items))
    }

    /// Check if a task is fully completed
    ///
    /// # Arguments
    /// * `task_id` - The ID of the task to check
    ///
    /// # Returns
    /// true if all checklist items are completed
    pub fn is_completed(&self, task_id: &str) -> Result<bool> {
        let status = self.parse_status(task_id)?;
        Ok(status.is_fully_completed())
    }

    /// Update the status of a specific checklist item
    ///
    /// # Arguments
    /// * `task_id` - The ID of the task
    /// * `item` - The text of the checklist item to update (partial match)
    /// * `done` - Whether to mark as done (true) or not done (false)
    pub fn update_status(&self, task_id: &str, item: &str, done: bool) -> Result<()> {
        let task_file = self.task_file(task_id);

        if !task_file.exists() {
            return Err(CoreError::TaskNotFound(task_id.to_string()));
        }

        let content = fs::read_to_string(&task_file).map_err(CoreError::Io)?;
        let lines: Vec<&str> = content.lines().collect();
        let mut updated_lines = Vec::new();
        let mut found = false;

        for line in lines {
            if line.contains(item)
                && (line.trim().starts_with("- [ ]") || line.trim().starts_with("- [x]"))
            {
                let updated = if done {
                    line.replace("- [ ]", "- [x]")
                } else {
                    line.replace("- [x]", "- [ ]")
                };
                updated_lines.push(updated);
                found = true;
            } else {
                updated_lines.push(line.to_string());
            }
        }

        if !found {
            return Err(CoreError::InvalidInput(format!(
                "Checklist item '{}' not found in task '{}'",
                item, task_id
            )));
        }

        let new_content = updated_lines.join("\n");
        let mut file = fs::File::create(&task_file).map_err(CoreError::Io)?;
        file.write_all(new_content.as_bytes())
            .map_err(CoreError::Io)?;

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_test_generator() -> (TempDir, TaskGenerator) {
        let temp_dir = TempDir::new().expect("create temp dir");
        let generator = TaskGenerator::new(temp_dir.path()).expect("create generator");
        (temp_dir, generator)
    }

    fn create_test_task() -> SubTask {
        SubTask::new("test-task-1", "实现用户登录功能").with_test_command("cargo test login")
    }

    #[test]
    fn test_task_status_new() {
        let status = TaskStatus::new(10, 5);
        assert_eq!(status.total_items, 10);
        assert_eq!(status.completed_items, 5);
        assert_eq!(status.percentage, 50);
    }

    #[test]
    fn test_task_status_zero_items() {
        let status = TaskStatus::new(0, 0);
        assert_eq!(status.total_items, 0);
        assert_eq!(status.completed_items, 0);
        assert_eq!(status.percentage, 0);
        assert!(!status.is_fully_completed());
    }

    #[test]
    fn test_task_status_fully_completed() {
        let status = TaskStatus::new(5, 5);
        assert_eq!(status.percentage, 100);
        assert!(status.is_fully_completed());
    }

    #[test]
    fn test_generator_new_creates_directory() {
        let temp_dir = TempDir::new().expect("create temp dir");
        let project_path = temp_dir.path();

        let generator = TaskGenerator::new(project_path).expect("create generator");

        let expected_dir = project_path.join(".smanclaw").join("tasks");
        assert!(expected_dir.exists());
        assert_eq!(generator.tasks_dir, expected_dir);
    }

    #[test]
    fn test_generate_creates_task_file() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();

        let result = generator.generate(&task);

        assert!(result.is_ok());
        let path = result.unwrap();
        assert!(path.exists());
        assert_eq!(path.file_name().unwrap().to_str().unwrap(), "task.md");
        assert_eq!(
            path.parent()
                .and_then(|parent| parent.file_name())
                .and_then(|name| name.to_str())
                .unwrap_or_default(),
            "test-task-1"
        );
    }

    #[test]
    fn test_generate_content_format() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();

        let path = generator.generate(&task).expect("generate task");
        let content = fs::read_to_string(&path).expect("read file");

        // Verify key sections exist
        assert!(content.contains("# Task: 实现用户登录功能"));
        assert!(content.contains("## 目标"));
        assert!(content.contains("## 交付标准"));
        assert!(content.contains("## TDD 执行策略"));
        assert!(content.contains("## 执行清单 (Sub-Claw 更新此区域)"));
        assert!(content.contains("## 经验沉淀区域 (Sub-Claw 完成后填写)"));
        assert!(content.contains("cargo test login"));
    }

    #[test]
    fn test_generate_named_creates_file_with_custom_name() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();

        let path = generator
            .generate_named(&task, "task-main-2603071250-AB12-001")
            .expect("generate named task");
        assert!(path.exists());
        assert_eq!(
            path.file_name().and_then(|name| name.to_str()),
            Some("task-main-2603071250-AB12-001.md")
        );
    }

    #[test]
    fn test_generate_named_with_contract_includes_contract_sections() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();
        let contract = SubTaskContextContract {
            writable_scope: vec!["src/auth".to_string(), "tests/auth".to_string()],
            readonly_context: vec!["src/lib/shared".to_string()],
            dependency_inputs: vec![DependencyInput {
                task_id: "task-1".to_string(),
                summary: "用户实体与仓储接口已完成".to_string(),
            }],
            acceptance_checks: vec!["cargo test auth_login".to_string()],
            constraints: vec!["禁止依赖其他子任务的临时口头同步".to_string()],
        };

        let path = generator
            .generate_named_with_contract(&task, "task-with-contract", &contract)
            .expect("generate task with contract");
        let content = fs::read_to_string(path).expect("read file");

        assert!(content.contains("## 子任务上下文契约"));
        assert!(content.contains("### 可写范围"));
        assert!(content.contains("- src/auth"));
        assert!(content.contains("### 依赖输入"));
        assert!(content.contains("task-1: 用户实体与仓储接口已完成"));
        assert!(content.contains("### 验收检查"));
        assert!(content.contains("cargo test auth_login"));
    }

    #[test]
    fn test_parse_status_task_not_found() {
        let (_temp_dir, generator) = create_test_generator();

        let result = generator.parse_status("nonexistent");

        assert!(result.is_err());
        match result {
            Err(CoreError::TaskNotFound(id)) => assert_eq!(id, "nonexistent"),
            _ => panic!("Expected TaskNotFound error"),
        }
    }

    #[test]
    fn test_parse_status_counts_checkboxes() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();
        generator.generate(&task).expect("generate task");

        let status = generator.parse_status("test-task-1").expect("parse status");

        // The generated task should have checklist items in execution section
        assert!(status.total_items > 0);
        // Initially none are completed
        assert_eq!(status.completed_items, 0);
    }

    #[test]
    fn test_is_completed_false_initially() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();
        generator.generate(&task).expect("generate task");

        let completed = generator
            .is_completed("test-task-1")
            .expect("check completion");

        assert!(!completed);
    }

    #[test]
    fn test_update_status_marks_item_done() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();
        generator.generate(&task).expect("generate task");

        // Update a specific item
        generator
            .update_status(
                "test-task-1",
                "在 tests 目录中补充/编写失败测试（Red）",
                true,
            )
            .expect("update status");

        // Verify the item is now marked as done
        let status = generator.parse_status("test-task-1").expect("parse status");
        assert!(status.completed_items > 0);

        // Verify the file content was updated
        let path = generator.tasks_dir.join("test-task-1").join("task.md");
        let content = fs::read_to_string(&path).expect("read file");
        assert!(content.contains("- [x] 在 tests 目录中补充/编写失败测试（Red）"));
    }

    #[test]
    fn test_update_status_marks_item_undone() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();
        generator.generate(&task).expect("generate task");

        // First mark as done
        generator
            .update_status(
                "test-task-1",
                "在 tests 目录中补充/编写失败测试（Red）",
                true,
            )
            .expect("update status");

        // Then mark as not done
        generator
            .update_status(
                "test-task-1",
                "在 tests 目录中补充/编写失败测试（Red）",
                false,
            )
            .expect("update status");

        // Verify the item is back to not done
        let path = generator.tasks_dir.join("test-task-1").join("task.md");
        let content = fs::read_to_string(&path).expect("read file");
        assert!(content.contains("- [ ] 在 tests 目录中补充/编写失败测试（Red）"));
        assert!(!content.contains("- [x] 在 tests 目录中补充/编写失败测试（Red）"));
    }

    #[test]
    fn test_update_status_item_not_found() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();
        generator.generate(&task).expect("generate task");

        let result = generator.update_status("test-task-1", "nonexistent item", true);

        assert!(result.is_err());
        match result {
            Err(CoreError::InvalidInput(msg)) => {
                assert!(msg.contains("nonexistent item"));
            }
            _ => panic!("Expected InvalidInput error"),
        }
    }

    #[test]
    fn test_update_status_task_not_found() {
        let (_temp_dir, generator) = create_test_generator();

        let result = generator.update_status("nonexistent", "some item", true);

        assert!(result.is_err());
        match result {
            Err(CoreError::TaskNotFound(id)) => assert_eq!(id, "nonexistent"),
            _ => panic!("Expected TaskNotFound error"),
        }
    }

    #[test]
    fn test_complete_all_items() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();
        generator.generate(&task).expect("generate task");

        // Get initial status to know all items
        let initial_status = generator.parse_status("test-task-1").expect("parse status");

        // Mark all items as done
        let path = generator.tasks_dir.join("test-task-1").join("task.md");
        let content = fs::read_to_string(&path).expect("read file");

        let updated_content = content.replace("- [ ]", "- [x]");
        fs::write(&path, updated_content).expect("write file");

        // Now should be fully completed
        let completed = generator
            .is_completed("test-task-1")
            .expect("check completion");
        assert!(completed);

        let final_status = generator.parse_status("test-task-1").expect("parse status");
        assert_eq!(final_status.total_items, initial_status.total_items);
        assert_eq!(final_status.completed_items, initial_status.total_items);
        assert!(final_status.is_fully_completed());
    }

    #[test]
    fn test_percentage_calculation() {
        // Test various percentage calculations
        assert_eq!(TaskStatus::new(4, 1).percentage, 25);
        assert_eq!(TaskStatus::new(4, 2).percentage, 50);
        assert_eq!(TaskStatus::new(4, 3).percentage, 75);
        assert_eq!(TaskStatus::new(3, 1).percentage, 33);
        assert_eq!(TaskStatus::new(7, 2).percentage, 28);
    }

    #[test]
    fn test_generate_with_dependencies() {
        let (_temp_dir, generator) = create_test_generator();
        let task = SubTask::new("task-with-deps", "实现登录API")
            .depends_on("task-1")
            .depends_on("task-2");

        let path = generator.generate(&task).expect("generate task");
        assert!(path.exists());
    }

    #[test]
    fn test_parse_status_after_partial_completion() {
        let (_temp_dir, generator) = create_test_generator();
        let task = create_test_task();
        generator.generate(&task).expect("generate task");

        // Get initial count
        let initial_status = generator.parse_status("test-task-1").expect("parse status");

        // Mark some items as done
        generator
            .update_status(
                "test-task-1",
                "在 tests 目录中补充/编写失败测试（Red）",
                true,
            )
            .expect("update status");
        generator
            .update_status(
                "test-task-1",
                "以最小改动实现功能直至测试通过（Green）",
                true,
            )
            .expect("update status");

        // Verify updated count
        let new_status = generator.parse_status("test-task-1").expect("parse status");
        assert_eq!(new_status.completed_items, 2);
        assert_eq!(new_status.total_items, initial_status.total_items);

        // Should not be fully completed yet
        assert!(!new_status.is_fully_completed());
    }
}
