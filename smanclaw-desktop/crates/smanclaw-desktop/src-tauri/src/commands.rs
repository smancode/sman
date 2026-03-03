//! Tauri commands for frontend communication

use chrono::Utc;
use smanclaw_ffi::ZeroclawBridge;
use smanclaw_types::{
    Conversation, FileAction, HistoryEntry, Project, ProjectConfig, Role, Task,
    TaskStatus,
};
use std::path::PathBuf;
use std::sync::Arc;
use tauri::{AppHandle, State};

use crate::error::{TauriError, TauriResult};
use crate::events::{emit_file_change, emit_progress_event, emit_task_status};
use crate::state::AppState;

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
pub async fn add_project(
    state: State<'_, AppState>,
    path: String,
) -> TauriResult<Project> {
    if path.is_empty() {
        return Err(TauriError::InvalidInput("Project path cannot be empty".to_string()));
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
#[tauri::command]
pub async fn remove_project(
    state: State<'_, AppState>,
    project_id: String,
) -> TauriResult<()> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput("Project ID cannot be empty".to_string()));
    }

    let mut pm = state.project_manager.lock().await;
    pm.remove_project(&project_id)?;
    Ok(())
}

/// Get project configuration
#[tauri::command]
pub async fn get_project_config(
    state: State<'_, AppState>,
    project_id: String,
) -> TauriResult<ProjectConfig> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput("Project ID cannot be empty".to_string()));
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
        return Err(TauriError::InvalidInput("Project ID cannot be empty".to_string()));
    }

    let mut pm = state.project_manager.lock().await;
    pm.update_project_config(&config)?;
    Ok(())
}

// ============================================================================
// Task Commands
// ============================================================================

/// Execute a task
#[tauri::command]
pub async fn execute_task(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    project_id: String,
    input: String,
) -> TauriResult<Task> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput("Project ID cannot be empty".to_string()));
    }
    if input.is_empty() {
        return Err(TauriError::InvalidInput("Task input cannot be empty".to_string()));
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

    // Emit task started event
    emit_task_status(&app_handle, &task_id, "running", None)?;

    // Create bridge and execute task
    let bridge = Arc::new(ZeroclawBridge::from_project(&project_path)?);
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
                // Update task status
                let status = if result.success {
                    TaskStatus::Completed
                } else {
                    TaskStatus::Failed
                };
                if let Err(e) = task_manager.lock().await.update_task_status(&task_id, status) {
                    tracing::error!("Failed to update task status: {}", e);
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
                let status_str = if result.success { "completed" } else { "failed" };
                let message = if result.success {
                    Some(result.output.clone())
                } else {
                    result.error.clone()
                };
                if let Err(e) = emit_task_status(&app_handle_clone, &task_id, status_str, message) {
                    tracing::error!("Failed to emit task status event: {}", e);
                }
            }
            Err(e) => {
                tracing::error!("Task execution failed: {}", e);
                if let Err(update_err) = task_manager.lock().await.update_task_status(&task_id, TaskStatus::Failed) {
                    tracing::error!("Failed to update task status: {}", update_err);
                }
                if let Err(emit_err) = emit_task_status(
                    &app_handle_clone,
                    &task_id,
                    "failed",
                    Some(e.to_string()),
                ) {
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
#[tauri::command]
pub async fn get_task(
    state: State<'_, AppState>,
    task_id: String,
) -> TauriResult<Option<Task>> {
    if task_id.is_empty() {
        return Err(TauriError::InvalidInput("Task ID cannot be empty".to_string()));
    }

    let tm = state.task_manager.lock().await;
    let task = tm.get_task(&task_id)?;
    Ok(task)
}

/// List tasks for a project
#[tauri::command]
pub async fn list_tasks(
    state: State<'_, AppState>,
    project_id: String,
) -> TauriResult<Vec<Task>> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput("Project ID cannot be empty".to_string()));
    }

    let tm = state.task_manager.lock().await;
    let tasks = tm.list_tasks(&project_id)?;
    Ok(tasks)
}

// ============================================================================
// Conversation Commands
// ============================================================================

/// Get conversation history
#[tauri::command]
pub async fn get_conversation(
    state: State<'_, AppState>,
    conversation_id: String,
) -> TauriResult<Option<Conversation>> {
    if conversation_id.is_empty() {
        return Err(TauriError::InvalidInput("Conversation ID cannot be empty".to_string()));
    }

    let hs = state.history_store.lock().await;
    let conversation = hs.get_conversation(&conversation_id)?;
    Ok(conversation)
}

/// Get conversation messages
#[tauri::command]
pub async fn get_conversation_messages(
    state: State<'_, AppState>,
    conversation_id: String,
) -> TauriResult<Vec<HistoryEntry>> {
    if conversation_id.is_empty() {
        return Err(TauriError::InvalidInput("Conversation ID cannot be empty".to_string()));
    }

    let hs = state.history_store.lock().await;
    let entries = hs.load_conversation(&conversation_id)?;
    Ok(entries)
}

/// List conversations for a project
#[tauri::command]
pub async fn list_conversations(
    state: State<'_, AppState>,
    project_id: String,
) -> TauriResult<Vec<Conversation>> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput("Project ID cannot be empty".to_string()));
    }

    let hs = state.history_store.lock().await;
    let conversations = hs.list_conversations(&project_id)?;
    Ok(conversations)
}

/// Create a new conversation
#[tauri::command]
pub async fn create_conversation(
    state: State<'_, AppState>,
    project_id: String,
    title: String,
) -> TauriResult<Conversation> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput("Project ID cannot be empty".to_string()));
    }
    if title.is_empty() {
        return Err(TauriError::InvalidInput("Conversation title cannot be empty".to_string()));
    }

    let mut hs = state.history_store.lock().await;
    let conversation = hs.create_conversation(&project_id, &title)?;
    Ok(conversation)
}

/// Send a message in a conversation
#[tauri::command]
pub async fn send_message(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    conversation_id: String,
    content: String,
) -> TauriResult<HistoryEntry> {
    if conversation_id.is_empty() {
        return Err(TauriError::InvalidInput("Conversation ID cannot be empty".to_string()));
    }
    if content.is_empty() {
        return Err(TauriError::InvalidInput("Message content cannot be empty".to_string()));
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

    // Execute task and get response
    let bridge = Arc::new(ZeroclawBridge::from_project(&project_path)?);
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
        match bridge.execute_task_stream(&conv_id, &content_clone, tx).await {
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
pub async fn select_folder(
    app_handle: AppHandle,
) -> TauriResult<Option<String>> {
    use tauri_plugin_dialog::DialogExt;

    let folder = app_handle
        .dialog()
        .file()
        .blocking_pick_folder();

    Ok(folder.map(|p| p.to_string()))
}
