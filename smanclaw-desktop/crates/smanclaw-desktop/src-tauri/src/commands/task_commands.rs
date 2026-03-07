use smanclaw_ffi::ZeroclawBridge;
use smanclaw_types::{FileAction, Task, TaskStatus};
use smanclaw_core::{SubTask, SubTaskStatus, TaskDag};
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Arc;
use tauri::{AppHandle, State};

use crate::error::{TauriError, TauriResult};
use crate::events::{
    emit_file_change, emit_progress_event, emit_task_status_event, task_event_status_from_success,
    TaskEventStatus,
};
use crate::state::AppState;

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

    let task = {
        let tm = state.task_manager.lock().await;
        tm.create_task(&project_id, &input)?
    };
    let task_id = task.id.clone();

    {
        let pm = state.project_manager.lock().await;
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

    emit_task_status_event(&app_handle, &task_id, TaskEventStatus::Running, None)?;

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

        match bridge.execute_task_stream(&task_id, &input_clone, tx).await {
            Ok(result) => {
                tracing::info!(
                    "execute_task: bridge.execute_task_stream succeeded, output length: {}, output: {}",
                    result.output.len(),
                    result.output
                );

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

                let status = task_event_status_from_success(result.success);
                let message = if result.success {
                    Some(result.output.clone())
                } else {
                    result.error.clone()
                };
                tracing::info!(
                    "Emitting task status: task_id={}, status={}, message={}",
                    task_id,
                    status.as_str(),
                    message.as_deref().unwrap_or("")
                );
                if let Err(e) =
                    emit_task_status_event(&app_handle_clone, &task_id, status, message)
                {
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
                if let Err(emit_err) = emit_task_status_event(
                    &app_handle_clone,
                    &task_id,
                    TaskEventStatus::Failed,
                    Some(e.to_string()),
                ) {
                    tracing::error!("Failed to emit task status event: {}", emit_err);
                }
            }
        }

        forward_handle.await.ok();
    });

    Ok(task)
}

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
    {
        let tm = state.task_manager.lock().await;
        tm.update_task_status(&task_id, TaskStatus::Failed)?;
    }
    emit_task_status_event(
        &app_handle,
        &task_id,
        TaskEventStatus::Cancelled,
        Some("Task cancelled by user".to_string()),
    )?;
    Ok(())
}

pub(crate) fn subtask_file_stem(main_task_id: &str, sequence: usize) -> String {
    format!("task-{}-{:03}", main_task_id, sequence)
}

pub(crate) fn subtask_relative_path(file_stem: &str) -> String {
    format!(".smanclaw/tasks/{}.md", file_stem)
}

pub(crate) fn build_remediation_subtasks(
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

    crate::commands::orchestration_decompose::enforce_subtask_context_independence(
        remediation_tasks,
        "补救失败子任务并完成验收回归",
    )
}
