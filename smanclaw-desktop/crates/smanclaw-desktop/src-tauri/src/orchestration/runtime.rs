use smanclaw_core::{
    DependencyInput, ExperienceSink, MainTaskManager, MainTaskStatus, SkillStore, SubClawExecutor,
    SubTaskContextContract, SubTaskStatus, TaskDag, TaskGenerator, TaskManager,
};
use smanclaw_ffi::{ZeroclawBridge, ZeroclawStepExecutor};
use smanclaw_types::AppSettings;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tauri::AppHandle;
use tokio::sync::Mutex;

use crate::commands::task_commands::subtask_file_stem;
use crate::events::{
    emit_orchestration_progress, emit_subtask_completed, emit_subtask_started,
    emit_task_status_event, TaskEventStatus,
};

use super::task_outcome::{
    apply_execution_outcome, emit_progress_and_completion, persist_execution_experience,
    subtask_status_from_success,
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
                if let Err(e) =
                    emit_subtask_started(&app_handle, &task_id, &task.id, &task.description)
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

                let output_text = apply_execution_outcome(
                    &mut dag,
                    &task.id,
                    result.success,
                    result.error.clone(),
                    result.steps_completed,
                    result.steps_total,
                );
                if let Err(e) = main_task_manager.update_sub_task_status(
                    &main_task_id,
                    &task.id,
                    subtask_status_from_success(result.success),
                ) {
                    tracing::error!("Failed to update subtask final status: {}", e);
                }
                let experience_output = result.error.as_deref().unwrap_or(output_text.as_str());
                persist_execution_experience(
                    &project_path,
                    experience_sink.as_ref(),
                    &task.id,
                    &task.description,
                    &task_md_path,
                    experience_output,
                    result.success,
                );
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
                emit_progress_and_completion(
                    &app_handle,
                    &task_id,
                    &task.id,
                    result.success,
                    &output_text,
                    result.error.clone(),
                    completed_count,
                    total_tasks,
                );
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
        let contract = build_subtask_context_contract(dag, task);
        let file_stem = subtask_file_stems
            .entry(task_id.to_string())
            .or_insert_with(|| {
                let stem = subtask_file_stem(main_task_id, *next_subtask_sequence);
                *next_subtask_sequence += 1;
                stem
            })
            .clone();
        match generator.generate_named_with_contract(task, &file_stem, &contract) {
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

fn build_subtask_context_contract(
    dag: &TaskDag,
    task: &smanclaw_core::SubTask,
) -> SubTaskContextContract {
    let mut readonly_context = vec!["项目既有架构与公共约束".to_string()];
    if !task.depends_on.is_empty() {
        readonly_context.push(format!(
            "仅可读取依赖任务输出，不可要求依赖任务进行额外沟通: {}",
            task.depends_on.join(", ")
        ));
    }
    let mut dependency_inputs = Vec::new();
    for dep_id in &task.depends_on {
        let summary = dag
            .get_task(dep_id)
            .and_then(|dep| dep.result.clone())
            .map(|text| summarize_dependency_output(&text))
            .unwrap_or_else(|| "依赖任务尚未产生输出，按契约仅可读取已落盘结果".to_string());
        dependency_inputs.push(DependencyInput {
            task_id: dep_id.clone(),
            summary,
        });
    }
    let mut acceptance_checks = vec![
        "确保实现结果满足子任务目标并可复现".to_string(),
        "确保新增或更新测试放在 tests 目录并通过".to_string(),
    ];
    if let Some(test_command) = task.test_command.as_ref() {
        acceptance_checks.push(format!("执行并通过 `{}`", test_command));
    } else {
        acceptance_checks.push("执行与任务相关的验证命令并记录结果".to_string());
    }
    SubTaskContextContract {
        writable_scope: vec![
            "仅修改与当前子任务直接相关的源码文件".to_string(),
            "仅修改 tests/ 下与当前子任务相关的测试文件".to_string(),
        ],
        readonly_context,
        dependency_inputs,
        acceptance_checks,
        constraints: vec![
            "必须在独立上下文内完成，不依赖其他子任务的实时对话".to_string(),
            "禁止要求其他子任务补充口头说明；仅基于已提供输入与代码现状执行".to_string(),
            "若信息不足，先在当前任务内补齐最小必要上下文再继续实现".to_string(),
        ],
    }
}

fn summarize_dependency_output(raw: &str) -> String {
    let compact = raw
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .replace('\n', " ");
    let char_count = compact.chars().count();
    if char_count <= 180 {
        return compact;
    }
    format!("{}...", compact.chars().take(180).collect::<String>())
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
    if let Err(progress_err) = emit_orchestration_progress(
        app_handle,
        orchestration_task_id,
        *completed_count,
        total_tasks,
    ) {
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
        tracing::error!(
            "Failed to emit subtask completion after failure: {}",
            emit_err
        );
    }
}
