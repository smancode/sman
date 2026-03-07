use smanclaw_core::{ExperienceSink, SubTaskStatus, TaskDag, TaskResultForExperience};
use std::path::Path;
use tauri::AppHandle;

use crate::commands::utility_commands::persist_task_experience_artifacts;
use crate::events::{emit_orchestration_progress, emit_subtask_completed};

pub(crate) fn step_completion_output(steps_completed: usize, steps_total: usize) -> String {
    format!("Completed {}/{} steps", steps_completed, steps_total)
}

pub(crate) fn subtask_status_from_success(success: bool) -> SubTaskStatus {
    if success {
        SubTaskStatus::Completed
    } else {
        SubTaskStatus::Failed
    }
}

pub(crate) fn apply_execution_outcome(
    dag: &mut TaskDag,
    task_id: &str,
    success: bool,
    error: Option<String>,
    steps_completed: usize,
    steps_total: usize,
) -> String {
    let output_text = step_completion_output(steps_completed, steps_total);
    if let Some(task) = dag.get_task_mut(task_id) {
        task.status = subtask_status_from_success(success);
        task.result = error.clone().or(Some(output_text.clone()));
    }
    output_text
}

pub(crate) fn persist_execution_experience(
    project_path: &Path,
    experience_sink: Option<&ExperienceSink>,
    task_id: &str,
    description: &str,
    task_md_path: &Path,
    output_text: &str,
    success: bool,
) {
    let Some(sink) = experience_sink else {
        return;
    };
    let task_md_content = std::fs::read_to_string(task_md_path).ok();
    let mut task_result = TaskResultForExperience::new(
        task_id.to_string(),
        description.to_string(),
        output_text.to_string(),
        success,
    )
    .with_files(vec![task_md_path.display().to_string()]);
    if let Some(content) = task_md_content {
        task_result = task_result.with_task_md(content);
    }
    if let Ok(experience) = sink.extract_experience(&task_result) {
        persist_task_experience_artifacts(project_path, sink, &experience);
    }
}

pub(crate) fn emit_progress_and_completion(
    app_handle: &AppHandle,
    orchestration_task_id: &str,
    subtask_id: &str,
    success: bool,
    output: &str,
    error: Option<String>,
    completed_count: usize,
    total_tasks: usize,
) {
    if let Err(progress_error) = emit_orchestration_progress(
        app_handle,
        orchestration_task_id,
        completed_count,
        total_tasks,
    ) {
        tracing::error!("Failed to emit progress event: {}", progress_error);
    }
    if let Err(emit_error) = emit_subtask_completed(
        app_handle,
        orchestration_task_id,
        subtask_id,
        success,
        output,
        error,
    ) {
        tracing::error!("Failed to emit subtask completed event: {}", emit_error);
    }
}

#[cfg(test)]
mod tests {
    use super::{step_completion_output, subtask_status_from_success};
    use smanclaw_core::SubTaskStatus;

    #[test]
    fn output_text_is_stable() {
        assert_eq!(step_completion_output(3, 7), "Completed 3/7 steps");
    }

    #[test]
    fn status_mapping_is_stable() {
        assert_eq!(subtask_status_from_success(true), SubTaskStatus::Completed);
        assert_eq!(subtask_status_from_success(false), SubTaskStatus::Failed);
    }
}
