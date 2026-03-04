//! Orchestrator module for task decomposition and DAG-based execution
//!
//! This module provides the core orchestration logic for breaking down
//! user requirements into subtasks and managing their execution order.

use std::collections::{HashMap, HashSet, VecDeque};

use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};

/// Unique identifier for a subtask
pub type SubTaskId = String;

/// Status of a subtask
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum SubTaskStatus {
    /// Task is waiting for dependencies to complete
    Pending,
    /// Task is currently running
    Running,
    /// Task completed successfully
    Completed,
    /// Task failed with an error
    Failed,
}

impl Default for SubTaskStatus {
    fn default() -> Self {
        Self::Pending
    }
}

/// A subtask represents a decomposed unit of work
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SubTask {
    /// Unique task identifier
    pub id: SubTaskId,
    /// Human-readable description
    pub description: String,
    /// Current status
    pub status: SubTaskStatus,
    /// IDs of tasks this task depends on
    pub depends_on: Vec<SubTaskId>,
    /// Optional test command to verify completion
    pub test_command: Option<String>,
    /// Execution result (output or error)
    pub result: Option<String>,
}

impl SubTask {
    /// Create a new subtask with the given id and description
    pub fn new(id: impl Into<String>, description: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            description: description.into(),
            status: SubTaskStatus::default(),
            depends_on: Vec::new(),
            test_command: None,
            result: None,
        }
    }

    /// Add a dependency to this task
    pub fn depends_on(mut self, task_id: impl Into<String>) -> Self {
        self.depends_on.push(task_id.into());
        self
    }

    /// Set the test command for this task
    pub fn with_test_command(mut self, command: impl Into<String>) -> Self {
        self.test_command = Some(command.into());
        self
    }
}

/// Task Directed Acyclic Graph (DAG)
///
/// Manages task dependencies and execution order using topological sorting.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskDag {
    /// All tasks indexed by ID
    tasks: HashMap<SubTaskId, SubTask>,
    /// Cached topological order
    topological_order: Vec<SubTaskId>,
}

impl TaskDag {
    /// Create a new empty DAG
    pub fn new() -> Self {
        Self {
            tasks: HashMap::new(),
            topological_order: Vec::new(),
        }
    }

    /// Create a DAG from a vector of tasks
    pub fn from_tasks(tasks: Vec<SubTask>) -> Result<Self> {
        let mut dag = Self::new();
        for task in tasks {
            dag.add_task(task)?;
        }
        dag.rebuild_topological_order()?;
        Ok(dag)
    }

    /// Add a task to the DAG
    pub fn add_task(&mut self, task: SubTask) -> Result<()> {
        if self.tasks.contains_key(&task.id) {
            return Err(CoreError::TaskAlreadyExists(task.id.clone()));
        }
        self.tasks.insert(task.id.clone(), task);
        Ok(())
    }

    /// Rebuild the topological order using Kahn's algorithm
    fn rebuild_topological_order(&mut self) -> Result<()> {
        let mut in_degree: HashMap<SubTaskId, usize> = HashMap::new();
        let mut adjacency: HashMap<SubTaskId, Vec<SubTaskId>> = HashMap::new();

        // Initialize
        for task_id in self.tasks.keys() {
            in_degree.entry(task_id.clone()).or_insert(0);
            adjacency.entry(task_id.clone()).or_insert_with(Vec::new);
        }

        // Build graph
        for task in self.tasks.values() {
            for dep_id in &task.depends_on {
                if !self.tasks.contains_key(dep_id) {
                    return Err(CoreError::TaskNotFound(dep_id.clone()));
                }
                adjacency.get_mut(dep_id).unwrap().push(task.id.clone());
                *in_degree.get_mut(&task.id).unwrap() += 1;
            }
        }

        // Kahn's algorithm
        let mut queue: VecDeque<SubTaskId> = in_degree
            .iter()
            .filter(|(_, &deg)| deg == 0)
            .map(|(id, _)| id.clone())
            .collect();

        let mut order = Vec::new();
        let mut visited_count = 0;

        while let Some(task_id) = queue.pop_front() {
            order.push(task_id.clone());
            visited_count += 1;

            if let Some(neighbors) = adjacency.get(&task_id) {
                for neighbor in neighbors {
                    let degree = in_degree.get_mut(neighbor).unwrap();
                    *degree -= 1;
                    if *degree == 0 {
                        queue.push_back(neighbor.clone());
                    }
                }
            }
        }

        if visited_count != self.tasks.len() {
            return Err(CoreError::CycleDetected(
                "Circular dependency detected in task graph".to_string(),
            ));
        }

        self.topological_order = order;
        Ok(())
    }

    /// Get tasks that are ready to execute (all dependencies completed)
    pub fn get_ready_tasks(&self) -> Vec<&SubTask> {
        self.tasks
            .values()
            .filter(|task| {
                if task.status != SubTaskStatus::Pending {
                    return false;
                }
                task.depends_on.iter().all(|dep_id| {
                    self.tasks
                        .get(dep_id)
                        .map(|t| t.status == SubTaskStatus::Completed)
                        .unwrap_or(false)
                })
            })
            .collect()
    }

    /// Get groups of tasks that can be executed in parallel
    pub fn get_parallel_groups(&self) -> Vec<Vec<&SubTask>> {
        let mut groups: Vec<Vec<&SubTask>> = Vec::new();
        let mut completed: HashSet<SubTaskId> = HashSet::new();

        loop {
            let ready: Vec<&SubTask> = self
                .tasks
                .values()
                .filter(|task| {
                    if completed.contains(&task.id) {
                        return false;
                    }
                    task.depends_on
                        .iter()
                        .all(|dep_id| completed.contains(dep_id))
                })
                .collect();

            if ready.is_empty() {
                break;
            }

            for task in &ready {
                completed.insert(task.id.clone());
            }
            groups.push(ready);
        }

        groups
    }

    /// Mark a task as completed with optional result
    pub fn mark_completed(&mut self, task_id: &str, result: Option<String>) -> Result<()> {
        let task = self
            .tasks
            .get_mut(task_id)
            .ok_or_else(|| CoreError::TaskNotFound(task_id.to_string()))?;
        task.status = SubTaskStatus::Completed;
        task.result = result;
        Ok(())
    }

    /// Get a task by ID
    pub fn get_task(&self, task_id: &str) -> Option<&SubTask> {
        self.tasks.get(task_id)
    }

    /// Get a mutable reference to a task by ID
    pub fn get_task_mut(&mut self, task_id: &str) -> Option<&mut SubTask> {
        self.tasks.get_mut(task_id)
    }

    /// Get all tasks in topological order
    pub fn tasks_in_order(&self) -> Vec<&SubTask> {
        self.topological_order
            .iter()
            .filter_map(|id| self.tasks.get(id))
            .collect()
    }

    /// Get total number of tasks
    pub fn len(&self) -> usize {
        self.tasks.len()
    }

    /// Check if DAG is empty
    pub fn is_empty(&self) -> bool {
        self.tasks.is_empty()
    }
}

impl Default for TaskDag {
    fn default() -> Self {
        Self::new()
    }
}

/// Orchestrator for decomposing requirements and managing task execution
pub struct Orchestrator;

impl Orchestrator {
    /// Parse a user requirement into subtasks
    ///
    /// This is a rule-based decomposition that splits common coding tasks
    /// into logical subtasks. For production use, this would be enhanced
    /// with LLM-based decomposition.
    pub fn parse_requirement(input: &str) -> Vec<SubTask> {
        let input_lower = input.to_lowercase();

        // Rule-based decomposition for common patterns
        if input_lower.contains("登录") || input_lower.contains("login") {
            Self::decompose_login_feature()
        } else if input_lower.contains("注册")
            || input_lower.contains("register")
            || input_lower.contains("signup")
        {
            Self::decompose_register_feature()
        } else {
            // Default: single task
            vec![SubTask::new("task-0", input)]
        }
    }

    /// Decompose "implement login feature" into subtasks
    fn decompose_login_feature() -> Vec<SubTask> {
        vec![
            SubTask::new("login-1", "定义用户登录相关的数据模型和类型")
                .with_test_command("cargo check"),
            SubTask::new("login-2", "实现用户认证服务（密码验证、Token生成）")
                .depends_on("login-1")
                .with_test_command("cargo test auth"),
            SubTask::new("login-3", "实现登录 API 接口")
                .depends_on("login-2")
                .with_test_command("cargo test login_api"),
            SubTask::new("login-4", "添加登录相关的单元测试")
                .depends_on("login-3")
                .with_test_command("cargo test"),
            SubTask::new("login-5", "集成测试和验收")
                .depends_on("login-4")
                .with_test_command("cargo test --all"),
        ]
    }

    /// Decompose "implement register feature" into subtasks
    fn decompose_register_feature() -> Vec<SubTask> {
        vec![
            SubTask::new("register-1", "定义用户注册相关的数据模型")
                .with_test_command("cargo check"),
            SubTask::new("register-2", "实现用户注册服务（输入验证、密码加密）")
                .depends_on("register-1")
                .with_test_command("cargo test register_service"),
            SubTask::new("register-3", "实现注册 API 接口")
                .depends_on("register-2")
                .with_test_command("cargo test register_api"),
            SubTask::new("register-4", "添加注册相关的单元测试")
                .depends_on("register-3")
                .with_test_command("cargo test"),
        ]
    }

    /// Build a DAG from a list of subtasks
    pub fn build_dag(tasks: Vec<SubTask>) -> Result<TaskDag> {
        TaskDag::from_tasks(tasks)
    }

    /// Mark a task as completed in the DAG
    pub fn mark_completed(dag: &mut TaskDag, task_id: &str, result: Option<String>) -> Result<()> {
        dag.mark_completed(task_id, result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_requirement_login() {
        let tasks = Orchestrator::parse_requirement("实现用户登录功能");

        assert!(!tasks.is_empty());
        assert!(tasks.iter().any(|t| t.description.contains("数据模型")));
        assert!(tasks.iter().any(|t| t.description.contains("认证服务")));
        assert!(tasks.iter().any(|t| t.description.contains("API")));
    }

    #[test]
    fn test_build_dag_topological_order() {
        let tasks = Orchestrator::parse_requirement("实现用户登录功能");
        let dag = Orchestrator::build_dag(tasks).expect("build dag");

        let ordered = dag.tasks_in_order();

        // 验证拓扑排序：login-1 应该在 login-2 之前
        let pos_1 = ordered.iter().position(|t| t.id == "login-1").unwrap();
        let pos_2 = ordered.iter().position(|t| t.id == "login-2").unwrap();
        assert!(pos_1 < pos_2, "login-1 should come before login-2");

        // login-2 应该在 login-3 之前
        let pos_3 = ordered.iter().position(|t| t.id == "login-3").unwrap();
        assert!(pos_2 < pos_3, "login-2 should come before login-3");
    }

    #[test]
    fn test_get_ready_tasks_initial() {
        let tasks = Orchestrator::parse_requirement("实现用户登录功能");
        let dag = Orchestrator::build_dag(tasks).expect("build dag");

        let ready = dag.get_ready_tasks();

        // 初始状态下，只有 login-1 没有依赖，应该 ready
        assert_eq!(ready.len(), 1);
        assert_eq!(ready[0].id, "login-1");
    }

    #[test]
    fn test_get_ready_tasks_after_completion() {
        let tasks = Orchestrator::parse_requirement("实现用户登录功能");
        let mut dag = Orchestrator::build_dag(tasks).expect("build dag");

        // 完成 login-1
        Orchestrator::mark_completed(&mut dag, "login-1", Some("done".to_string())).unwrap();

        let ready = dag.get_ready_tasks();

        // login-1 完成后，login-2 应该 ready
        assert_eq!(ready.len(), 1);
        assert_eq!(ready[0].id, "login-2");
    }

    #[test]
    fn test_mark_completed() {
        let tasks = Orchestrator::parse_requirement("实现用户登录功能");
        let mut dag = Orchestrator::build_dag(tasks).expect("build dag");

        Orchestrator::mark_completed(&mut dag, "login-1", Some("模型定义完成".to_string()))
            .unwrap();

        let task = dag.get_task("login-1").unwrap();
        assert_eq!(task.status, SubTaskStatus::Completed);
        assert_eq!(task.result, Some("模型定义完成".to_string()));
    }

    #[test]
    fn test_mark_completed_nonexistent_task() {
        let tasks = Orchestrator::parse_requirement("实现用户登录功能");
        let mut dag = Orchestrator::build_dag(tasks).expect("build dag");

        let result = Orchestrator::mark_completed(&mut dag, "nonexistent", None);

        assert!(result.is_err());
        match result {
            Err(CoreError::TaskNotFound(id)) => assert_eq!(id, "nonexistent"),
            _ => panic!("Expected TaskNotFound error"),
        }
    }

    #[test]
    fn test_detect_cycle() {
        let tasks = vec![
            SubTask::new("a", "Task A").depends_on("c"),
            SubTask::new("b", "Task B").depends_on("a"),
            SubTask::new("c", "Task C").depends_on("b"),
        ];

        let result = Orchestrator::build_dag(tasks);

        assert!(result.is_err());
        match result {
            Err(CoreError::CycleDetected(_)) => {}
            _ => panic!("Expected CycleDetected error"),
        }
    }

    #[test]
    fn test_parallel_groups() {
        let tasks = vec![
            SubTask::new("a", "Task A"),
            SubTask::new("b", "Task B"),
            SubTask::new("c", "Task C").depends_on("a").depends_on("b"),
        ];
        let dag = Orchestrator::build_dag(tasks).expect("build dag");

        let groups = dag.get_parallel_groups();

        // 第一组应该包含 a 和 b（可以并行）
        assert_eq!(groups[0].len(), 2);

        // 第二组应该只包含 c
        assert_eq!(groups[1].len(), 1);
        assert_eq!(groups[1][0].id, "c");
    }

    #[test]
    fn test_default_requirement() {
        let tasks = Orchestrator::parse_requirement("随机任务");

        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].description, "随机任务");
        assert!(tasks[0].depends_on.is_empty());
    }
}
