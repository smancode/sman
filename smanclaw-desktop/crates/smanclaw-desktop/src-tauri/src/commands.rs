//! Tauri commands for frontend communication

use chrono::Utc;
use smanclaw_core::{
    AcceptanceEvaluator, Experience, ExperienceSink, IdentityFiles, LearnedItem, MainTaskManager,
    MainTaskResult, MainTaskStatus, Orchestrator, Skill, SkillMeta, SkillStore,
    SubClawExecutor, SubTask, SubTaskRef, SubTaskStatus, TaskDag, TaskGenerator,
    TaskResultForExperience, UserExperienceExtractor, VerificationMethod,
};
use smanclaw_ffi::{ZeroclawBridge, ZeroclawStepExecutor};
use smanclaw_types::{
    FileAction, Task, TaskStatus,
};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tauri::{AppHandle, State};
use tokio::sync::RwLock;

use crate::error::{TauriError, TauriResult};
use crate::events::{
    emit_file_change, emit_orchestration_progress, emit_progress_event, emit_subtask_completed,
    emit_subtask_started, emit_task_dag, emit_task_status, emit_test_result, SubTaskInfo,
};
use crate::state::AppState;

mod chat_execution;
pub(crate) mod conversation_commands;
mod history_runtime;
mod orchestration_decompose;
pub(crate) mod project_commands;
pub(crate) mod settings_commands;
pub use project_commands::{
    add_project, get_project_config, get_projects, remove_project, update_project_config,
};
pub(crate) use history_runtime::{open_project_history_store, resolve_conversation_project};

// ============================================================================
// Project Commands
// ============================================================================

fn subtask_file_stem(main_task_id: &str, sequence: usize) -> String {
    format!("task-{}-{:03}", main_task_id, sequence)
}

fn subtask_relative_path(file_stem: &str) -> String {
    format!(".smanclaw/tasks/{}.md", file_stem)
}

// ============================================================================
// Task Commands
// ============================================================================

/// Execute a task
#[tauri::command(rename_all = "snake_case")]
pub async fn execute_task(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    project_id: String,
    input: String,
) -> TauriResult<Task> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }
    if input.is_empty() {
        return Err(TauriError::InvalidInput(
            "Task input cannot be empty".to_string(),
        ));
    }

    // Get project path and settings
    let (project_path, settings) = {
        let pm = state.project_manager.lock().await;
        let project = pm
            .get_project(&project_id)?
            .ok_or_else(|| TauriError::ProjectNotFound(project_id.clone()))?;
        let path = PathBuf::from(&project.path);
        drop(pm);

        let ss = state.settings_store.lock().await;
        let settings = ss.load()?;
        (path, settings)
    };

    // Create task
    let task = {
        let mut tm = state.task_manager.lock().await;
        tm.create_task(&project_id, &input)?
    };
    let task_id = task.id.clone();

    // Update project last accessed
    {
        let mut pm = state.project_manager.lock().await;
        pm.touch_project(&project_id)?;
    }

    // Update task status to running before spawning execution
    if let Err(e) = state.task_manager.lock().await.update_task_status(&task_id, TaskStatus::Running) {
        tracing::error!("Failed to update task status to running: {}", e);
    }

    // Emit task started event
    emit_task_status(&app_handle, &task_id, "running", None)?;

    // Get or create cached bridge for this project (maintains conversation history)
    let bridge = {
        let mut bridges = state.zeroclaw_bridges.lock().await;
        if let Some(existing) = bridges.get(&project_id) {
            existing.clone()
        } else {
            let new_bridge = Arc::new(ZeroclawBridge::from_project_with_settings(
                &project_path,
                &settings,
            )?);
            bridges.insert(project_id.clone(), new_bridge.clone());
            new_bridge
        }
    };
    let (tx, mut rx) = tokio::sync::mpsc::channel(64);

    let app_handle_clone = app_handle.clone();
    let task_manager = state.task_manager.clone();
    let input_clone = input.clone();

    tokio::spawn(async move {
        // Forward progress events
        let forward_handle = {
            let app_handle = app_handle_clone.clone();
            tokio::spawn(async move {
                while let Some(event) = rx.recv().await {
                    if let Err(e) = emit_progress_event(&app_handle, &event) {
                        tracing::error!("Failed to emit progress event: {}", e);
                    }
                }
            })
        };

        // Execute task
        match bridge.execute_task_stream(&task_id, &input_clone, tx).await {
            Ok(result) => {
                tracing::info!("execute_task: bridge.execute_task_stream succeeded, output length: {}, output: {}", result.output.len(), result.output);

                // Update task with result (status + output/error)
                let status = if result.success {
                    TaskStatus::Completed
                } else {
                    TaskStatus::Failed
                };
                let output = if result.success {
                    Some(result.output.clone())
                } else {
                    None
                };
                let error = if !result.success {
                    result.error.clone()
                } else {
                    None
                };

                if let Err(e) = task_manager
                    .lock()
                    .await
                    .update_task_result(&task_id, status, output, error)
                {
                    tracing::error!("Failed to update task result: {}", e);
                }

                // Emit file change events
                for change in &result.files_changed {
                    let action = match change.action {
                        FileAction::Created => "created",
                        FileAction::Modified => "modified",
                        FileAction::Deleted => "deleted",
                    };
                    if let Err(e) = emit_file_change(&app_handle_clone, &change.path, action) {
                        tracing::error!("Failed to emit file change event: {}", e);
                    }
                }

                // Emit completion event
                let (status_str, message) = match result.success {
                    true => ("completed", Some(result.output.clone())),
                    false => ("failed", result.error.clone()),
                };
                tracing::info!("Emitting task status: task_id={}, status={}, message={}", task_id, status_str, message.as_deref().unwrap_or(""));
                if let Err(e) = emit_task_status(&app_handle_clone, &task_id, status_str, message) {
                    tracing::error!("Failed to emit task status event: {}", e);
                }
            }
            Err(e) => {
                tracing::error!("Task execution failed: {}", e);
                if let Err(update_err) = task_manager
                    .lock()
                    .await
                    .update_task_result(&task_id, TaskStatus::Failed, None, Some(e.to_string()))
                {
                    tracing::error!("Failed to update task result: {}", update_err);
                }
                if let Err(emit_err) =
                    emit_task_status(&app_handle_clone, &task_id, "failed", Some(e.to_string()))
                {
                    tracing::error!("Failed to emit task status event: {}", emit_err);
                }
            }
        }

        // Wait for event forwarding to complete
        forward_handle.await.ok();
    });

    Ok(task)
}

/// Get task status
#[tauri::command(rename_all = "snake_case")]
pub async fn get_task(state: State<'_, AppState>, task_id: String) -> TauriResult<Option<Task>> {
    if task_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Task ID cannot be empty".to_string(),
        ));
    }

    let tm = state.task_manager.lock().await;
    let task = tm.get_task(&task_id)?;
    Ok(task)
}

/// List tasks for a project
#[tauri::command(rename_all = "snake_case")]
pub async fn list_tasks(state: State<'_, AppState>, project_id: String) -> TauriResult<Vec<Task>> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let tm = state.task_manager.lock().await;
    let tasks = tm.list_tasks(&project_id)?;
    Ok(tasks)
}

/// Cancel a running task
#[tauri::command(rename_all = "snake_case")]
pub async fn cancel_task(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    task_id: String,
) -> TauriResult<()> {
    if task_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Task ID cannot be empty".to_string(),
        ));
    }

    // Update task status to failed (cancelled)
    {
        let mut tm = state.task_manager.lock().await;
        tm.update_task_status(&task_id, TaskStatus::Failed)?;
    }

    // Emit cancelled event
    emit_task_status(&app_handle, &task_id, "cancelled", Some("Task cancelled by user".to_string()))?;

    Ok(())
}

// ============================================================================
// Utility Commands
// ============================================================================

/// Get application version
#[tauri::command]
pub fn get_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Check if a path exists
#[tauri::command]
pub fn path_exists(path: String) -> bool {
    PathBuf::from(&path).exists()
}

/// Select a folder using native dialog
#[tauri::command]
pub async fn select_folder(app_handle: AppHandle) -> TauriResult<Option<String>> {
    use tauri_plugin_dialog::DialogExt;

    let folder = app_handle.dialog().file().blocking_pick_folder();

    Ok(folder.map(|p| p.to_string()))
}

// ============================================================================
// Orchestration Commands
// ============================================================================

/// Global storage for active orchestrations (task_id -> TaskDag)
/// Uses RwLock for concurrent read access from multiple commands
static ORCHESTRATION_DAGS: once_cell::sync::Lazy<Arc<RwLock<HashMap<String, TaskDag>>>> =
    once_cell::sync::Lazy::new(|| Arc::new(RwLock::new(HashMap::new())));

/// Result of execute_orchestrated_task command
#[derive(Debug, Clone, serde::Serialize)]
pub struct OrchestratedTaskResult {
    /// Task ID
    pub task_id: String,
    /// Number of subtasks
    pub subtask_count: usize,
    /// Parallel execution groups
    pub parallel_groups: Vec<Vec<String>>,
}

/// DAG response for get_task_dag command
#[derive(Debug, Clone, serde::Serialize)]
pub struct TaskDagResponse {
    /// Task ID
    pub task_id: String,
    /// All subtasks
    pub tasks: Vec<SubTaskInfo>,
    /// Parallel execution groups
    pub parallel_groups: Vec<Vec<String>>,
    /// Overall progress
    pub progress: OrchestrationProgress,
}

/// Progress information
#[derive(Debug, Clone, serde::Serialize)]
pub struct OrchestrationProgress {
    /// Completed subtasks
    pub completed: usize,
    /// Total subtasks
    pub total: usize,
    /// Percentage (0.0 - 1.0)
    pub percent: f32,
}

fn persist_user_memory(
    project_path: &std::path::Path,
    raw_input: &str,
    extracted_content: &str,
    tags: &[String],
) {
    let skill_store = match SkillStore::new(project_path) {
        Ok(store) => store,
        Err(e) => {
            tracing::error!("Failed to create SkillStore for user memory: {}", e);
            return;
        }
    };
    let sink = ExperienceSink::new(skill_store);
    let mut experience = Experience::new("user-input");
    let mut learned = LearnedItem::new("user-preference", extracted_content.to_string());
    for tag in tags {
        learned = learned.with_tag(tag.clone());
    }
    experience.add_learned(learned);
    experience.add_pattern(raw_input.to_string());
    if let Err(e) = sink.update_memory(project_path, &experience) {
        tracing::error!("Failed to persist user memory: {}", e);
    }
}

fn is_quick_agent_note_candidate(input: &str) -> bool {
    let text = input.trim();
    if text.is_empty() {
        return false;
    }
    let lowered = text.to_lowercase();
    let is_question = lowered.contains("是哪个")
        || lowered.contains("在哪")
        || lowered.contains("在哪里")
        || lowered.contains("怎么找")
        || lowered.contains("哪个文件")
        || lowered.contains("哪个接口")
        || lowered.contains("接口是");
    let has_locator = lowered.contains("接口")
        || lowered.contains("路径")
        || lowered.contains(".xml")
        || lowered.contains(".java")
        || lowered.contains(".sql")
        || lowered.contains(".yaml")
        || lowered.contains(".yml")
        || lowered.contains(".json");
    let is_short = text.chars().count() <= 120;
    let is_complex = lowered.contains("完整流程")
        || lowered.contains("跨模块")
        || lowered.contains("端到端")
        || lowered.contains("会计核算")
        || lowered.contains("业务流程");
    is_short && is_question && has_locator && !is_complex
}

fn persist_agent_quick_note(project_path: &std::path::Path, input: &str) {
    let identity = IdentityFiles::new(project_path);
    let mut content = identity.read_agents().unwrap_or_default();
    let note = input.trim().replace('\n', " ");
    if note.is_empty() {
        return;
    }
    let note_line = format!("- {note}\n");
    if content.contains(&note_line) {
        return;
    }
    let section_title = "## 业务快速索引";
    if !content.contains(section_title) {
        if !content.is_empty() && !content.ends_with('\n') {
            content.push('\n');
        }
        content.push('\n');
        content.push_str(section_title);
        content.push_str("\n\n");
    } else if !content.ends_with('\n') {
        content.push('\n');
    }
    content.push_str(&note_line);
    if let Err(e) = identity.write_agents(&content) {
        tracing::error!("Failed to persist AGENTS quick note: {}", e);
    }
}

pub(crate) fn persist_user_input_experience(project_path: &std::path::Path, input: &str) {
    if is_quick_agent_note_candidate(input) {
        persist_agent_quick_note(project_path, input);
        let tags = vec!["quick-note".to_string(), "agents".to_string()];
        persist_user_memory(project_path, input, input, &tags);
        return;
    }
    if let Ok(skill_store) = SkillStore::new(project_path) {
        let extractor = match SkillStore::for_paths(project_path) {
            Ok(path_store) => UserExperienceExtractor::new(skill_store).with_path_store(path_store),
            Err(_) => UserExperienceExtractor::new(skill_store),
        };
        if let Some(experience) = extractor.analyze(input) {
            if let Err(e) = extractor.store_as_skill(&experience) {
                tracing::error!("Failed to persist user experience: {}", e);
            }
            persist_user_memory(project_path, input, &experience.content, &experience.tags);
        }
    }
}

fn should_generate_path_from_task_experience(experience: &Experience) -> bool {
    if !experience.reusable_patterns.is_empty() {
        return true;
    }
    experience
        .learned
        .iter()
        .any(|item| item.content.contains("必须") || item.content.contains("默认") || item.tags.len() >= 2)
}

fn persist_path_from_task_experience(project_path: &std::path::Path, experience: &Experience) {
    if !should_generate_path_from_task_experience(experience) {
        return;
    }
    let path_store = match SkillStore::for_paths(project_path) {
        Ok(store) => store,
        Err(e) => {
            tracing::error!("Failed to create path store from task experience: {}", e);
            return;
        }
    };
    let timestamp = Utc::now().timestamp_millis();
    let source = sanitize_path_fragment(&experience.source_task);
    let id = format!("path-task-{}-{}", source, timestamp);
    let path = format!("auto/operation-{}-{}.md", source, timestamp);

    let learned_lines = experience
        .learned
        .iter()
        .map(|item| format!("- [{}] {}", item.category, item.content))
        .collect::<Vec<_>>()
        .join("\n");
    let pattern_lines = experience
        .reusable_patterns
        .iter()
        .map(|pattern| format!("- {}", pattern))
        .collect::<Vec<_>>()
        .join("\n");

    let content = format!(
        "# Path - {}\n\n## 来源任务\n\n{}\n\n## 可复用步骤\n\n{}\n\n## 关键模式\n\n{}\n",
        experience.source_task,
        experience.source_task,
        if learned_lines.is_empty() { "- 无".to_string() } else { learned_lines },
        if pattern_lines.is_empty() { "- 无".to_string() } else { pattern_lines },
    );
    let mut tags = vec![
        "path".to_string(),
        "auto-generated".to_string(),
        "task-experience".to_string(),
    ];
    tags.extend(
        experience
            .learned
            .iter()
            .flat_map(|item| item.tags.iter().cloned()),
    );
    tags.sort();
    tags.dedup();

    let skill = Skill {
        meta: SkillMeta {
            id,
            path,
            tags,
            learned_from: experience.source_task.clone(),
            updated_at: Utc::now().timestamp(),
        },
        content,
    };
    if let Err(e) = path_store.create(&skill) {
        tracing::error!("Failed to persist path from task experience: {}", e);
    }
}

fn sanitize_path_fragment(raw: &str) -> String {
    let cleaned = raw
        .to_lowercase()
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() {
                c
            } else {
                '-'
            }
        })
        .collect::<String>();
    let compact = cleaned
        .split('-')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    if compact.is_empty() {
        "task".to_string()
    } else {
        compact
    }
}

fn persist_task_experience_artifacts(
    project_path: &std::path::Path,
    sink: &ExperienceSink,
    experience: &Experience,
) {
    if sink.should_update_skill(experience) {
        if let Err(e) = sink.update_skill(experience) {
            tracing::error!("Failed to update skill from experience: {}", e);
        }
    }
    if experience.is_valuable() {
        if let Err(e) = sink.update_memory(project_path, experience) {
            tracing::error!("Failed to update MEMORY from experience: {}", e);
        }
        persist_path_from_task_experience(project_path, experience);
    }
}

fn build_remediation_subtasks(
    dag: &TaskDag,
    evaluation: Option<&smanclaw_core::EvaluationResult>,
    round: usize,
) -> Vec<SubTask> {
    let mut remediation_tasks = Vec::new();
    let mut seen_descriptions = HashSet::new();

    for task in dag
        .tasks_in_order()
        .iter()
        .filter(|task| task.status == SubTaskStatus::Failed)
    {
        let id = format!("remediate-r{}-{}", round, task.id);
        let description = format!("修复子任务 {} 的失败原因并补齐验证", task.id);
        if seen_descriptions.insert(description.clone()) {
            remediation_tasks.push(SubTask::new(id, description).with_test_command("cargo test"));
        }
    }

    if let Some(result) = evaluation {
        for (idx, recommendation) in result.recommendations.iter().enumerate() {
            let id = format!("remediate-r{}-eval-{}", round, idx + 1);
            let description = format!("根据验收建议补救: {}", recommendation);
            if seen_descriptions.insert(description.clone()) {
                remediation_tasks
                    .push(SubTask::new(id, description).with_test_command("cargo test"));
            }
        }
    }

    if remediation_tasks.is_empty() {
        remediation_tasks.push(
            SubTask::new(
                format!("remediate-r{}-general", round),
                "补充失败场景测试并修复未通过验收项",
            )
                .with_test_command("cargo test"),
        );
    }

    orchestration_decompose::enforce_subtask_context_independence(
        remediation_tasks,
        "补救失败子任务并完成验收回归",
    )
}

/// Execute an orchestrated task with automatic decomposition
#[tauri::command(rename_all = "snake_case")]
pub async fn execute_orchestrated_task(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    project_id: String,
    input: String,
) -> TauriResult<OrchestratedTaskResult> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }
    if input.is_empty() {
        return Err(TauriError::InvalidInput(
            "Task input cannot be empty".to_string(),
        ));
    }

    // Get project path and settings
    let (project_path, settings) = {
        let pm = state.project_manager.lock().await;
        let project = pm
            .get_project(&project_id)?
            .ok_or_else(|| TauriError::ProjectNotFound(project_id.clone()))?;
        let path = PathBuf::from(&project.path);
        drop(pm);
        let ss = state.settings_store.lock().await;
        let settings = ss.load()?;
        (path, settings)
    };

    persist_user_input_experience(&project_path, &input);

    // Create task
    let task = {
        let mut tm = state.task_manager.lock().await;
        tm.create_task(&project_id, &input)?
    };
    let task_id = task.id.clone();

    // Update project last accessed
    {
        let mut pm = state.project_manager.lock().await;
        pm.touch_project(&project_id)?;
    }

    if let Err(e) = state
        .task_manager
        .lock()
        .await
        .update_task_status(&task_id, TaskStatus::Running)
    {
        tracing::error!("Failed to update task status to running: {}", e);
    }

    if let Err(e) = emit_task_status(
        &app_handle,
        &task_id,
        "running",
        Some("主 Claw 正在进行语义拆解与依赖分析...".to_string()),
    ) {
        tracing::error!("Failed to emit decomposition status event: {}", e);
    }

    // Parse requirement and build DAG
    let subtasks = match orchestration_decompose::try_semantic_decompose_with_zeroclaw(
        &project_path,
        &settings,
        &input,
    )
    .await
    {
        Some(tasks) => tasks,
        None => orchestration_decompose::fallback_decompose_subtasks(&input),
    };
    let dag = Orchestrator::build_dag(subtasks.clone())?;
    let parallel_groups: Vec<Vec<String>> = dag
        .get_parallel_groups()
        .iter()
        .map(|group| group.iter().map(|t| t.id.clone()).collect())
        .collect();

    // Store DAG for later queries
    {
        let mut dags = ORCHESTRATION_DAGS.write().await;
        dags.insert(task_id.clone(), dag);
    }

    // Emit initial task DAG event
    let tasks_info: Vec<SubTaskInfo> = subtasks
        .iter()
        .map(|t| SubTaskInfo {
            id: t.id.clone(),
            description: t.description.clone(),
            status: "pending".to_string(),
            depends_on: t.depends_on.clone(),
        })
        .collect();

    let main_task_manager = MainTaskManager::new(&project_path)?;
    let main_task = main_task_manager.create(&input)?;
    let subtask_file_stems: HashMap<String, String> = subtasks
        .iter()
        .enumerate()
        .map(|(index, task)| {
            (
                task.id.clone(),
                subtask_file_stem(&main_task.id, index.saturating_add(1)),
            )
        })
        .collect();
    main_task_manager.update_status(&main_task.id, MainTaskStatus::Planning)?;
    for task in &subtasks {
        let mut sub_task_ref = SubTaskRef::new(&task.id, &task.description);
        for dep in &task.depends_on {
            sub_task_ref = sub_task_ref.depends_on(dep.clone());
        }
        main_task_manager.add_sub_task(&main_task.id, &sub_task_ref)?;
    }
    if let Some(mut loaded_main_task) = main_task_manager.load(&main_task.id)? {
        for task in &subtasks {
            let file_stem = subtask_file_stems
                .get(&task.id)
                .cloned()
                .unwrap_or_else(|| task.id.clone());
            loaded_main_task.add_acceptance_criterion(format!(
                "content: {} contains '- [x]'",
                subtask_relative_path(&file_stem)
            ));
        }
        main_task_manager.update(&loaded_main_task)?;
    }

    emit_task_dag(
        &app_handle,
        &task_id,
        tasks_info.clone(),
        parallel_groups.clone(),
    )?;

    // Emit task started event
    emit_task_status(
        &app_handle,
        &task_id,
        "running",
        Some(format!("主 Claw 已生成 {} 个子任务，准备分发执行...", subtasks.len())),
    )?;

    // Spawn background task for execution
    let app_handle_clone = app_handle.clone();
    let task_manager = state.task_manager.clone();
    let task_id_clone = task_id.clone();
    let main_task_id = main_task.id.clone();
    let settings_clone = settings.clone();
    let input_clone = input.clone();
    let subtask_file_stems_clone = subtask_file_stems.clone();

    tokio::spawn(async move {
        let main_task_manager = match MainTaskManager::new(&project_path) {
            Ok(manager) => manager,
            Err(e) => {
                tracing::error!("Failed to create MainTaskManager: {}", e);
                return;
            }
        };

        if let Err(e) = main_task_manager.update_status(&main_task_id, MainTaskStatus::Executing) {
            tracing::error!("Failed to update main task status: {}", e);
        }

        let bridge = match ZeroclawBridge::from_project_with_settings(&project_path, &settings_clone)
        {
            Ok(bridge) => Some(Arc::new(bridge)),
            Err(e) => {
                tracing::error!("Failed to initialize ZeroClaw bridge: {}", e);
                None
            }
        };

        let experience_sink = match SkillStore::new(&project_path) {
            Ok(skill_store) => Some(ExperienceSink::new(skill_store)),
            Err(e) => {
                tracing::error!("Failed to create ExperienceSink: {}", e);
                None
            }
        };
        let task_generator = match TaskGenerator::new(&project_path) {
            Ok(generator) => Some(generator),
            Err(e) => {
                tracing::error!("Failed to create TaskGenerator: {}", e);
                None
            }
        };

        // Load DAG from storage
        let mut dag = {
            let dags = ORCHESTRATION_DAGS.read().await;
            dags.get(&task_id_clone)
                .cloned()
                .unwrap_or_else(|| TaskDag::new())
        };

        let mut total_tasks = dag.len();
        let mut completed_count = 0;
        let mut subtask_file_stems = subtask_file_stems_clone;
        let mut next_subtask_sequence = subtask_file_stems.len() + 1;

        // Collect parallel groups upfront to avoid borrow issues
        let parallel_groups: Vec<Vec<_>> = dag
            .get_parallel_groups()
            .into_iter()
            .map(|group| group.into_iter().cloned().collect())
            .collect();

        // Execute in parallel groups
        for (group_index, group_tasks) in parallel_groups.iter().enumerate() {
            if let Err(e) = emit_task_status(
                &app_handle_clone,
                &task_id_clone,
                "running",
                Some(format!(
                    "主 Claw 正在执行第 {}/{} 批子任务...",
                    group_index + 1,
                    parallel_groups.len()
                )),
            ) {
                tracing::error!("Failed to emit group progress status: {}", e);
            }

            // Emit subtask started events
            for task in group_tasks {
                if let Err(e) = emit_subtask_started(
                    &app_handle_clone,
                    &task_id_clone,
                    &task.id,
                    &task.description,
                ) {
                    tracing::error!("Failed to emit subtask started event: {}", e);
                }
            }

            let mut runnable_tasks = Vec::new();
            for task in group_tasks {
                if let Err(e) = main_task_manager.update_sub_task_status(
                    &main_task_id,
                    &task.id,
                    SubTaskStatus::Running,
                ) {
                    tracing::error!("Failed to update subtask running status: {}", e);
                }

                let task_md_path = if let Some(generator) = &task_generator {
                    let file_stem = subtask_file_stems
                        .entry(task.id.clone())
                        .or_insert_with(|| {
                            let stem = subtask_file_stem(&main_task_id, next_subtask_sequence);
                            next_subtask_sequence += 1;
                            stem
                        })
                        .clone();
                    match generator.generate_named(task, &file_stem) {
                        Ok(path) => path,
                        Err(e) => {
                            tracing::error!("Failed to generate task file for {}: {}", task.id, e);
                            if let Some(t) = dag.get_task_mut(&task.id) {
                                t.status = SubTaskStatus::Failed;
                                t.result = Some(format!("task 文件生成失败: {}", e));
                            }
                            if let Err(update_err) = main_task_manager.update_sub_task_status(
                                &main_task_id,
                                &task.id,
                                SubTaskStatus::Failed,
                            ) {
                                tracing::error!(
                                    "Failed to update subtask failed status after task file generation failure: {}",
                                    update_err
                                );
                            }
                            completed_count += 1;
                            if let Err(progress_err) = emit_orchestration_progress(
                                &app_handle_clone,
                                &task_id_clone,
                                completed_count,
                                total_tasks,
                            ) {
                                tracing::error!(
                                    "Failed to emit progress after task file generation failure: {}",
                                    progress_err
                                );
                            }
                            if let Err(emit_err) = emit_subtask_completed(
                                &app_handle_clone,
                                &task_id_clone,
                                &task.id,
                                false,
                                "",
                                Some(format!("task 文件生成失败: {}", e)),
                            ) {
                                tracing::error!(
                                    "Failed to emit subtask completion after task file generation failure: {}",
                                    emit_err
                                );
                            }
                            continue;
                        }
                    }
                } else {
                    tracing::error!("TaskGenerator unavailable, skip subtask {}", task.id);
                    if let Some(t) = dag.get_task_mut(&task.id) {
                        t.status = SubTaskStatus::Failed;
                        t.result = Some("TaskGenerator unavailable".to_string());
                    }
                    if let Err(update_err) = main_task_manager.update_sub_task_status(
                        &main_task_id,
                        &task.id,
                        SubTaskStatus::Failed,
                    ) {
                        tracing::error!(
                            "Failed to update subtask failed status after generator unavailable: {}",
                            update_err
                        );
                    }
                    completed_count += 1;
                    if let Err(progress_err) = emit_orchestration_progress(
                        &app_handle_clone,
                        &task_id_clone,
                        completed_count,
                        total_tasks,
                    ) {
                        tracing::error!(
                            "Failed to emit progress after generator unavailable: {}",
                            progress_err
                        );
                    }
                    if let Err(emit_err) = emit_subtask_completed(
                        &app_handle_clone,
                        &task_id_clone,
                        &task.id,
                        false,
                        "",
                        Some("TaskGenerator unavailable".to_string()),
                    ) {
                        tracing::error!(
                            "Failed to emit subtask completion after generator unavailable: {}",
                            emit_err
                        );
                    }
                    continue;
                };
                runnable_tasks.push((task.clone(), task_md_path));
            }

            let mut join_set = tokio::task::JoinSet::new();
            for (task, task_md_path) in runnable_tasks {
                let bridge_for_task = bridge.clone();
                let project_path_for_task = project_path.clone();
                join_set.spawn(async move {
                    let result = match SkillStore::new(&project_path_for_task) {
                        Ok(skill_store) => {
                            let mut executor = SubClawExecutor::new(&task_md_path, skill_store);
                            if let Some(bridge) = bridge_for_task {
                                executor.set_step_executor(Arc::new(
                                    ZeroclawStepExecutor::from_bridge(bridge),
                                ));
                            }
                            executor.run().await.map_err(|e| e.to_string())
                        }
                        Err(e) => Err(e.to_string()),
                    };
                    (task, task_md_path, result)
                });
            }

            while let Some(join_result) = join_set.join_next().await {
                let (task, task_md_path, result) = match join_result {
                    Ok(value) => value,
                    Err(e) => {
                        tracing::error!("Subtask execution join error: {}", e);
                        continue;
                    }
                };

                let result = match result {
                    Ok(exec_result) => exec_result,
                    Err(e) => {
                        if let Some(t) = dag.get_task_mut(&task.id) {
                            t.status = SubTaskStatus::Failed;
                            t.result = Some(e.clone());
                        }
                        if let Err(update_err) = main_task_manager.update_sub_task_status(
                            &main_task_id,
                            &task.id,
                            SubTaskStatus::Failed,
                        ) {
                            tracing::error!(
                                "Failed to update subtask failed status after execution failure: {}",
                                update_err
                            );
                        }
                        completed_count += 1;
                        if let Err(progress_err) = emit_orchestration_progress(
                            &app_handle_clone,
                            &task_id_clone,
                            completed_count,
                            total_tasks,
                        ) {
                            tracing::error!(
                                "Failed to emit progress after execution failure: {}",
                                progress_err
                            );
                        }
                        if let Err(emit_err) = emit_subtask_completed(
                            &app_handle_clone,
                            &task_id_clone,
                            &task.id,
                            false,
                            "",
                            Some(e),
                        ) {
                            tracing::error!(
                                "Failed to emit subtask completion after execution failure: {}",
                                emit_err
                            );
                        }
                        continue;
                    }
                };

                if let Some(t) = dag.get_task_mut(&task.id) {
                    t.status = if result.success {
                        SubTaskStatus::Completed
                    } else {
                        SubTaskStatus::Failed
                    };
                    t.result = result.error.clone().or(Some(format!(
                        "Completed {}/{} steps",
                        result.steps_completed, result.steps_total
                    )));
                }

                let subtask_status = if result.success {
                    SubTaskStatus::Completed
                } else {
                    SubTaskStatus::Failed
                };
                if let Err(e) =
                    main_task_manager.update_sub_task_status(&main_task_id, &task.id, subtask_status)
                {
                    tracing::error!("Failed to update subtask final status: {}", e);
                }

                if let Some(sink) = experience_sink.as_ref() {
                    let task_md_content = std::fs::read_to_string(&task_md_path).ok();
                    let output_text = result
                        .error
                        .clone()
                        .unwrap_or_else(|| {
                            format!(
                                "Completed {}/{} steps",
                                result.steps_completed, result.steps_total
                            )
                        });
                    let mut task_result = TaskResultForExperience::new(
                        task.id.clone(),
                        task.description.clone(),
                        output_text,
                        result.success,
                    )
                    .with_files(vec![task_md_path.display().to_string()]);
                    if let Some(content) = task_md_content {
                        task_result = task_result.with_task_md(content);
                    }
                    if let Ok(experience) = sink.extract_experience(&task_result) {
                        persist_task_experience_artifacts(&project_path, sink, &experience);
                    }
                }

                completed_count += 1;

                if let Err(e) = emit_task_status(
                    &app_handle_clone,
                    &task_id_clone,
                    "running",
                    Some(format!(
                        "主 Claw 验证中：已完成 {}/{} 个子任务",
                        completed_count, total_tasks
                    )),
                ) {
                    tracing::error!("Failed to emit running status event: {}", e);
                }

                // Emit progress
                if let Err(e) = emit_orchestration_progress(
                    &app_handle_clone,
                    &task_id_clone,
                    completed_count,
                    total_tasks,
                ) {
                    tracing::error!("Failed to emit progress event: {}", e);
                }

                // Emit subtask completed event
                let success = result.success;
                let output =
                    format!("Completed {}/{} steps", result.steps_completed, result.steps_total);
                let error = result.error.clone();

                if let Err(emit_err) = emit_subtask_completed(
                    &app_handle_clone,
                    &task_id_clone,
                    &task.id,
                    success,
                    &output,
                    error,
                ) {
                    tracing::error!("Failed to emit subtask completed event: {}", emit_err);
                }
            }

            // Update stored DAG
            {
                let mut dags = ORCHESTRATION_DAGS.write().await;
                dags.insert(task_id_clone.clone(), dag.clone());
            }
        }

        // Check overall success
        let all_completed = dag
            .tasks_in_order()
            .iter()
            .all(|t| t.status == SubTaskStatus::Completed);

        if let Err(e) = main_task_manager.update_status(&main_task_id, MainTaskStatus::Verifying) {
            tracing::error!("Failed to update main task verifying status: {}", e);
        }

        let evaluator = AcceptanceEvaluator::new(&project_path);
        let acceptance_criteria = dag
            .tasks_in_order()
            .iter()
            .map(|task| smanclaw_core::AcceptanceCriteria::Functional {
                id: format!("subtask-{}", task.id),
                description: format!(
                    "content: {} contains '- [x]'",
                    subtask_relative_path(
                        subtask_file_stems
                            .get(&task.id)
                            .map(std::string::String::as_str)
                            .unwrap_or(task.id.as_str())
                    )
                ),
                verification_method: VerificationMethod::ContentMatch,
            })
            .collect::<Vec<_>>();
        let mut evaluation = evaluator.evaluate(&acceptance_criteria);
        let mut evaluation_passed = evaluation
            .as_ref()
            .map(|result| result.overall_passed)
            .unwrap_or(false);
        let mut final_passed = all_completed && evaluation_passed;
        let mut remediation_round = 0usize;
        const MAX_REMEDIATION_ROUNDS: usize = 2;

        while !final_passed && remediation_round < MAX_REMEDIATION_ROUNDS {
            remediation_round += 1;
            let remediation_tasks =
                build_remediation_subtasks(&dag, evaluation.as_ref().ok(), remediation_round);
            if let Err(e) = emit_task_status(
                &app_handle_clone,
                &task_id_clone,
                "running",
                Some(format!(
                    "主 Claw 验收未通过，开始第 {}/{} 轮自动补救执行（{} 个补救任务）...",
                    remediation_round,
                    MAX_REMEDIATION_ROUNDS,
                    remediation_tasks.len(),
                )),
            ) {
                tracing::error!("Failed to emit remediation status: {}", e);
            }

            for remediation_task in remediation_tasks {
                if let Err(e) = dag.add_task(remediation_task.clone()) {
                    tracing::error!("Failed to add remediation task {}: {}", remediation_task.id, e);
                    continue;
                }
                total_tasks = dag.len();
                let sub_task_ref = SubTaskRef::new(&remediation_task.id, &remediation_task.description);
                if let Err(e) = main_task_manager.add_sub_task(&main_task_id, &sub_task_ref) {
                    tracing::error!("Failed to append remediation subtask: {}", e);
                }

                if let Err(e) = emit_subtask_started(
                    &app_handle_clone,
                    &task_id_clone,
                    &remediation_task.id,
                    &remediation_task.description,
                ) {
                    tracing::error!("Failed to emit remediation subtask started event: {}", e);
                }

                if let Err(e) = main_task_manager.update_sub_task_status(
                    &main_task_id,
                    &remediation_task.id,
                    SubTaskStatus::Running,
                ) {
                    tracing::error!("Failed to update remediation subtask running status: {}", e);
                }

                let task_md_path = if let Some(generator) = &task_generator {
                    let file_stem = subtask_file_stem(&main_task_id, next_subtask_sequence);
                    next_subtask_sequence += 1;
                    subtask_file_stems.insert(remediation_task.id.clone(), file_stem.clone());
                    match generator.generate_named(&remediation_task, &file_stem) {
                        Ok(path) => path,
                        Err(e) => {
                            tracing::error!(
                                "Failed to generate remediation task file for {}: {}",
                                remediation_task.id,
                                e
                            );
                            continue;
                        }
                    }
                } else {
                    tracing::error!(
                        "TaskGenerator unavailable, skip remediation subtask {}",
                        remediation_task.id
                    );
                    continue;
                };

                let skill_store = match SkillStore::new(&project_path) {
                    Ok(s) => s,
                    Err(e) => {
                        tracing::error!("Failed to create SkillStore for remediation: {}", e);
                        continue;
                    }
                };
                let mut executor = SubClawExecutor::new(&task_md_path, skill_store);
                if let Some(ref bridge) = bridge {
                    executor.set_step_executor(Arc::new(ZeroclawStepExecutor::from_bridge(
                        bridge.clone(),
                    )));
                }
                let result = executor.run().await;

                if let Some(t) = dag.get_task_mut(&remediation_task.id) {
                    match &result {
                        Ok(exec_result) => {
                            t.status = if exec_result.success {
                                SubTaskStatus::Completed
                            } else {
                                SubTaskStatus::Failed
                            };
                            t.result = exec_result.error.clone().or(Some(format!(
                                "Completed {}/{} steps",
                                exec_result.steps_completed, exec_result.steps_total
                            )));
                        }
                        Err(e) => {
                            t.status = SubTaskStatus::Failed;
                            t.result = Some(e.to_string());
                        }
                    }
                }

                let subtask_status = match &result {
                    Ok(exec_result) if exec_result.success => SubTaskStatus::Completed,
                    _ => SubTaskStatus::Failed,
                };
                if let Err(e) = main_task_manager.update_sub_task_status(
                    &main_task_id,
                    &remediation_task.id,
                    subtask_status,
                ) {
                    tracing::error!(
                        "Failed to update remediation subtask final status: {}",
                        e
                    );
                }

                if let (Some(sink), Ok(exec_result)) = (experience_sink.as_ref(), &result) {
                    let task_md_content = std::fs::read_to_string(&task_md_path).ok();
                    let output_text = exec_result.error.clone().unwrap_or_else(|| {
                        format!(
                            "Completed {}/{} steps",
                            exec_result.steps_completed, exec_result.steps_total
                        )
                    });
                    let mut task_result = TaskResultForExperience::new(
                        remediation_task.id.clone(),
                        remediation_task.description.clone(),
                        output_text,
                        exec_result.success,
                    )
                    .with_files(vec![task_md_path.display().to_string()]);
                    if let Some(content) = task_md_content {
                        task_result = task_result.with_task_md(content);
                    }
                    if let Ok(experience) = sink.extract_experience(&task_result) {
                        persist_task_experience_artifacts(&project_path, sink, &experience);
                    }
                }

                completed_count += 1;
                if let Err(e) = emit_orchestration_progress(
                    &app_handle_clone,
                    &task_id_clone,
                    completed_count,
                    total_tasks,
                ) {
                    tracing::error!("Failed to emit remediation progress event: {}", e);
                }
                let (success, output, error) = match &result {
                    Ok(r) => (
                        r.success,
                        format!("Completed {}/{} steps", r.steps_completed, r.steps_total),
                        r.error.clone(),
                    ),
                    Err(e) => (false, String::new(), Some(e.to_string())),
                };
                if let Err(emit_err) = emit_subtask_completed(
                    &app_handle_clone,
                    &task_id_clone,
                    &remediation_task.id,
                    success,
                    &output,
                    error,
                ) {
                    tracing::error!(
                        "Failed to emit remediation subtask completed event: {}",
                        emit_err
                    );
                }
            }

            let acceptance_criteria = dag
                .tasks_in_order()
                .iter()
                .map(|task| smanclaw_core::AcceptanceCriteria::Functional {
                    id: format!("subtask-{}", task.id),
                    description: format!(
                        "content: {} contains '- [x]'",
                        subtask_relative_path(
                            subtask_file_stems
                                .get(&task.id)
                                .map(std::string::String::as_str)
                                .unwrap_or(task.id.as_str())
                        )
                    ),
                    verification_method: VerificationMethod::ContentMatch,
                })
                .collect::<Vec<_>>();
            evaluation = evaluator.evaluate(&acceptance_criteria);
            evaluation_passed = evaluation
                .as_ref()
                .map(|result| result.overall_passed)
                .unwrap_or(false);
            let all_completed_after_remediation = dag
                .tasks_in_order()
                .iter()
                .all(|t| t.status == SubTaskStatus::Completed);
            final_passed = all_completed_after_remediation && evaluation_passed;
        }

        let tests_run = evaluation
            .as_ref()
            .ok()
            .map(|result| result.criteria_results.len());
        let tests_passed = evaluation.as_ref().ok().map(|result| {
            result
                .criteria_results
                .iter()
                .filter(|criteria| criteria.status == smanclaw_core::CriteriaStatus::Passed)
                .count()
        });
        if let Err(e) = emit_test_result(
            &app_handle_clone,
            &task_id_clone,
            "acceptance",
            final_passed,
            &format!("Acceptance for request: {}", input_clone),
            tests_run,
            tests_passed,
        ) {
            tracing::error!("Failed to emit acceptance test result: {}", e);
        }

        let final_status = if final_passed {
            TaskStatus::Completed
        } else {
            TaskStatus::Failed
        };
        let final_output = if final_passed {
            Some(format!("All {} subtasks completed and accepted", total_tasks))
        } else {
            None
        };
        let final_error = if final_passed {
            None
        } else {
            let mut errors = Vec::new();
            let current_all_completed = dag
                .tasks_in_order()
                .iter()
                .all(|t| t.status == SubTaskStatus::Completed);
            if !current_all_completed {
                errors.push("Some subtasks failed".to_string());
            }
            if !evaluation_passed {
                errors.push("Acceptance criteria not fully passed".to_string());
            }
            if let Err(e) = &evaluation {
                errors.push(format!("Acceptance evaluator error: {}", e));
            }
            Some(errors.join("; "))
        };
        if let Err(e) = task_manager.lock().await.update_task_result(
            &task_id_clone,
            final_status,
            final_output.clone(),
            final_error.clone(),
        ) {
            tracing::error!("Failed to update task result: {}", e);
        }

        let main_task_result = if final_passed {
            MainTaskResult::success(
                format!("Completed {} subtasks and passed acceptance", total_tasks),
                dag.tasks_in_order()
                    .iter()
                    .map(|task| {
                        subtask_relative_path(
                            subtask_file_stems
                                .get(&task.id)
                                .map(std::string::String::as_str)
                                .unwrap_or(task.id.as_str()),
                        )
                    })
                    .collect(),
            )
        } else {
            MainTaskResult::failure(
                final_error
                    .clone()
                    .unwrap_or_else(|| "Orchestration failed".to_string()),
            )
        };
        if let Err(e) = main_task_manager.complete(&main_task_id, &main_task_result) {
            tracing::error!("Failed to complete main task: {}", e);
        }

        // Emit final status
        let (status_str, message) = match final_passed {
            true => (
                "completed",
                Some(format!(
                    "All {} subtasks completed and accepted",
                    total_tasks
                )),
            ),
            false => ("failed", final_error),
        };
        if let Err(e) = emit_task_status(&app_handle_clone, &task_id_clone, status_str, message) {
            tracing::error!("Failed to emit final task status event: {}", e);
        }

        // Clean up DAG storage (optional, keep for history if needed)
        // {
        //     let mut dags = ORCHESTRATION_DAGS.write().await;
        //     dags.remove(&task_id_clone);
        // }
    });

    Ok(OrchestratedTaskResult {
        task_id,
        subtask_count: subtasks.len(),
        parallel_groups,
    })
}

/// Get the DAG structure for an orchestrated task
#[tauri::command(rename_all = "snake_case")]
pub async fn get_task_dag(task_id: String) -> TauriResult<Option<TaskDagResponse>> {
    if task_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Task ID cannot be empty".to_string(),
        ));
    }

    let dags = ORCHESTRATION_DAGS.read().await;
    let dag = match dags.get(&task_id) {
        Some(d) => d,
        None => return Ok(None),
    };

    let tasks: Vec<SubTaskInfo> = dag
        .tasks_in_order()
        .iter()
        .map(|t| SubTaskInfo {
            id: t.id.clone(),
            description: t.description.clone(),
            status: match t.status {
                SubTaskStatus::Pending => "pending",
                SubTaskStatus::Running => "running",
                SubTaskStatus::Completed => "completed",
                SubTaskStatus::Failed => "failed",
            }
            .to_string(),
            depends_on: t.depends_on.clone(),
        })
        .collect();

    let parallel_groups: Vec<Vec<String>> = dag
        .get_parallel_groups()
        .iter()
        .map(|group| group.iter().map(|t| t.id.clone()).collect())
        .collect();

    let completed = tasks.iter().filter(|t| t.status == "completed").count();
    let total = tasks.len();
    let percent = if total > 0 {
        completed as f32 / total as f32
    } else {
        0.0
    };

    Ok(Some(TaskDagResponse {
        task_id,
        tasks,
        parallel_groups,
        progress: OrchestrationProgress {
            completed,
            total,
            percent,
        },
    }))
}

/// Get orchestration status for a task
#[tauri::command(rename_all = "snake_case")]
pub async fn get_orchestration_status(
    task_id: String,
) -> TauriResult<Option<OrchestrationProgress>> {
    if task_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Task ID cannot be empty".to_string(),
        ));
    }

    let dags = ORCHESTRATION_DAGS.read().await;
    let dag = match dags.get(&task_id) {
        Some(d) => d,
        None => return Ok(None),
    };

    let total = dag.len();
    let completed = dag
        .tasks_in_order()
        .iter()
        .filter(|t| t.status == SubTaskStatus::Completed)
        .count();
    let percent = if total > 0 {
        completed as f32 / total as f32
    } else {
        0.0
    };

    Ok(Some(OrchestrationProgress {
        completed,
        total,
        percent,
    }))
}

#[cfg(test)]
mod tests {
    use super::{build_remediation_subtasks, persist_path_from_task_experience, persist_user_input_experience, persist_user_memory, sanitize_path_fragment, should_generate_path_from_task_experience, subtask_file_stem, subtask_relative_path};
    use crate::commands::orchestration_decompose::{
        enforce_subtask_context_independence, extract_json_payload, fallback_decompose_subtasks,
        is_simple_requirement, normalize_requirement_summary, normalize_semantic_subtasks,
        parse_semantic_subtasks, sanitize_task_id, SemanticDecomposeResponse, SemanticSubTask,
    };
    use smanclaw_core::{EvaluationResult, Experience, LearnedItem, SkillStore, SubTask, SubTaskStatus, TaskDag};
    use std::fs;

    #[test]
    fn extracts_json_from_markdown_block() {
        let output = "text\n```json\n{\"subtasks\":[{\"id\":\"task-1\",\"description\":\"a\"}]}\n```\nend";
        let payload = extract_json_payload(output).expect("json payload");
        assert!(payload.contains("\"subtasks\""));
    }

    #[test]
    fn sanitizes_and_deduplicates_task_ids() {
        let response = SemanticDecomposeResponse {
            subtasks: vec![
                SemanticSubTask {
                    id: Some("Task_1".to_string()),
                    description: "first".to_string(),
                    depends_on: vec![],
                    test_command: None,
                },
                SemanticSubTask {
                    id: Some("task-1".to_string()),
                    description: "second".to_string(),
                    depends_on: vec!["Task_1".to_string()],
                    test_command: None,
                },
            ],
        };

        let tasks = normalize_semantic_subtasks(response).expect("normalized tasks");
        assert_eq!(tasks[0].id, "task-1");
        assert_eq!(tasks[1].id, "task-1-2");
        assert_eq!(tasks[1].depends_on, vec!["task-1"]);
    }

    #[test]
    fn parses_semantic_subtasks_with_dependencies() {
        let output = r#"{"subtasks":[{"id":"plan","description":"plan work","depends_on":[]},{"id":"impl","description":"implement","depends_on":["plan"],"test_command":"cargo test"}]}"#;
        let tasks = parse_semantic_subtasks(output).expect("parsed subtasks");
        assert_eq!(tasks.len(), 2);
        assert_eq!(tasks[1].depends_on, vec!["plan"]);
        assert_eq!(tasks[1].test_command.as_deref(), Some("cargo test"));
    }

    #[test]
    fn sanitize_id_falls_back_for_numeric_prefix() {
        let id = sanitize_task_id("123abc", 0);
        assert_eq!(id, "task-1");
    }

    #[test]
    fn remediation_subtasks_use_round_specific_ids() {
        let mut failed = SubTask::new("task-1", "failing task");
        failed.status = SubTaskStatus::Failed;
        let dag = TaskDag::from_tasks(vec![failed]).expect("dag");

        let round1 = build_remediation_subtasks(&dag, None, 1);
        let round2 = build_remediation_subtasks(&dag, None, 2);

        assert_eq!(round1.len(), 1);
        assert_eq!(round2.len(), 1);
        assert_eq!(round1[0].id, "remediate-r1-task-1");
        assert_eq!(round2[0].id, "remediate-r2-task-1");
        assert_ne!(round1[0].id, round2[0].id);
    }

    #[test]
    fn remediation_subtasks_fallback_to_general_task() {
        let dag = TaskDag::from_tasks(vec![SubTask::new("task-1", "done task")]).expect("dag");
        let mut evaluation = EvaluationResult::new("main-task".to_string());
        evaluation.recommendations.clear();

        let remediation = build_remediation_subtasks(&dag, Some(&evaluation), 1);

        assert_eq!(remediation.len(), 1);
        assert_eq!(remediation[0].id, "remediate-r1-general");
    }

    #[test]
    fn fallback_decompose_generates_structured_chain_for_generic_requirement() {
        let tasks = fallback_decompose_subtasks(
            "请重构任务编排并增强验收评估稳定性，覆盖失败重试并补齐回归验证",
        );

        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].id, "task-context-serial");
        assert!(tasks[0].description.contains("单一子Claw上下文"));
        assert_eq!(tasks[0].test_command.as_deref(), Some("cargo test"));
    }

    #[test]
    fn fallback_decompose_prefers_existing_rule_based_result() {
        let tasks = fallback_decompose_subtasks("Implement user login feature");
        assert!(!tasks.is_empty());
        assert!(tasks
            .iter()
            .any(|t| t.id.contains("login") || t.id == "task-context-serial"));
    }

    #[test]
    fn fallback_decompose_keeps_single_task_for_simple_requirement() {
        let tasks = fallback_decompose_subtasks("修复按钮颜色");
        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].id, "task-0");
    }

    #[test]
    fn context_independence_merges_cross_context_subtasks() {
        let subtasks = vec![
            SubTask::new("task-1", "完成数据模型设计"),
            SubTask::new("task-2", "基于 task-1 的上下文完成接口实现").depends_on("task-1"),
        ];
        let merged = enforce_subtask_context_independence(subtasks, "实现支付流程");
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].id, "task-context-serial");
    }

    #[test]
    fn context_independence_keeps_parallelizable_subtasks() {
        let subtasks = vec![
            SubTask::new("task-ui", "完成前端页面改造"),
            SubTask::new("task-api", "完成后端接口改造"),
        ];
        let kept = enforce_subtask_context_independence(subtasks, "同步改造前后端");
        assert_eq!(kept.len(), 2);
        assert_eq!(kept[0].id, "task-ui");
        assert_eq!(kept[1].id, "task-api");
    }

    #[test]
    fn simple_requirement_heuristic_distinguishes_complex_input() {
        assert!(is_simple_requirement("修复按钮颜色"));
        assert!(!is_simple_requirement("重构任务编排，并补齐回归验证"));
    }

    #[test]
    fn path_generation_heuristic_respects_reusable_patterns() {
        let mut experience = Experience::new("task-1");
        assert!(!should_generate_path_from_task_experience(&experience));
        experience.add_pattern("固定执行顺序：先校验再发布");
        assert!(should_generate_path_from_task_experience(&experience));
    }

    #[test]
    fn sanitize_path_fragment_outputs_stable_token() {
        assert_eq!(sanitize_path_fragment("Task A_B"), "task-a-b");
        assert_eq!(sanitize_path_fragment("###"), "task");
    }

    #[test]
    fn persist_user_memory_creates_memory_file() {
        let root = std::env::temp_dir().join(format!(
            "smanclaw-memory-test-{}",
            chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()
        ));
        fs::create_dir_all(&root).expect("create temp root");

        persist_user_memory(
            &root,
            "接口返回必须包含trace_id",
            "接口必须返回trace_id",
            &["api".to_string(), "constraint".to_string()],
        );

        let memory_path = root.join(".smanclaw").join("MEMORY.md");
        let content = fs::read_to_string(memory_path).expect("read memory");
        assert!(content.contains("user-input"));
        assert!(content.contains("trace_id"));

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn persist_user_input_experience_creates_skill_memory_and_path() {
        let root = std::env::temp_dir().join(format!(
            "smanclaw-user-exp-test-{}",
            chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()
        ));
        fs::create_dir_all(&root).expect("create temp root");

        persist_user_input_experience(&root, "接口返回必须包含trace_id");

        let memory_path = root.join(".smanclaw").join("MEMORY.md");
        let memory = fs::read_to_string(memory_path).expect("read memory");
        assert!(memory.contains("trace_id"));

        let skill_store = SkillStore::new(&root).expect("skill store");
        let skill_metas = skill_store.list().expect("list skills");
        assert!(!skill_metas.is_empty());

        let path_store = SkillStore::for_paths(&root).expect("path store");
        let path_metas = path_store.list().expect("list paths");
        assert!(!path_metas.is_empty());
        assert!(path_metas
            .iter()
            .any(|meta| meta.tags.contains(&"path".to_string())));

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn persist_task_experience_writes_path_skill() {
        let root = std::env::temp_dir().join(format!(
            "smanclaw-path-test-{}",
            chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()
        ));
        fs::create_dir_all(&root).expect("create temp root");

        let mut experience = Experience::new("task-api-hardening");
        experience.add_learned(LearnedItem::new("workflow", "发布前必须执行回归"));
        experience.add_pattern("先 lint，再 test，最后部署");
        persist_path_from_task_experience(&root, &experience);

        let store = SkillStore::for_paths(&root).expect("path store");
        let metas = store.list().expect("list path skills");
        assert!(!metas.is_empty());
        assert!(metas.iter().any(|meta| meta.tags.contains(&"path".to_string())));

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn requirement_summary_is_trimmed_to_stable_length() {
        let long_input = "这是一个非常长的需求描述，需要在多模块中实现并保持可回滚、可观测、可测试，同时要求失败自动补救、再次验收和经验沉淀流程完整闭环".repeat(2);
        let summary = normalize_requirement_summary(&long_input);
        assert!(summary.chars().count() <= 75);
        assert!(summary.ends_with("..."));
    }

    #[test]
    fn subtask_file_stem_preserves_main_task_relation() {
        let stem = subtask_file_stem("main-2603071250-A1B2", 1);
        assert_eq!(stem, "task-main-2603071250-A1B2-001");

        let second = subtask_file_stem("main-2603071250-A1B2", 12);
        assert_eq!(second, "task-main-2603071250-A1B2-012");
    }

    #[test]
    fn subtask_relative_path_uses_named_markdown() {
        let path = subtask_relative_path("task-main-2603071250-A1B2-001");
        assert_eq!(path, ".smanclaw/tasks/task-main-2603071250-A1B2-001.md");
    }
}
