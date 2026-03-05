//! Tauri commands for frontend communication

use chrono::Utc;
use smanclaw_core::{MockStepExecutor, Orchestrator, SkillStore, SubClawExecutor, SubTaskStatus, TaskDag};
use smanclaw_ffi::ZeroclawBridge;
use smanclaw_types::{
    AppSettings, ConnectionTestResult, Conversation, EmbeddingSettings, FileAction, HistoryEntry,
    LlmSettings, Project, ProjectConfig, QdrantSettings, Role, Task, TaskStatus,
};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tauri::{AppHandle, State};
use tokio::sync::RwLock;

use crate::error::{TauriError, TauriResult};
use crate::events::{
    emit_file_change, emit_orchestration_progress, emit_progress_event, emit_subtask_completed,
    emit_subtask_started, emit_task_dag, emit_task_status, emit_test_result, SubTaskInfo,
};
use crate::state::AppState;
use smanclaw_ffi::test_llm_direct;

// ============================================================================
// Project Commands
// ============================================================================

/// Get all projects
#[tauri::command]
pub async fn get_projects(state: State<'_, AppState>) -> TauriResult<Vec<Project>> {
    let pm = state.project_manager.lock().await;
    let projects = pm.list_projects()?;
    Ok(projects)
}

/// Add a new project
#[tauri::command]
pub async fn add_project(state: State<'_, AppState>, path: String) -> TauriResult<Project> {
    if path.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project path cannot be empty".to_string(),
        ));
    }

    let project_path = PathBuf::from(&path);
    if !project_path.exists() {
        return Err(TauriError::InvalidInput(format!(
            "Project path does not exist: {}",
            path
        )));
    }

    let mut pm = state.project_manager.lock().await;
    let project = pm.add_project(&project_path)?;
    drop(pm);

    Ok(project)
}

/// Remove a project
#[tauri::command(rename_all = "snake_case")]
pub async fn remove_project(state: State<'_, AppState>, project_id: String) -> TauriResult<()> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let mut pm = state.project_manager.lock().await;
    pm.remove_project(&project_id)?;
    Ok(())
}

/// Get project configuration
#[tauri::command(rename_all = "snake_case")]
pub async fn get_project_config(
    state: State<'_, AppState>,
    project_id: String,
) -> TauriResult<ProjectConfig> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let pm = state.project_manager.lock().await;
    let config = pm.get_project_config(&project_id)?;
    Ok(config)
}

/// Update project configuration
#[tauri::command]
pub async fn update_project_config(
    state: State<'_, AppState>,
    config: ProjectConfig,
) -> TauriResult<()> {
    if config.project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let mut pm = state.project_manager.lock().await;
    pm.update_project_config(&config)?;
    Ok(())
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
// Conversation Commands
// ============================================================================

/// Get conversation history
#[tauri::command(rename_all = "snake_case")]
pub async fn get_conversation(
    state: State<'_, AppState>,
    conversation_id: String,
) -> TauriResult<Option<Conversation>> {
    if conversation_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Conversation ID cannot be empty".to_string(),
        ));
    }

    let hs = state.history_store.lock().await;
    let conversation = hs.get_conversation(&conversation_id)?;
    Ok(conversation)
}

/// Get conversation messages
#[tauri::command(rename_all = "snake_case")]
pub async fn get_conversation_messages(
    state: State<'_, AppState>,
    conversation_id: String,
) -> TauriResult<Vec<HistoryEntry>> {
    if conversation_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Conversation ID cannot be empty".to_string(),
        ));
    }

    let hs = state.history_store.lock().await;
    let entries = hs.load_conversation(&conversation_id)?;
    Ok(entries)
}

/// List conversations for a project
#[tauri::command(rename_all = "snake_case")]
pub async fn list_conversations(
    state: State<'_, AppState>,
    project_id: String,
) -> TauriResult<Vec<Conversation>> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let hs = state.history_store.lock().await;
    let conversations = hs.list_conversations(&project_id)?;
    Ok(conversations)
}

/// Create a new conversation
#[tauri::command(rename_all = "snake_case")]
pub async fn create_conversation(
    state: State<'_, AppState>,
    project_id: String,
    title: String,
) -> TauriResult<Conversation> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }
    if title.is_empty() {
        return Err(TauriError::InvalidInput(
            "Conversation title cannot be empty".to_string(),
        ));
    }

    let mut hs = state.history_store.lock().await;
    let conversation = hs.create_conversation(&project_id, &title)?;
    Ok(conversation)
}

/// Send a message in a conversation
#[tauri::command(rename_all = "snake_case")]
pub async fn send_message(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    conversation_id: String,
    content: String,
) -> TauriResult<HistoryEntry> {
    if conversation_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Conversation ID cannot be empty".to_string(),
        ));
    }
    if content.is_empty() {
        return Err(TauriError::InvalidInput(
            "Message content cannot be empty".to_string(),
        ));
    }

    // Get conversation and project path
    let (conversation, project_path) = {
        let hs = state.history_store.lock().await;
        let conversation = hs
            .get_conversation(&conversation_id)?
            .ok_or_else(|| TauriError::ConversationNotFound(conversation_id.clone()))?;
        drop(hs);

        let pm = state.project_manager.lock().await;
        let project = pm
            .get_project(&conversation.project_id)?
            .ok_or_else(|| TauriError::ProjectNotFound(conversation.project_id.clone()))?;
        (conversation, PathBuf::from(&project.path))
    };

    // Create user message
    let user_entry = HistoryEntry {
        id: uuid::Uuid::new_v4().to_string(),
        conversation_id: conversation_id.clone(),
        role: Role::User,
        content: content.clone(),
        timestamp: Utc::now(),
    };

    state.history_store.lock().await.save_entry(&user_entry)?;

    // Get settings for LLM configuration
    let settings = state.settings_store.lock().await.load()?;

    // Execute task and get response with settings
    let bridge = Arc::new(ZeroclawBridge::from_project_with_settings(&project_path, &settings)?);
    let (tx, mut rx) = tokio::sync::mpsc::channel(64);

    let app_handle_clone = app_handle.clone();
    let history_store = state.history_store.clone();
    let conv_id = conversation_id.clone();
    let content_clone = content.clone();

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
        match bridge
            .execute_task_stream(&conv_id, &content_clone, tx)
            .await
        {
            Ok(result) => {
                // Create assistant message
                let assistant_entry = HistoryEntry {
                    id: uuid::Uuid::new_v4().to_string(),
                    conversation_id: conv_id.clone(),
                    role: Role::Assistant,
                    content: result.output.clone(),
                    timestamp: Utc::now(),
                };

                if let Err(e) = history_store.lock().await.save_entry(&assistant_entry) {
                    tracing::error!("Failed to save assistant message: {}", e);
                }
            }
            Err(e) => {
                tracing::error!("Task execution failed: {}", e);
            }
        }

        forward_handle.await.ok();
    });

    Ok(user_entry)
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
// Settings Commands
// ============================================================================

/// Get application settings
#[tauri::command]
pub async fn get_app_settings(state: State<'_, AppState>) -> TauriResult<AppSettings> {
    let store = state.settings_store.lock().await;
    let settings = store.load()?;
    Ok(settings)
}

/// Update application settings
#[tauri::command(rename_all = "snake_case")]
pub async fn update_app_settings(
    state: State<'_, AppState>,
    settings: AppSettings,
) -> TauriResult<AppSettings> {
    let store = state.settings_store.lock().await;
    store.save(&settings)?;
    // Reload to get the actual stored values (with API keys from secure storage)
    let loaded = store.load()?;
    Ok(loaded)
}

/// Test LLM API connection
#[tauri::command(rename_all = "snake_case")]
pub async fn test_llm_connection(settings: LlmSettings) -> TauriResult<ConnectionTestResult> {
    use std::time::Instant;

    let start = Instant::now();

    // Build a simple test request
    let client = reqwest::Client::new();
    let url = format!(
        "{}/chat/completions",
        settings.api_url.trim_end_matches('/')
    );

    let body = serde_json::json!({
        "model": settings.default_model,
        "messages": [{"role": "user", "content": "Hello"}],
        "max_tokens": 1
    });

    let result = client
        .post(&url)
        .header("Authorization", format!("Bearer {}", settings.api_key))
        .header("Content-Type", "application/json")
        .json(&body)
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await;

    match result {
        Ok(response) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            if response.status().is_success() {
                Ok(ConnectionTestResult {
                    success: true,
                    error: None,
                    latency_ms: Some(latency_ms),
                })
            } else {
                let status = response.status();
                let error_text = response.text().await.unwrap_or_default();
                Ok(ConnectionTestResult {
                    success: false,
                    error: Some(format!("HTTP {}: {}", status, error_text)),
                    latency_ms: Some(latency_ms),
                })
            }
        }
        Err(e) => Ok(ConnectionTestResult {
            success: false,
            error: Some(e.to_string()),
            latency_ms: None,
        }),
    }
}

/// Test LLM connection using ZeroClaw's provider system (direct chat test)
#[tauri::command(rename_all = "snake_case")]
pub async fn test_llm_direct_chat(state: State<'_, AppState>) -> TauriResult<ConnectionTestResult> {
    use std::time::Instant;

    let start = Instant::now();

    // Load settings
    let settings = {
        let store = state.settings_store.lock().await;
        store.load()?
    };

    if !settings.llm.is_configured() {
        return Ok(ConnectionTestResult {
            success: false,
            error: Some("LLM not configured (API key missing)".to_string()),
            latency_ms: None,
        });
    }

    // Test using ZeroClaw's provider system
    match test_llm_direct(&settings).await {
        Ok(response) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            eprintln!("LLM direct test succeeded: {}", response);
            Ok(ConnectionTestResult {
                success: true,
                error: None,
                latency_ms: Some(latency_ms),
            })
        }
        Err(e) => {
            eprintln!("LLM direct test failed: {}", e);
            Ok(ConnectionTestResult {
                success: false,
                error: Some(e.to_string()),
                latency_ms: None,
            })
        }
    }
}

/// Test Embedding API connection
#[tauri::command(rename_all = "snake_case")]
pub async fn test_embedding_connection(
    settings: EmbeddingSettings,
) -> TauriResult<ConnectionTestResult> {
    use std::time::Instant;

    let start = Instant::now();

    let client = reqwest::Client::new();
    let url = format!("{}/embeddings", settings.api_url.trim_end_matches('/'));

    let body = serde_json::json!({
        "model": settings.model,
        "input": "test"
    });

    let result = client
        .post(&url)
        .header("Authorization", format!("Bearer {}", settings.api_key))
        .header("Content-Type", "application/json")
        .json(&body)
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await;

    match result {
        Ok(response) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            if response.status().is_success() {
                Ok(ConnectionTestResult {
                    success: true,
                    error: None,
                    latency_ms: Some(latency_ms),
                })
            } else {
                let status = response.status();
                let error_text = response.text().await.unwrap_or_default();
                Ok(ConnectionTestResult {
                    success: false,
                    error: Some(format!("HTTP {}: {}", status, error_text)),
                    latency_ms: Some(latency_ms),
                })
            }
        }
        Err(e) => Ok(ConnectionTestResult {
            success: false,
            error: Some(e.to_string()),
            latency_ms: None,
        }),
    }
}

/// Test Qdrant connection
#[tauri::command(rename_all = "snake_case")]
pub async fn test_qdrant_connection(settings: QdrantSettings) -> TauriResult<ConnectionTestResult> {
    use std::time::Instant;

    let start = Instant::now();

    let client = reqwest::Client::new();
    let url = format!(
        "{}/collections/{}",
        settings.url.trim_end_matches('/'),
        settings.collection
    );

    let mut request = client.get(&url).timeout(std::time::Duration::from_secs(10));

    if let Some(api_key) = settings.api_key {
        request = request.header("api-key", api_key);
    }

    let result = request.send().await;

    match result {
        Ok(response) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            if response.status().is_success() {
                Ok(ConnectionTestResult {
                    success: true,
                    error: None,
                    latency_ms: Some(latency_ms),
                })
            } else {
                let status = response.status();
                Ok(ConnectionTestResult {
                    success: false,
                    error: Some(format!("HTTP {}", status)),
                    latency_ms: Some(latency_ms),
                })
            }
        }
        Err(e) => Ok(ConnectionTestResult {
            success: false,
            error: Some(e.to_string()),
            latency_ms: None,
        }),
    }
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

    // Get project path
    let project_path = {
        let pm = state.project_manager.lock().await;
        let project = pm
            .get_project(&project_id)?
            .ok_or_else(|| TauriError::ProjectNotFound(project_id.clone()))?;
        PathBuf::from(&project.path)
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

    // Parse requirement and build DAG
    let subtasks = Orchestrator::parse_requirement(&input);
    let mut dag = Orchestrator::build_dag(subtasks.clone())?;
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
        Some("Orchestrating subtasks...".to_string()),
    )?;

    // Spawn background task for execution
    let app_handle_clone = app_handle.clone();
    let task_manager = state.task_manager.clone();
    let task_id_clone = task_id.clone();

    tokio::spawn(async move {
        // Load DAG from storage
        let mut dag = {
            let dags = ORCHESTRATION_DAGS.read().await;
            dags.get(&task_id_clone)
                .cloned()
                .unwrap_or_else(|| TaskDag::new())
        };

        let total_tasks = dag.len();
        let mut completed_count = 0;

        // Collect parallel groups upfront to avoid borrow issues
        let parallel_groups: Vec<Vec<_>> = dag
            .get_parallel_groups()
            .into_iter()
            .map(|group| group.into_iter().cloned().collect())
            .collect();

        // Execute in parallel groups
        for group_tasks in parallel_groups {
            // Emit subtask started events
            for task in &group_tasks {
                if let Err(e) = emit_subtask_started(
                    &app_handle_clone,
                    &task_id_clone,
                    &task.id,
                    &task.description,
                ) {
                    tracing::error!("Failed to emit subtask started event: {}", e);
                }
            }

            // Execute each task in the group (sequentially for now, can be parallelized later)
            for task in &group_tasks {
                // Create a task.md file for this subtask
                let task_dir = project_path.join(".smanclaw").join("tasks");
                if let Err(e) = std::fs::create_dir_all(&task_dir) {
                    tracing::error!("Failed to create task directory: {}", e);
                    continue;
                }

                let task_md_path = task_dir.join(format!("{}.md", task.id));
                let task_content = format!(
                    "# Task: {}\n\n## Description\n{}\n\n## Checklist\n- [ ] Execute task\n- [ ] Verify result",
                    task.description,
                    task.description
                );

                if let Err(e) = std::fs::write(&task_md_path, &task_content) {
                    tracing::error!("Failed to write task file: {}", e);
                    continue;
                }

                // Create executor for this task
                let skill_store = match SkillStore::new(&project_path) {
                    Ok(s) => s,
                    Err(e) => {
                        tracing::error!("Failed to create SkillStore: {}", e);
                        continue;
                    }
                };

                let mut executor = SubClawExecutor::new(&task_md_path, skill_store);
                executor.set_step_executor(std::sync::Arc::new(
                    MockStepExecutor::new("Mock execution response")
                ));

                // Execute the task
                let result = executor.run().await;

                // Update DAG with result
                if let Some(t) = dag.get_task_mut(&task.id) {
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

                completed_count += 1;

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
                let (success, output, error) = match &result {
                    Ok(r) => (r.success, format!("Completed {}/{} steps", r.steps_completed, r.steps_total), r.error.clone()),
                    Err(e) => (false, String::new(), Some(e.to_string())),
                };

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

        // Update task status
        let final_status = if all_completed {
            TaskStatus::Completed
        } else {
            TaskStatus::Failed
        };
        if let Err(e) = task_manager
            .lock()
            .await
            .update_task_status(&task_id_clone, final_status)
        {
            tracing::error!("Failed to update task status: {}", e);
        }

        // Emit final status
        let (status_str, message) = match all_completed {
            true => (
                "completed",
                Some(format!(
                    "All {} subtasks completed successfully",
                    total_tasks
                )),
            ),
            false => ("failed", Some("Some subtasks failed".to_string())),
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
