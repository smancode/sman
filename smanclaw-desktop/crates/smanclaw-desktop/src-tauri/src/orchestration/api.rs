use smanclaw_core::{MainTaskManager, MainTaskStatus, Orchestrator, SubTaskRef};
use smanclaw_types::TaskStatus;
use std::collections::HashMap;
use std::path::PathBuf;
use tauri::{AppHandle, State};

use crate::commands::orchestration_decompose;
use crate::commands::task_commands::{subtask_file_stem, subtask_relative_path};
use crate::commands::utility_commands::persist_user_input_experience;
use crate::error::{TauriError, TauriResult};
use crate::events::{emit_task_dag, emit_task_status_event, SubTaskInfo, TaskEventStatus};
use crate::state::AppState;

use super::status::OrchestratedTaskResult;
use super::{runtime, ORCHESTRATION_DAGS};

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

    if let Err(e) = emit_task_status_event(
        &app_handle,
        &task_id,
        TaskEventStatus::Running,
        Some("主 Claw 正在进行语义拆解与依赖分析...".to_string()),
    ) {
        tracing::error!("Failed to emit decomposition status event: {}", e);
    }

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

    {
        let mut dags = ORCHESTRATION_DAGS.write().await;
        dags.insert(task_id.clone(), dag);
    }

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
    emit_task_status_event(
        &app_handle,
        &task_id,
        TaskEventStatus::Running,
        Some(format!("主 Claw 已生成 {} 个子任务，准备分发执行...", subtasks.len())),
    )?;

    runtime::spawn_orchestration_execution(
        app_handle,
        state.task_manager.clone(),
        task_id.clone(),
        main_task.id.clone(),
        settings.clone(),
        input.clone(),
        project_path,
        subtask_file_stems,
    );

    Ok(OrchestratedTaskResult {
        task_id,
        subtask_count: subtasks.len(),
        parallel_groups,
    })
}
