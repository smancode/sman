//! Orchestrator module for task decomposition and DAG-based execution
//!
//! This module provides the core orchestration logic for breaking down
//! user requirements into subtasks and managing their execution order.
//!
//! ## State Machine
//!
//! The orchestrator follows a clear state machine:
//! ```text
//! Idle -> Analyzing -> Splitting -> Dispatching -> Polling -> Evaluating -> Completed
//!    |                                                                      |
//!    +-------------------> Failed <-----------------------------------------+
//! ```

use std::path::PathBuf;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use smanclaw_types::{HistoryEntry, ProjectKnowledge};

use crate::acceptance_evaluator::{AcceptanceEvaluator, EvaluationResult};
use crate::error::{CoreError, Result};
use crate::main_task::{MainTask, MainTaskManager, MainTaskStatus, SubTaskRef};
use crate::project_explorer::ProjectExplorer;
use crate::skill_store::{Skill, SkillStore};
use crate::task_generator::TaskGenerator;
use crate::task_poller::TaskPoller;

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

/// Orchestrator state machine states
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum OrchestratorState {
    /// Initial idle state, waiting for user request
    Idle,
    /// Analyzing user request to extract subtasks
    Analyzing,
    /// Splitting subtasks into task files
    Splitting,
    /// Dispatching subtasks to sub-claws
    Dispatching,
    /// Polling for task completion
    Polling,
    /// Evaluating completed tasks against acceptance criteria
    Evaluating,
    /// All tasks completed successfully
    Completed,
    /// Task failed with error message
    Failed(String),
}

impl Default for OrchestratorState {
    fn default() -> Self {
        Self::Idle
    }
}

impl OrchestratorState {
    /// Check if orchestrator is in a terminal state
    pub fn is_terminal(&self) -> bool {
        matches!(self, Self::Completed | Self::Failed(_))
    }
}

/// Subtask specification for task generation
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubTaskSpec {
    /// Task title
    pub title: String,
    /// Detailed description
    pub description: String,
    /// Execution checklist items
    pub checklist: Vec<String>,
    /// Acceptance criteria for this subtask
    pub acceptance_criteria: Vec<String>,
    /// IDs of tasks this task depends on
    pub dependencies: Vec<String>,
}

impl SubTaskSpec {
    /// Create a new subtask specification
    pub fn new(title: impl Into<String>, description: impl Into<String>) -> Self {
        Self {
            title: title.into(),
            description: description.into(),
            checklist: Vec::new(),
            acceptance_criteria: Vec::new(),
            dependencies: Vec::new(),
        }
    }

    /// Add a checklist item
    pub fn with_checklist_item(mut self, item: impl Into<String>) -> Self {
        self.checklist.push(item.into());
        self
    }

    /// Add an acceptance criterion
    pub fn with_acceptance_criterion(mut self, criterion: impl Into<String>) -> Self {
        self.acceptance_criteria.push(criterion.into());
        self
    }

    /// Add a dependency
    pub fn with_dependency(mut self, dep: impl Into<String>) -> Self {
        self.dependencies.push(dep.into());
        self
    }
}

/// Orchestrator context for building execution context
#[derive(Debug, Clone)]
pub struct OrchestratorContext {
    /// Project information
    pub project_info: ProjectKnowledge,
    /// Relevant skills for the current task
    pub relevant_skills: Vec<Skill>,
    /// Recent conversation history
    pub recent_history: Vec<HistoryEntry>,
}

impl OrchestratorContext {
    /// Create a new orchestrator context
    pub fn new(project_info: ProjectKnowledge) -> Self {
        Self {
            project_info,
            relevant_skills: Vec::new(),
            recent_history: Vec::new(),
        }
    }
}

/// Result of polling subtasks
#[derive(Debug, Clone)]
pub enum OrchestratorPollResult {
    /// All subtasks completed
    AllCompleted,
    /// Some subtasks still in progress
    InProgress {
        /// Number of completed subtasks
        completed: usize,
        /// Total number of subtasks
        total: usize,
    },
    /// A subtask failed
    TaskFailed {
        /// Failed task ID
        task_id: String,
        /// Error message
        error: String,
    },
}

/// Orchestrator for decomposing requirements and managing task execution
pub struct Orchestrator {
    /// Project path
    project_path: PathBuf,
    /// Current state
    state: OrchestratorState,
    /// Current main task being processed
    current_main_task: Option<MainTask>,
    /// Project explorer for analyzing project
    project_explorer: ProjectExplorer,
    /// Skill store for accessing project skills
    skill_store: SkillStore,
    /// Task generator for creating task files
    task_generator: TaskGenerator,
    /// Task poller for monitoring task completion
    task_poller: Option<TaskPoller>,
    /// Dispatched subtask IDs
    dispatched_task_ids: Vec<String>,
}

impl Orchestrator {
    /// Create a new Orchestrator instance
    ///
    /// # Arguments
    /// * `project_path` - Path to the project root directory
    ///
    /// # Returns
    /// A new Orchestrator instance or an error
    pub fn new(project_path: &std::path::Path) -> Result<Self> {
        if !project_path.exists() {
            return Err(CoreError::InvalidInput(format!(
                "Project path does not exist: {}",
                project_path.display()
            )));
        }

        let project_explorer = ProjectExplorer::new();
        let skill_store = SkillStore::new(project_path)?;
        let task_generator = TaskGenerator::new(project_path)?;

        Ok(Self {
            project_path: project_path.to_path_buf(),
            state: OrchestratorState::Idle,
            current_main_task: None,
            project_explorer,
            skill_store,
            task_generator,
            task_poller: None,
            dispatched_task_ids: Vec::new(),
        })
    }

    /// Build the orchestrator context with project info, skills, and history
    ///
    /// # Returns
    /// OrchestratorContext containing all relevant information
    pub fn build_context(&self) -> Result<OrchestratorContext> {
        let project_info = self.project_explorer.explore(&self.project_path)?;

        let mut context = OrchestratorContext::new(project_info);

        // Load relevant skills
        let skill_metas = self.skill_store.list()?;
        for meta in skill_metas {
            if let Some(skill) = self.skill_store.get(&meta.id)? {
                context.relevant_skills.push(skill);
            }
        }

        Ok(context)
    }

    /// Handle a user request and execute the full orchestration cycle
    ///
    /// # Arguments
    /// * `user_request` - The user's original request
    ///
    /// # Returns
    /// The created MainTask or an error
    pub async fn handle_request(&mut self, user_request: &str) -> Result<MainTask> {
        if self.state != OrchestratorState::Idle {
            return Err(CoreError::InvalidInput(format!(
                "Orchestrator is not in Idle state, current state: {:?}",
                self.state
            )));
        }

        // Create main task
        let main_task_manager = MainTaskManager::new(&self.project_path)?;
        let main_task = main_task_manager.create(user_request)?;
        self.current_main_task = Some(main_task.clone());

        // Phase 1: Analyze request
        self.state = OrchestratorState::Analyzing;
        main_task_manager.update_status(&main_task.id, MainTaskStatus::Analyzing)?;

        let subtask_specs = self.analyze_request(user_request)?;

        // Phase 2: Split tasks
        self.state = OrchestratorState::Splitting;
        main_task_manager.update_status(&main_task.id, MainTaskStatus::Planning)?;

        let task_paths = self.split_tasks(subtask_specs.clone())?;

        // Add subtasks to main task
        for spec in &subtask_specs {
            let subtask_ref = SubTaskRef::new(&spec.title, &spec.description);
            main_task_manager.add_sub_task(&main_task.id, &subtask_ref)?;
        }

        // Phase 3: Dispatch subtasks
        self.state = OrchestratorState::Dispatching;
        main_task_manager.update_status(&main_task.id, MainTaskStatus::Executing)?;

        self.dispatch_sub_tasks(task_paths.clone())?;

        // Phase 4: Poll for completion
        self.state = OrchestratorState::Polling;

        let poll_result = self.poll_completion().await?;

        match poll_result {
            OrchestratorPollResult::AllCompleted => {
                // Phase 5: Evaluate
                self.state = OrchestratorState::Evaluating;
                main_task_manager.update_status(&main_task.id, MainTaskStatus::Verifying)?;

                let evaluation = self.evaluate()?;

                if evaluation.overall_passed {
                    self.state = OrchestratorState::Completed;
                    main_task_manager.update_status(&main_task.id, MainTaskStatus::Completed)?;
                } else {
                    // Handle partial completion
                    let failed_criteria: Vec<_> = evaluation
                        .criteria_results
                        .iter()
                        .filter(|r| r.status != crate::acceptance_evaluator::CriteriaStatus::Passed)
                        .collect();

                    self.state = OrchestratorState::Failed(format!(
                        "Evaluation failed: {} criteria not passed",
                        failed_criteria.len()
                    ));
                    main_task_manager.update_status(&main_task.id, MainTaskStatus::Failed)?;
                }
            }
            OrchestratorPollResult::InProgress { completed, total } => {
                self.state = OrchestratorState::Failed(format!(
                    "Tasks still in progress: {}/{} completed",
                    completed, total
                ));
                main_task_manager.update_status(&main_task.id, MainTaskStatus::Failed)?;
            }
            OrchestratorPollResult::TaskFailed { task_id, error } => {
                self.state =
                    OrchestratorState::Failed(format!("Task {} failed: {}", task_id, error));
                main_task_manager.update_status(&main_task.id, MainTaskStatus::Failed)?;
            }
        }

        // Return the final main task
        let final_task = main_task_manager
            .load(&main_task.id)?
            .ok_or_else(|| CoreError::TaskNotFound(main_task.id.clone()))?;

        Ok(final_task)
    }

    /// Analyze user request and generate subtask specifications
    ///
    /// This is a rule-based decomposition that splits common coding tasks
    /// into logical subtasks. For production use, this would be enhanced
    /// with LLM-based decomposition.
    ///
    /// # Arguments
    /// * `user_request` - The user's original request
    ///
    /// # Returns
    /// A list of SubTaskSpec representing the decomposed tasks
    fn analyze_request(&mut self, user_request: &str) -> Result<Vec<SubTaskSpec>> {
        let input_lower = user_request.to_lowercase();

        // Rule-based decomposition for common patterns
        // Support both English and Chinese keywords
        let specs = if input_lower.contains("login") || input_lower.contains("登录") {
            Self::decompose_login_feature()
        } else if input_lower.contains("register")
            || input_lower.contains("注册")
            || input_lower.contains("signup")
        {
            Self::decompose_register_feature()
        } else if input_lower.contains("api") {
            Self::decompose_api_feature()
        } else {
            // Default: single task
            vec![SubTaskSpec::new("task-0", user_request)
                .with_checklist_item("Analyze requirements")
                .with_checklist_item("Implement solution")
                .with_checklist_item("Test implementation")
                .with_acceptance_criterion("Task completed as specified")]
        };

        Ok(specs)
    }

    /// Decompose "implement login feature" into subtasks
    fn decompose_login_feature() -> Vec<SubTaskSpec> {
        vec![
            SubTaskSpec::new("login-1", "Define user login data models and types")
                .with_checklist_item("Define User struct")
                .with_checklist_item("Define LoginRequest struct")
                .with_checklist_item("Define LoginResponse struct")
                .with_acceptance_criterion("All types compile successfully")
                .with_acceptance_criterion("Types have necessary fields"),
            SubTaskSpec::new("login-2", "Implement user authentication service")
                .with_checklist_item("Implement password verification")
                .with_checklist_item("Implement token generation")
                .with_checklist_item("Add unit tests for auth service")
                .with_acceptance_criterion("Password verification works correctly")
                .with_acceptance_criterion("Token generation produces valid tokens")
                .with_dependency("login-1"),
            SubTaskSpec::new("login-3", "Implement login API endpoint")
                .with_checklist_item("Create login handler")
                .with_checklist_item("Add request validation")
                .with_checklist_item("Add error handling")
                .with_acceptance_criterion("API returns correct responses")
                .with_acceptance_criterion("Error cases are handled properly")
                .with_dependency("login-2"),
            SubTaskSpec::new("login-4", "Add integration tests")
                .with_checklist_item("Write end-to-end login test")
                .with_checklist_item("Test successful login flow")
                .with_checklist_item("Test failure scenarios")
                .with_acceptance_criterion("All tests pass")
                .with_dependency("login-3"),
        ]
    }

    /// Decompose "implement register feature" into subtasks
    fn decompose_register_feature() -> Vec<SubTaskSpec> {
        vec![
            SubTaskSpec::new("register-1", "Define user registration data models")
                .with_checklist_item("Define RegisterRequest struct")
                .with_checklist_item("Define User creation logic")
                .with_acceptance_criterion("Types compile successfully"),
            SubTaskSpec::new("register-2", "Implement user registration service")
                .with_checklist_item("Implement input validation")
                .with_checklist_item("Implement password hashing")
                .with_checklist_item("Implement user creation")
                .with_acceptance_criterion("Validation works correctly")
                .with_acceptance_criterion("Passwords are securely hashed")
                .with_dependency("register-1"),
            SubTaskSpec::new("register-3", "Implement registration API endpoint")
                .with_checklist_item("Create registration handler")
                .with_checklist_item("Add request validation")
                .with_checklist_item("Add error handling")
                .with_acceptance_criterion("API returns correct responses")
                .with_dependency("register-2"),
            SubTaskSpec::new("register-4", "Add tests for registration")
                .with_checklist_item("Write unit tests")
                .with_checklist_item("Write integration tests")
                .with_acceptance_criterion("All tests pass")
                .with_dependency("register-3"),
        ]
    }

    /// Decompose "implement API feature" into subtasks
    fn decompose_api_feature() -> Vec<SubTaskSpec> {
        vec![
            SubTaskSpec::new("api-1", "Design API schema")
                .with_checklist_item("Define request/response types")
                .with_checklist_item("Document API endpoints")
                .with_acceptance_criterion("Schema is well-defined"),
            SubTaskSpec::new("api-2", "Implement API handlers")
                .with_checklist_item("Create route handlers")
                .with_checklist_item("Add middleware")
                .with_acceptance_criterion("Handlers work correctly")
                .with_dependency("api-1"),
            SubTaskSpec::new("api-3", "Add API tests")
                .with_checklist_item("Write endpoint tests")
                .with_checklist_item("Test error cases")
                .with_acceptance_criterion("All tests pass")
                .with_dependency("api-2"),
        ]
    }

    /// Split subtask specifications into task.md files
    ///
    /// # Arguments
    /// * `specs` - List of subtask specifications
    ///
    /// # Returns
    /// Paths to the generated task files
    fn split_tasks(&mut self, specs: Vec<SubTaskSpec>) -> Result<Vec<PathBuf>> {
        let mut paths = Vec::new();

        for spec in &specs {
            let subtask =
                SubTask::new(&spec.title, &spec.description).with_test_command("cargo test");

            let path = self.task_generator.generate(&subtask)?;
            paths.push(path);
        }

        Ok(paths)
    }

    /// Dispatch subtasks for execution
    ///
    /// In the current implementation, this creates a TaskPoller for monitoring.
    /// Actual dispatch to sub-claws would be implemented separately.
    ///
    /// # Arguments
    /// * `task_paths` - Paths to the task files
    fn dispatch_sub_tasks(&mut self, task_paths: Vec<PathBuf>) -> Result<()> {
        // Extract task IDs from paths
        self.dispatched_task_ids = task_paths
            .iter()
            .filter_map(|p| p.file_stem())
            .filter_map(|s| s.to_str())
            .map(String::from)
            .collect();

        // Create task poller for monitoring
        self.task_poller = Some(TaskPoller::new(&self.project_path, Duration::from_secs(1))?);

        Ok(())
    }

    /// Poll for subtask completion
    ///
    /// # Returns
    /// PollResult indicating the status of all subtasks
    async fn poll_completion(&mut self) -> Result<OrchestratorPollResult> {
        let poller = self
            .task_poller
            .as_ref()
            .ok_or_else(|| CoreError::InvalidInput("TaskPoller not initialized".to_string()))?;

        if self.dispatched_task_ids.is_empty() {
            return Ok(OrchestratorPollResult::AllCompleted);
        }

        // For testing, we do a single check rather than waiting
        // In production, this would use poller.wait_all()
        let task_ids: Vec<&str> = self
            .dispatched_task_ids
            .iter()
            .map(String::as_str)
            .collect();

        let statuses = poller.check_all(&task_ids)?;

        let total = statuses.len();
        let completed = statuses.iter().filter(|s| s.is_completed()).count();

        // Check for any failures (in current implementation, we check if none completed and timeout)
        if total == 0 {
            return Ok(OrchestratorPollResult::AllCompleted);
        }

        if completed == total {
            Ok(OrchestratorPollResult::AllCompleted)
        } else if completed == 0 {
            // Simulate in-progress for testing
            Ok(OrchestratorPollResult::InProgress {
                completed: 0,
                total,
            })
        } else {
            Ok(OrchestratorPollResult::InProgress { completed, total })
        }
    }

    /// Evaluate completed tasks against acceptance criteria
    ///
    /// # Returns
    /// EvaluationResult containing the evaluation outcome
    fn evaluate(&mut self) -> Result<EvaluationResult> {
        let evaluator = AcceptanceEvaluator::new(&self.project_path);

        // If we have a main task, use its acceptance criteria
        let criteria = if let Some(ref task) = self.current_main_task {
            evaluator.extract_criteria(task)
        } else {
            vec![]
        };

        evaluator.evaluate(&criteria)
    }

    /// Handle a failure by generating remediation tasks
    ///
    /// # Arguments
    /// * `reason` - Description of the failure
    ///
    /// # Returns
    /// New subtask specifications for remediation
    fn handle_failure(&mut self, reason: &str) -> Result<Vec<SubTaskSpec>> {
        self.state = OrchestratorState::Failed(reason.to_string());

        // Generate remediation tasks
        Ok(vec![
            SubTaskSpec::new("remediation-1", "Analyze failure root cause")
                .with_checklist_item("Review error logs")
                .with_checklist_item("Identify root cause")
                .with_acceptance_criterion("Root cause identified"),
            SubTaskSpec::new("remediation-2", "Implement fix")
                .with_checklist_item("Apply fix for identified issue")
                .with_checklist_item("Verify fix")
                .with_acceptance_criterion("Issue resolved")
                .with_dependency("remediation-1"),
        ])
    }

    /// Get the current orchestrator state
    pub fn state(&self) -> &OrchestratorState {
        &self.state
    }

    /// Get the current main task
    pub fn current_task(&self) -> Option<&MainTask> {
        self.current_main_task.as_ref()
    }

    /// Reset the orchestrator to idle state
    pub fn reset(&mut self) {
        self.state = OrchestratorState::Idle;
        self.current_main_task = None;
        self.task_poller = None;
        self.dispatched_task_ids.clear();
    }

    /// Build a DAG from a list of subtasks (legacy compatibility)
    pub fn build_dag(tasks: Vec<SubTask>) -> Result<TaskDag> {
        TaskDag::from_tasks(tasks)
    }

    /// Mark a task as completed in the DAG (legacy compatibility)
    pub fn mark_completed(dag: &mut TaskDag, task_id: &str, result: Option<String>) -> Result<()> {
        dag.mark_completed(task_id, result)
    }

    /// Parse a user requirement into subtasks (legacy compatibility)
    pub fn parse_requirement(input: &str) -> Vec<SubTask> {
        let input_lower = input.to_lowercase();

        // Support both English and Chinese keywords
        if input_lower.contains("login") || input_lower.contains("登录") {
            Self::decompose_login_subtasks()
        } else if input_lower.contains("register")
            || input_lower.contains("注册")
            || input_lower.contains("signup")
        {
            Self::decompose_register_subtasks()
        } else {
            vec![SubTask::new("task-0", input)]
        }
    }

    fn decompose_login_subtasks() -> Vec<SubTask> {
        vec![
            SubTask::new("login-1", "Define user login related data models and types")
                .with_test_command("cargo check"),
            SubTask::new(
                "login-2",
                "Implement user authentication service (password verification, token generation)",
            )
            .depends_on("login-1")
            .with_test_command("cargo test auth"),
            SubTask::new("login-3", "Implement login API interface")
                .depends_on("login-2")
                .with_test_command("cargo test login_api"),
            SubTask::new("login-4", "Add login related unit tests")
                .depends_on("login-3")
                .with_test_command("cargo test"),
            SubTask::new("login-5", "Integration testing and acceptance")
                .depends_on("login-4")
                .with_test_command("cargo test --all"),
        ]
    }

    fn decompose_register_subtasks() -> Vec<SubTask> {
        vec![
            SubTask::new("register-1", "Define user registration related data models")
                .with_test_command("cargo check"),
            SubTask::new(
                "register-2",
                "Implement user registration service (input validation, password encryption)",
            )
            .depends_on("register-1")
            .with_test_command("cargo test register_service"),
            SubTask::new("register-3", "Implement registration API interface")
                .depends_on("register-2")
                .with_test_command("cargo test register_api"),
            SubTask::new("register-4", "Add registration related unit tests")
                .depends_on("register-3")
                .with_test_command("cargo test"),
        ]
    }
}

use std::collections::{HashMap, HashSet, VecDeque};

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

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_test_project() -> TempDir {
        let dir = TempDir::new().expect("create temp dir");
        // Create minimal project structure
        std::fs::write(
            dir.path().join("Cargo.toml"),
            "[package]\nname = \"test\"\nversion = \"0.1.0\"",
        )
        .unwrap();
        std::fs::create_dir(dir.path().join("src")).unwrap();
        std::fs::write(dir.path().join("src/main.rs"), "fn main() {}").unwrap();
        dir
    }

    // ==================== Orchestrator Tests ====================

    #[test]
    fn test_orchestrator_new() {
        let dir = create_test_project();
        let orchestrator = Orchestrator::new(dir.path());
        assert!(orchestrator.is_ok());
    }

    #[test]
    fn test_orchestrator_state_initial_is_idle() {
        let dir = create_test_project();
        let orchestrator = Orchestrator::new(dir.path()).unwrap();
        assert_eq!(orchestrator.state(), &OrchestratorState::Idle);
    }

    #[test]
    fn test_orchestrator_state_idle_is_not_terminal() {
        assert!(!OrchestratorState::Idle.is_terminal());
    }

    #[test]
    fn test_orchestrator_state_completed_is_terminal() {
        assert!(OrchestratorState::Completed.is_terminal());
    }

    #[test]
    fn test_orchestrator_state_failed_is_terminal() {
        assert!(OrchestratorState::Failed("error".to_string()).is_terminal());
    }

    #[test]
    fn test_orchestrator_build_context() {
        let dir = create_test_project();
        let orchestrator = Orchestrator::new(dir.path()).unwrap();
        let context = orchestrator.build_context();
        assert!(context.is_ok());
        let context = context.unwrap();
        assert_eq!(
            context.project_info.project_type,
            smanclaw_types::ProjectType::Rust
        );
    }

    #[test]
    fn test_analyze_request_returns_subtask_specs() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        let specs = orchestrator
            .analyze_request("Implement login feature")
            .unwrap();
        assert!(!specs.is_empty());
        assert!(specs.iter().any(|s| s.title.contains("login")));
    }

    #[test]
    fn test_analyze_request_login_decomposition() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        let specs = orchestrator
            .analyze_request("Implement user login")
            .unwrap();
        assert!(specs.len() >= 3);
    }

    #[test]
    fn test_analyze_request_register_decomposition() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        let specs = orchestrator
            .analyze_request("Implement user register")
            .unwrap();
        assert!(specs.len() >= 3);
    }

    #[test]
    fn test_analyze_request_default_single_task() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        let specs = orchestrator.analyze_request("Random task").unwrap();
        assert_eq!(specs.len(), 1);
    }

    #[test]
    fn test_split_tasks_generates_files() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        let specs = vec![
            SubTaskSpec::new("test-1", "Test task 1"),
            SubTaskSpec::new("test-2", "Test task 2"),
        ];
        let paths = orchestrator.split_tasks(specs).unwrap();
        assert_eq!(paths.len(), 2);
        for path in &paths {
            assert!(path.exists());
        }
    }

    #[test]
    fn test_dispatch_sub_tasks_creates_poller() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        let specs = vec![SubTaskSpec::new("test-1", "Test task")];
        let paths = orchestrator.split_tasks(specs).unwrap();
        let result = orchestrator.dispatch_sub_tasks(paths);
        assert!(result.is_ok());
        assert!(orchestrator.task_poller.is_some());
        assert!(!orchestrator.dispatched_task_ids.is_empty());
    }

    #[test]
    fn test_poll_completion_with_no_tasks() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        // Create poller but no dispatched tasks
        orchestrator.task_poller =
            Some(TaskPoller::new(dir.path(), Duration::from_secs(1)).unwrap());
        let rt = tokio::runtime::Runtime::new().unwrap();
        let result = rt.block_on(orchestrator.poll_completion()).unwrap();
        assert!(matches!(result, OrchestratorPollResult::AllCompleted));
    }

    #[test]
    fn test_evaluate_returns_result() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        orchestrator.current_main_task = Some(MainTask::new(dir.path(), "Test task"));
        let result = orchestrator.evaluate();
        assert!(result.is_ok());
    }

    #[test]
    fn test_handle_failure_generates_remediation() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        let specs = orchestrator.handle_failure("Test failure reason").unwrap();
        assert!(!specs.is_empty());
        assert!(specs.iter().any(|s| s.title.contains("remediation")));
        assert_eq!(
            orchestrator.state(),
            &OrchestratorState::Failed("Test failure reason".to_string())
        );
    }

    #[test]
    fn test_orchestrator_reset() {
        let dir = create_test_project();
        let mut orchestrator = Orchestrator::new(dir.path()).unwrap();
        orchestrator.state = OrchestratorState::Analyzing;
        orchestrator.dispatched_task_ids.push("test".to_string());
        orchestrator.reset();
        assert_eq!(orchestrator.state(), &OrchestratorState::Idle);
        assert!(orchestrator.dispatched_task_ids.is_empty());
    }

    #[test]
    fn test_orchestrator_current_task() {
        let dir = create_test_project();
        let orchestrator = Orchestrator::new(dir.path()).unwrap();
        assert!(orchestrator.current_task().is_none());
    }

    // ==================== SubTask Tests ====================

    #[test]
    fn test_subtask_new() {
        let task = SubTask::new("task-1", "Test task");
        assert_eq!(task.id, "task-1");
        assert_eq!(task.description, "Test task");
        assert_eq!(task.status, SubTaskStatus::Pending);
        assert!(task.depends_on.is_empty());
        assert!(task.test_command.is_none());
    }

    #[test]
    fn test_subtask_with_dependencies() {
        let task = SubTask::new("task-2", "Dependent task")
            .depends_on("task-1")
            .depends_on("task-0");
        assert_eq!(task.depends_on.len(), 2);
    }

    #[test]
    fn test_subtask_with_test_command() {
        let task = SubTask::new("task-1", "Test task").with_test_command("cargo test");
        assert_eq!(task.test_command, Some("cargo test".to_string()));
    }

    // ==================== SubTaskSpec Tests ====================

    #[test]
    fn test_subtask_spec_new() {
        let spec = SubTaskSpec::new("Test", "Description");
        assert_eq!(spec.title, "Test");
        assert_eq!(spec.description, "Description");
        assert!(spec.checklist.is_empty());
        assert!(spec.acceptance_criteria.is_empty());
        assert!(spec.dependencies.is_empty());
    }

    #[test]
    fn test_subtask_spec_builders() {
        let spec = SubTaskSpec::new("Test", "Description")
            .with_checklist_item("Item 1")
            .with_acceptance_criterion("Criterion 1")
            .with_dependency("dep-1");
        assert_eq!(spec.checklist.len(), 1);
        assert_eq!(spec.acceptance_criteria.len(), 1);
        assert_eq!(spec.dependencies.len(), 1);
    }

    // ==================== OrchestratorContext Tests ====================

    #[test]
    fn test_orchestrator_context_new() {
        let project_info = ProjectKnowledge::default();
        let context = OrchestratorContext::new(project_info.clone());
        assert_eq!(context.project_info, project_info);
        assert!(context.relevant_skills.is_empty());
        assert!(context.recent_history.is_empty());
    }

    // ==================== Legacy Compatibility Tests ====================

    #[test]
    fn test_parse_requirement_login() {
        let tasks = Orchestrator::parse_requirement("Implement user login feature");

        assert!(!tasks.is_empty());
        assert!(tasks.iter().any(|t| t.description.contains("data models")));
        assert!(tasks
            .iter()
            .any(|t| t.description.contains("authentication")));
        assert!(tasks.iter().any(|t| t.description.contains("API")));
    }

    #[test]
    fn test_parse_requirement_default() {
        let tasks = Orchestrator::parse_requirement("Random task");

        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].description, "Random task");
        assert!(tasks[0].depends_on.is_empty());
    }

    // ==================== TaskDag Tests ====================

    #[test]
    fn test_dag_new() {
        let dag = TaskDag::new();
        assert!(dag.is_empty());
        assert_eq!(dag.len(), 0);
    }

    #[test]
    fn test_dag_add_task() {
        let mut dag = TaskDag::new();
        let task = SubTask::new("task-1", "Test");
        let result = dag.add_task(task);
        assert!(result.is_ok());
        assert_eq!(dag.len(), 1);
    }

    #[test]
    fn test_dag_add_duplicate_task() {
        let mut dag = TaskDag::new();
        dag.add_task(SubTask::new("task-1", "Test")).unwrap();
        let result = dag.add_task(SubTask::new("task-1", "Duplicate"));
        assert!(result.is_err());
    }

    #[test]
    fn test_dag_from_tasks() {
        let tasks = vec![SubTask::new("a", "Task A"), SubTask::new("b", "Task B")];
        let dag = TaskDag::from_tasks(tasks);
        assert!(dag.is_ok());
        assert_eq!(dag.unwrap().len(), 2);
    }

    #[test]
    fn test_dag_get_ready_tasks_initial() {
        let tasks = vec![
            SubTask::new("a", "Task A"),
            SubTask::new("b", "Task B").depends_on("a"),
        ];
        let dag = TaskDag::from_tasks(tasks).unwrap();

        let ready = dag.get_ready_tasks();
        assert_eq!(ready.len(), 1);
        assert_eq!(ready[0].id, "a");
    }

    #[test]
    fn test_dag_get_ready_tasks_after_completion() {
        let tasks = vec![
            SubTask::new("a", "Task A"),
            SubTask::new("b", "Task B").depends_on("a"),
        ];
        let mut dag = TaskDag::from_tasks(tasks).unwrap();

        dag.mark_completed("a", None).unwrap();
        let ready = dag.get_ready_tasks();
        assert_eq!(ready.len(), 1);
        assert_eq!(ready[0].id, "b");
    }

    #[test]
    fn test_dag_mark_completed() {
        let tasks = vec![SubTask::new("a", "Task A")];
        let mut dag = TaskDag::from_tasks(tasks).unwrap();

        let result = dag.mark_completed("a", Some("done".to_string()));
        assert!(result.is_ok());
        let task = dag.get_task("a").unwrap();
        assert_eq!(task.status, SubTaskStatus::Completed);
        assert_eq!(task.result, Some("done".to_string()));
    }

    #[test]
    fn test_dag_mark_completed_nonexistent() {
        let mut dag = TaskDag::new();
        let result = dag.mark_completed("nonexistent", None);
        assert!(result.is_err());
    }

    #[test]
    fn test_dag_detect_cycle() {
        let tasks = vec![
            SubTask::new("a", "Task A").depends_on("c"),
            SubTask::new("b", "Task B").depends_on("a"),
            SubTask::new("c", "Task C").depends_on("b"),
        ];

        let result = TaskDag::from_tasks(tasks);
        assert!(result.is_err());
        assert!(matches!(result, Err(CoreError::CycleDetected(_))));
    }

    #[test]
    fn test_dag_parallel_groups() {
        let tasks = vec![
            SubTask::new("a", "Task A"),
            SubTask::new("b", "Task B"),
            SubTask::new("c", "Task C").depends_on("a").depends_on("b"),
        ];
        let dag = TaskDag::from_tasks(tasks).unwrap();

        let groups = dag.get_parallel_groups();
        assert_eq!(groups.len(), 2);
        assert_eq!(groups[0].len(), 2); // a and b can run in parallel
        assert_eq!(groups[1].len(), 1); // c runs after both complete
    }

    #[test]
    fn test_dag_tasks_in_order() {
        let tasks = vec![
            SubTask::new("a", "Task A"),
            SubTask::new("b", "Task B").depends_on("a"),
            SubTask::new("c", "Task C").depends_on("b"),
        ];
        let dag = TaskDag::from_tasks(tasks).unwrap();

        let ordered = dag.tasks_in_order();
        assert_eq!(ordered.len(), 3);

        // Verify topological order
        let pos_a = ordered.iter().position(|t| t.id == "a").unwrap();
        let pos_b = ordered.iter().position(|t| t.id == "b").unwrap();
        let pos_c = ordered.iter().position(|t| t.id == "c").unwrap();
        assert!(pos_a < pos_b);
        assert!(pos_b < pos_c);
    }

    #[test]
    fn test_dag_get_task() {
        let tasks = vec![SubTask::new("a", "Task A")];
        let dag = TaskDag::from_tasks(tasks).unwrap();

        assert!(dag.get_task("a").is_some());
        assert!(dag.get_task("nonexistent").is_none());
    }

    #[test]
    fn test_dag_get_task_mut() {
        let tasks = vec![SubTask::new("a", "Task A")];
        let mut dag = TaskDag::from_tasks(tasks).unwrap();

        let task = dag.get_task_mut("a");
        assert!(task.is_some());
        if let Some(t) = task {
            t.status = SubTaskStatus::Running;
        }
        assert_eq!(dag.get_task("a").unwrap().status, SubTaskStatus::Running);
    }
}
