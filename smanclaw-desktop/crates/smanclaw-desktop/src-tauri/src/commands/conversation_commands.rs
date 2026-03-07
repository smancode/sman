use smanclaw_types::{Conversation, HistoryEntry};
use std::path::PathBuf;
use tauri::{AppHandle, State};

use crate::commands::chat_execution;
use crate::commands::history_runtime::{open_project_history_store, resolve_conversation_project};
use crate::error::{TauriError, TauriResult};
use crate::state::AppState;

async fn get_project_path(state: &State<'_, AppState>, project_id: &str) -> TauriResult<PathBuf> {
    let pm = state.project_manager.lock().await;
    let project = pm
        .get_project(project_id)?
        .ok_or_else(|| TauriError::ProjectNotFound(project_id.to_string()))?;
    Ok(PathBuf::from(project.path))
}

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
    let resolved = resolve_conversation_project(&state, &conversation_id).await?;
    Ok(resolved.map(|(conversation, _)| conversation))
}

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
    let (_, project_path) = resolve_conversation_project(&state, &conversation_id)
        .await?
        .ok_or_else(|| TauriError::ConversationNotFound(conversation_id.clone()))?;
    let history_store = open_project_history_store(&state.config_dir, &project_path)?;
    let entries = history_store.load_conversation(&conversation_id)?;
    Ok(entries)
}

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
    let project_path = get_project_path(&state, &project_id).await.map_err(|error| {
        tracing::error!(
            project_id = %project_id,
            error = %error,
            "Failed to resolve project path when listing conversations"
        );
        error
    })?;
    let history_store =
        open_project_history_store(&state.config_dir, &project_path).map_err(|error| {
            tracing::error!(
                project_id = %project_id,
                project_path = %project_path.display(),
                error = %error,
                "Failed to open project history store when listing conversations"
            );
            error
        })?;
    let conversations = history_store.list_conversations(&project_id).map_err(|error| {
        tracing::error!(
            project_id = %project_id,
            project_path = %project_path.display(),
            error = %error,
            "Failed to list conversations from history store"
        );
        TauriError::from(error)
    })?;
    Ok(conversations)
}

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
    let project_path = get_project_path(&state, &project_id).await.map_err(|error| {
        tracing::error!(
            project_id = %project_id,
            error = %error,
            "Failed to resolve project path when creating conversation"
        );
        error
    })?;
    let history_store =
        open_project_history_store(&state.config_dir, &project_path).map_err(|error| {
            tracing::error!(
                project_id = %project_id,
                project_path = %project_path.display(),
                error = %error,
                "Failed to open project history store when creating conversation"
            );
            error
        })?;
    let conversation = history_store
        .create_conversation(&project_id, &title)
        .map_err(|error| {
            tracing::error!(
                project_id = %project_id,
                project_path = %project_path.display(),
                title = %title,
                error = %error,
                "Failed to create conversation in history store"
            );
            TauriError::from(error)
        })?;
    Ok(conversation)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn send_message(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    conversation_id: String,
    content: String,
) -> TauriResult<HistoryEntry> {
    chat_execution::send_message(app_handle, state, conversation_id, content).await
}
