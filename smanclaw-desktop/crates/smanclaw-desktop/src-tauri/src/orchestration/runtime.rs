use smanclaw_core::{
    ExperienceSink, MainTaskManager, MainTaskStatus, SkillStore, SubClawExecutor, SubTaskStatus,
    TaskDag, TaskGenerator, TaskManager, TaskResultForExperience,
};
use smanclaw_ffi::{ZeroclawBridge, ZeroclawStepExecutor};
use smanclaw_types::AppSettings;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tauri::AppHandle;
use tokio::sync::Mutex;

use crate::commands::task_commands::subtask_file_stem;
use crate::commands::utility_commands::persist_task_experience_artifacts;
use crate::events::{
    emit_orchestration_progress, emit_subtask_completed, emit_subtask_started,
    emit_task_status_event, TaskEventStatus,
};

use super::{remediation::finalize_orchestration, ORCHESTRATION_DAGS};

pub(crate) fn spawn_orchestration_execution(
    app_handle: AppHandle,
    task_manager: Arc<Mutex<TaskManager>>,
    task_id: String,
    main_task_id: String,
    settings: AppSettings,
    input: String,
    project_path: PathBuf,
    subtask_file_stems: HashMap<String, String>,
) {
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

        let bridge = match ZeroclawBridge::from_project_with_settings(&project_path, &settings) {
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

        let mut dag = {
            let dags = ORCHESTRATION_DAGS.read().await;
            dags.get(&task_id).cloned().unwrap_or_else(TaskDag::new)
        };
        let mut total_tasks = dag.len();
        let mut completed_count = 0usize;
        let mut subtask_file_stems = subtask_file_stems;
        let mut next_subtask_sequence = subtask_file_stems.len() + 1;
        let parallel_groups: Vec<Vec<_>> = dag
            .get_parallel_groups()
            .into_iter()
            .map(|group| group.into_iter().cloned().collect())
            .collect();

        for (group_index, group_tasks) in parallel_groups.iter().enumerate() {
            if let Err(e) = emit_task_status_event(
                &app_handle,
                &task_id,
                TaskEventStatus::Running,
                Some(format!(
                    "主 Claw 正在执行第 {}/{} 批子任务...",
                    group_index + 1,
                    parallel_groups.len()
                )),
            ) {
                tracing::error!("Failed to emit group progress status: {}", e);
            }

            for task in group_tasks {
                if let Err(e) = emit_subtask_started(&app_handle, &task_id, &task.id, &task.description)
                {
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
                let task_md_path = match generate_task_md_path(
                    &mut dag,
                    &main_task_manager,
                    &main_task_id,
                    task_generator.as_ref(),
                    &mut subtask_file_stems,
                    &mut next_subtask_sequence,
                    &task.id,
                    task,
                    &app_handle,
                    &task_id,
                    &mut completed_count,
                    total_tasks,
                ) {
                    Some(path) => path,
                    None => continue,
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
                                executor
                                    .set_step_executor(Arc::new(ZeroclawStepExecutor::from_bridge(bridge)));
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
                        mark_subtask_failed(
                            &mut dag,
                            &main_task_manager,
                            &main_task_id,
                            &app_handle,
                            &task_id,
                            &task.id,
                            e,
                            &mut completed_count,
                            total_tasks,
                            "Failed to update subtask failed status after execution failure",
                        );
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
                if let Err(e) = main_task_manager.update_sub_task_status(
                    &main_task_id,
                    &task.id,
                    if result.success {
                        SubTaskStatus::Completed
                    } else {
                        SubTaskStatus::Failed
                    },
                ) {
                    tracing::error!("Failed to update subtask final status: {}", e);
                }
                if let Some(sink) = experience_sink.as_ref() {
                    let task_md_content = std::fs::read_to_string(&task_md_path).ok();
                    let output_text = result.error.clone().unwrap_or_else(|| {
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
                if let Err(e) = emit_task_status_event(
                    &app_handle,
                    &task_id,
                    TaskEventStatus::Running,
                    Some(format!(
                        "主 Claw 验证中：已完成 {}/{} 个子任务",
                        completed_count, total_tasks
                    )),
                ) {
                    tracing::error!("Failed to emit running status event: {}", e);
                }
                if let Err(e) =
                    emit_orchestration_progress(&app_handle, &task_id, completed_count, total_tasks)
                {
                    tracing::error!("Failed to emit progress event: {}", e);
                }
                if let Err(emit_err) = emit_subtask_completed(
                    &app_handle,
                    &task_id,
                    &task.id,
                    result.success,
                    &format!("Completed {}/{} steps", result.steps_completed, result.steps_total),
                    result.error.clone(),
                ) {
                    tracing::error!("Failed to emit subtask completed event: {}", emit_err);
                }
            }

            {
                let mut dags = ORCHESTRATION_DAGS.write().await;
                dags.insert(task_id.clone(), dag.clone());
            }
        }

        finalize_orchestration(
            &app_handle,
            &task_manager,
            &project_path,
            &input,
            &task_id,
            &main_task_id,
            &main_task_manager,
            &mut dag,
            &bridge,
            task_generator.as_ref(),
            &experience_sink,
            &mut subtask_file_stems,
            &mut next_subtask_sequence,
            &mut completed_count,
            &mut total_tasks,
        )
        .await;
    });
}

#[allow(clippy::too_many_arguments)]
fn generate_task_md_path(
    dag: &mut TaskDag,
    main_task_manager: &MainTaskManager,
    main_task_id: &str,
    task_generator: Option<&TaskGenerator>,
    subtask_file_stems: &mut HashMap<String, String>,
    next_subtask_sequence: &mut usize,
    task_id: &str,
    task: &smanclaw_core::SubTask,
    app_handle: &AppHandle,
    orchestration_task_id: &str,
    completed_count: &mut usize,
    total_tasks: usize,
) -> Option<PathBuf> {
    if let Some(generator) = task_generator {
        let file_stem = subtask_file_stems
            .entry(task_id.to_string())
            .or_insert_with(|| {
                let stem = subtask_file_stem(main_task_id, *next_subtask_sequence);
                *next_subtask_sequence += 1;
                stem
            })
            .clone();
        match generator.generate_named(task, &file_stem) {
            Ok(path) => Some(path),
            Err(e) => {
                mark_subtask_failed(
                    dag,
                    main_task_manager,
                    main_task_id,
                    app_handle,
                    orchestration_task_id,
                    task_id,
                    format!("task 文件生成失败: {}", e),
                    completed_count,
                    total_tasks,
                    "Failed to update subtask failed status after task file generation failure",
                );
                None
            }
        }
    } else {
        mark_subtask_failed(
            dag,
            main_task_manager,
            main_task_id,
            app_handle,
            orchestration_task_id,
            task_id,
            "TaskGenerator unavailable".to_string(),
            completed_count,
            total_tasks,
            "Failed to update subtask failed status after generator unavailable",
        );
        None
    }
}

#[allow(clippy::too_many_arguments)]
fn mark_subtask_failed(
    dag: &mut TaskDag,
    main_task_manager: &MainTaskManager,
    main_task_id: &str,
    app_handle: &AppHandle,
    orchestration_task_id: &str,
    task_id: &str,
    message: String,
    completed_count: &mut usize,
    total_tasks: usize,
    status_error: &str,
) {
    if let Some(t) = dag.get_task_mut(task_id) {
        t.status = SubTaskStatus::Failed;
        t.result = Some(message.clone());
    }
    if let Err(update_err) =
        main_task_manager.update_sub_task_status(main_task_id, task_id, SubTaskStatus::Failed)
    {
        tracing::error!("{}: {}", status_error, update_err);
    }
    *completed_count += 1;
    if let Err(progress_err) =
        emit_orchestration_progress(app_handle, orchestration_task_id, *completed_count, total_tasks)
    {
        tracing::error!("Failed to emit progress after failure: {}", progress_err);
    }
    if let Err(emit_err) = emit_subtask_completed(
        app_handle,
        orchestration_task_id,
        task_id,
        false,
        "",
        Some(message),
    ) {
        tracing::error!("Failed to emit subtask completion after failure: {}", emit_err);
    }
}
