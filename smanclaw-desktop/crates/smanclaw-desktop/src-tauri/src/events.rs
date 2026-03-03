//! Progress event emission for Tauri frontend

use smanclaw_types::ProgressEvent;
use tauri::{AppHandle, Emitter};

/// Emit a progress event to the frontend
pub fn emit_progress_event(
    app_handle: &AppHandle,
    event: &ProgressEvent,
) -> Result<(), tauri::Error> {
    app_handle.emit("progress", event)
}

/// Event payload for task status changes
#[derive(Debug, Clone, serde::Serialize)]
pub struct TaskStatusEvent {
    /// Task ID
    pub task_id: String,
    /// New status
    pub status: String,
    /// Message
    pub message: Option<String>,
}

/// Emit a task status event to the frontend
pub fn emit_task_status(
    app_handle: &AppHandle,
    task_id: &str,
    status: &str,
    message: Option<String>,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "task-status",
        TaskStatusEvent {
            task_id: task_id.to_string(),
            status: status.to_string(),
            message,
        },
    )
}

/// Event payload for file changes
#[derive(Debug, Clone, serde::Serialize)]
pub struct FileChangeEvent {
    /// File path
    pub path: String,
    /// Action (created/modified/deleted)
    pub action: String,
}

/// Emit a file change event to the frontend
pub fn emit_file_change(
    app_handle: &AppHandle,
    path: &str,
    action: &str,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "file-change",
        FileChangeEvent {
            path: path.to_string(),
            action: action.to_string(),
        },
    )
}
