//! Tauri commands for frontend communication

use chrono::Utc;
use smanclaw_core::{
    AcceptanceEvaluator, MainTaskManager, MainTaskResult, MainTaskStatus, Orchestrator,
    SkillStore, SubClawExecutor, SubTask, SubTaskRef, SubTaskStatus, TaskDag, TaskGenerator,
    TaskResultForExperience,
    VerificationMethod, ExperienceSink,
};
use smanclaw_ffi::{ZeroclawBridge, ZeroclawStepExecutor};
use smanclaw_types::{
    AppSettings, ConnectionTestResult, Conversation, EmbeddingSettings, FileAction, HistoryEntry,
    LlmSettings, Project, ProjectConfig, QdrantSettings, Role, Task, TaskStatus,
};
use std::collections::{HashMap, HashSet};
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

#[derive(Debug, serde::Deserialize)]
struct SemanticDecomposeResponse {
    subtasks: Vec<SemanticSubTask>,
}

#[derive(Debug, serde::Deserialize)]
struct SemanticSubTask {
    id: Option<String>,
    description: String,
    #[serde(default)]
    depends_on: Vec<String>,
    #[serde(default)]
    test_command: Option<String>,
}

async fn try_semantic_decompose_with_zeroclaw(
    project_path: &std::path::Path,
    settings: &AppSettings,
    input: &str,
) -> Option<Vec<SubTask>> {
    if !settings.llm.is_configured() {
        return None;
    }

    let bridge = ZeroclawBridge::from_project_with_settings(project_path, settings).ok()?;
    let prompt = build_semantic_decompose_prompt(input);
    let result = bridge.execute_task_async(&prompt).await.ok()?;
    let parsed = parse_semantic_subtasks(&result.output)?;
    if Orchestrator::build_dag(parsed.clone()).is_err() {
        return None;
    }
    Some(parsed)
}

fn build_semantic_decompose_prompt(input: &str) -> String {
    format!(
        "你是任务拆解器。把用户需求拆成可执行子任务，必要时可调用已安装 skills（如 ClawHub 安装的能力），但最终只输出 JSON，不要输出解释。\n\
输出必须是一个 JSON 对象，结构如下：\n\
{{\"subtasks\":[{{\"id\":\"task-1\",\"description\":\"...\",\"depends_on\":[],\"test_command\":\"...\"}}]}}\n\
规则：\n\
1) id 唯一，使用 kebab-case。\n\
2) depends_on 只能引用已有 id，不允许自依赖。\n\
3) 子任务要覆盖：实现、验证、回归。\n\
4) 如果需求很小，也至少拆成 2 个子任务。\n\
5) 不要使用 markdown 代码块包裹。\n\
用户需求：{}",
        input
    )
}

fn parse_semantic_subtasks(output: &str) -> Option<Vec<SubTask>> {
    let payload = extract_json_payload(output)?;
    let response: SemanticDecomposeResponse = serde_json::from_str(&payload).ok()?;
    normalize_semantic_subtasks(response)
}

fn extract_json_payload(output: &str) -> Option<String> {
    let trimmed = output.trim();
    if serde_json::from_str::<serde_json::Value>(trimmed).is_ok() {
        return Some(trimmed.to_string());
    }

    if let Some(start) = trimmed.find("```json") {
        let rest = &trimmed[start + 7..];
        if let Some(end) = rest.find("```") {
            let candidate = rest[..end].trim();
            if serde_json::from_str::<serde_json::Value>(candidate).is_ok() {
                return Some(candidate.to_string());
            }
        }
    }

    if let Some(start) = trimmed.find("```") {
        let rest = &trimmed[start + 3..];
        if let Some(end) = rest.find("```") {
            let candidate = rest[..end].trim();
            if serde_json::from_str::<serde_json::Value>(candidate).is_ok() {
                return Some(candidate.to_string());
            }
        }
    }

    let mut depth = 0usize;
    let mut start_idx = None;
    for (idx, ch) in trimmed.char_indices() {
        if ch == '{' {
            if start_idx.is_none() {
                start_idx = Some(idx);
            }
            depth += 1;
        } else if ch == '}' {
            if depth == 0 {
                continue;
            }
            depth -= 1;
            if depth == 0 {
                if let Some(start) = start_idx {
                    let candidate = trimmed[start..=idx].trim();
                    if serde_json::from_str::<serde_json::Value>(candidate).is_ok() {
                        return Some(candidate.to_string());
                    }
                }
                start_idx = None;
            }
        }
    }

    None
}

fn sanitize_task_id(raw: &str, fallback_index: usize) -> String {
    let mut cleaned = raw
        .trim()
        .to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .collect::<String>();
    cleaned = cleaned.replace('_', "-");
    if cleaned.is_empty() || cleaned.chars().next().is_some_and(|c| c.is_ascii_digit()) {
        return format!("task-{}", fallback_index + 1);
    }
    cleaned
}

fn normalize_semantic_subtasks(response: SemanticDecomposeResponse) -> Option<Vec<SubTask>> {
    if response.subtasks.is_empty() {
        return None;
    }

    let mut ids = Vec::with_capacity(response.subtasks.len());
    let mut seen = HashSet::new();

    for (idx, task) in response.subtasks.iter().enumerate() {
        let mut id = sanitize_task_id(task.id.as_deref().unwrap_or_default(), idx);
        if seen.contains(&id) {
            let mut suffix = 2usize;
            loop {
                let candidate = format!("{id}-{suffix}");
                if !seen.contains(&candidate) {
                    id = candidate;
                    break;
                }
                suffix += 1;
            }
        }
        seen.insert(id.clone());
        ids.push(id);
    }

    let id_set = ids.iter().cloned().collect::<HashSet<_>>();
    let mut subtasks = Vec::new();

    for (idx, task) in response.subtasks.into_iter().enumerate() {
        let description = task.description.trim().to_string();
        if description.is_empty() {
            continue;
        }

        let id = ids.get(idx).cloned().unwrap_or_else(|| format!("task-{}", idx + 1));
        let mut subtask = SubTask::new(id.clone(), description);

        for dep in task.depends_on {
            let dep_id = sanitize_task_id(&dep, idx);
            if dep_id != id && id_set.contains(&dep_id) {
                subtask = subtask.depends_on(dep_id);
            }
        }

        if let Some(cmd) = task.test_command.map(|s| s.trim().to_string()) {
            if !cmd.is_empty() {
                subtask = subtask.with_test_command(cmd);
            }
        }

        subtasks.push(subtask);
    }

    if subtasks.is_empty() {
        None
    } else {
        Some(subtasks)
    }
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
    let subtasks = match try_semantic_decompose_with_zeroclaw(&project_path, &settings, &input).await
    {
        Some(tasks) => tasks,
        None => Orchestrator::parse_requirement(&input),
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
            loaded_main_task.add_acceptance_criterion(format!(
                "content: .smanclaw/tasks/{}.md contains '- [x]'",
                task.id
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

        let total_tasks = dag.len();
        let mut completed_count = 0;

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

            // Execute each task in the group (sequentially for now, can be parallelized later)
            for task in group_tasks {
                if let Err(e) = main_task_manager.update_sub_task_status(
                    &main_task_id,
                    &task.id,
                    SubTaskStatus::Running,
                ) {
                    tracing::error!("Failed to update subtask running status: {}", e);
                }

                let task_md_path = if let Some(generator) = &task_generator {
                    match generator.generate(task) {
                        Ok(path) => path,
                        Err(e) => {
                            tracing::error!("Failed to generate task.md for {}: {}", task.id, e);
                            continue;
                        }
                    }
                } else {
                    tracing::error!("TaskGenerator unavailable, skip subtask {}", task.id);
                    continue;
                };

                // Create executor for this task
                let skill_store = match SkillStore::new(&project_path) {
                    Ok(s) => s,
                    Err(e) => {
                        tracing::error!("Failed to create SkillStore: {}", e);
                        continue;
                    }
                };

                let mut executor = SubClawExecutor::new(&task_md_path, skill_store);
                if let Some(ref bridge) = bridge {
                    executor.set_step_executor(Arc::new(ZeroclawStepExecutor::from_bridge(
                        bridge.clone(),
                    )));
                }

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

                let subtask_status = match &result {
                    Ok(exec_result) if exec_result.success => SubTaskStatus::Completed,
                    _ => SubTaskStatus::Failed,
                };
                if let Err(e) =
                    main_task_manager.update_sub_task_status(&main_task_id, &task.id, subtask_status)
                {
                    tracing::error!("Failed to update subtask final status: {}", e);
                }

                if let (Some(sink), Ok(exec_result)) = (experience_sink.as_ref(), &result) {
                    let task_md_content = std::fs::read_to_string(&task_md_path).ok();
                    let output_text = exec_result
                        .error
                        .clone()
                        .unwrap_or_else(|| {
                            format!(
                                "Completed {}/{} steps",
                                exec_result.steps_completed, exec_result.steps_total
                            )
                        });
                    let mut task_result = TaskResultForExperience::new(
                        task.id.clone(),
                        task.description.clone(),
                        output_text,
                        exec_result.success,
                    )
                    .with_files(vec![task_md_path.display().to_string()]);
                    if let Some(content) = task_md_content {
                        task_result = task_result.with_task_md(content);
                    }
                    if let Ok(experience) = sink.extract_experience(&task_result) {
                        if sink.should_update_skill(&experience) {
                            if let Err(e) = sink.update_skill(&experience) {
                                tracing::error!("Failed to update skill from experience: {}", e);
                            }
                        }
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

        if let Err(e) = main_task_manager.update_status(&main_task_id, MainTaskStatus::Verifying) {
            tracing::error!("Failed to update main task verifying status: {}", e);
        }

        let evaluator = AcceptanceEvaluator::new(&project_path);
        let acceptance_criteria = dag
            .tasks_in_order()
            .iter()
            .map(|task| smanclaw_core::AcceptanceCriteria::Functional {
                id: format!("subtask-{}", task.id),
                description: format!("content: .smanclaw/tasks/{}.md contains '- [x]'", task.id),
                verification_method: VerificationMethod::ContentMatch,
            })
            .collect::<Vec<_>>();
        let evaluation = evaluator.evaluate(&acceptance_criteria);
        let evaluation_passed = evaluation
            .as_ref()
            .map(|result| result.overall_passed)
            .unwrap_or(false);
        let final_passed = all_completed && evaluation_passed;

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
            if !all_completed {
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
                    .map(|task| format!(".smanclaw/tasks/{}.md", task.id))
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
    use super::{
        extract_json_payload, normalize_semantic_subtasks, parse_semantic_subtasks,
        sanitize_task_id, SemanticDecomposeResponse, SemanticSubTask,
    };

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
}
