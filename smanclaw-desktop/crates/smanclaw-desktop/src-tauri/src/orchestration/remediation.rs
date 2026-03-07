use smanclaw_core::{
    AcceptanceEvaluator, ExperienceSink, MainTaskManager, MainTaskResult, SubClawExecutor,
    SkillStore, SubTaskRef, SubTaskStatus, TaskDag, TaskGenerator, TaskManager,
    TaskResultForExperience, VerificationMethod,
};
use smanclaw_ffi::{ZeroclawBridge, ZeroclawStepExecutor};
use smanclaw_types::TaskStatus;
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use tauri::AppHandle;
use tokio::sync::Mutex;

use crate::commands::task_commands::{build_remediation_subtasks, subtask_file_stem, subtask_relative_path};
use crate::commands::utility_commands::persist_task_experience_artifacts;
use crate::events::{
    emit_orchestration_progress, emit_subtask_completed, emit_subtask_started,
    emit_task_status_event, emit_test_result, task_event_status_from_success, TaskEventStatus,
};

#[allow(clippy::too_many_arguments)]
pub(crate) async fn finalize_orchestration(
    app_handle: &AppHandle,
    task_manager: &Arc<Mutex<TaskManager>>,
    project_path: &Path,
    input: &str,
    task_id: &str,
    main_task_id: &str,
    main_task_manager: &MainTaskManager,
    dag: &mut TaskDag,
    bridge: &Option<Arc<ZeroclawBridge>>,
    task_generator: Option<&TaskGenerator>,
    experience_sink: &Option<ExperienceSink>,
    subtask_file_stems: &mut HashMap<String, String>,
    next_subtask_sequence: &mut usize,
    completed_count: &mut usize,
    total_tasks: &mut usize,
) {
    let all_completed = dag
        .tasks_in_order()
        .iter()
        .all(|t| t.status == SubTaskStatus::Completed);
    if let Err(e) = main_task_manager.update_status(main_task_id, smanclaw_core::MainTaskStatus::Verifying) {
        tracing::error!("Failed to update main task verifying status: {}", e);
    }
    let evaluator = AcceptanceEvaluator::new(project_path);
    let mut evaluation = evaluator.evaluate(&build_acceptance_criteria(dag, subtask_file_stems));
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
            build_remediation_subtasks(dag, evaluation.as_ref().ok(), remediation_round);
        if let Err(e) = emit_task_status_event(
            app_handle,
            task_id,
            TaskEventStatus::Running,
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
            *total_tasks = dag.len();
            let sub_task_ref = SubTaskRef::new(&remediation_task.id, &remediation_task.description);
            if let Err(e) = main_task_manager.add_sub_task(main_task_id, &sub_task_ref) {
                tracing::error!("Failed to append remediation subtask: {}", e);
            }
            if let Err(e) = emit_subtask_started(
                app_handle,
                task_id,
                &remediation_task.id,
                &remediation_task.description,
            ) {
                tracing::error!("Failed to emit remediation subtask started event: {}", e);
            }
            if let Err(e) = main_task_manager.update_sub_task_status(
                main_task_id,
                &remediation_task.id,
                SubTaskStatus::Running,
            ) {
                tracing::error!("Failed to update remediation subtask running status: {}", e);
            }
            let task_md_path = if let Some(generator) = task_generator {
                let file_stem = subtask_file_stem(main_task_id, *next_subtask_sequence);
                *next_subtask_sequence += 1;
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

            let skill_store = match SkillStore::new(project_path) {
                Ok(s) => s,
                Err(e) => {
                    tracing::error!("Failed to create SkillStore for remediation: {}", e);
                    continue;
                }
            };
            let mut executor = SubClawExecutor::new(&task_md_path, skill_store);
            if let Some(current_bridge) = bridge {
                executor.set_step_executor(Arc::new(ZeroclawStepExecutor::from_bridge(
                    current_bridge.clone(),
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
            if let Err(e) = main_task_manager.update_sub_task_status(
                main_task_id,
                &remediation_task.id,
                match &result {
                    Ok(exec_result) if exec_result.success => SubTaskStatus::Completed,
                    _ => SubTaskStatus::Failed,
                },
            ) {
                tracing::error!("Failed to update remediation subtask final status: {}", e);
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
                    persist_task_experience_artifacts(project_path, sink, &experience);
                }
            }
            *completed_count += 1;
            if let Err(e) = emit_orchestration_progress(app_handle, task_id, *completed_count, *total_tasks)
            {
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
                app_handle,
                task_id,
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

        evaluation = evaluator.evaluate(&build_acceptance_criteria(dag, subtask_file_stems));
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

    let tests_run = evaluation.as_ref().ok().map(|result| result.criteria_results.len());
    let tests_passed = evaluation.as_ref().ok().map(|result| {
        result
            .criteria_results
            .iter()
            .filter(|criteria| criteria.status == smanclaw_core::CriteriaStatus::Passed)
            .count()
    });
    if let Err(e) = emit_test_result(
        app_handle,
        task_id,
        "acceptance",
        final_passed,
        &format!("Acceptance for request: {}", input),
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
        task_id,
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
    if let Err(e) = main_task_manager.complete(main_task_id, &main_task_result) {
        tracing::error!("Failed to complete main task: {}", e);
    }
    let status = task_event_status_from_success(final_passed);
    let message = if final_passed {
        Some(format!("All {} subtasks completed and accepted", total_tasks))
    } else {
        final_error
    };
    if let Err(e) = emit_task_status_event(app_handle, task_id, status, message) {
        tracing::error!("Failed to emit final task status event: {}", e);
    }
}

fn build_acceptance_criteria(
    dag: &TaskDag,
    subtask_file_stems: &HashMap<String, String>,
) -> Vec<smanclaw_core::AcceptanceCriteria> {
    dag.tasks_in_order()
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
        .collect()
}
