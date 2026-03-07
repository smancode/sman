use smanclaw_core::SubTaskStatus;

use crate::error::{TauriError, TauriResult};
use crate::events::SubTaskInfo;

use super::ORCHESTRATION_DAGS;

#[derive(Debug, Clone, serde::Serialize)]
pub struct OrchestratedTaskResult {
    pub task_id: String,
    pub subtask_count: usize,
    pub parallel_groups: Vec<Vec<String>>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct TaskDagResponse {
    pub task_id: String,
    pub tasks: Vec<SubTaskInfo>,
    pub parallel_groups: Vec<Vec<String>>,
    pub progress: OrchestrationProgress,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct OrchestrationProgress {
    pub completed: usize,
    pub total: usize,
    pub percent: f32,
}

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
    let progress = calculate_progress(
        tasks.iter().filter(|t| t.status == "completed").count(),
        tasks.len(),
    );

    Ok(Some(TaskDagResponse {
        task_id,
        tasks,
        parallel_groups,
        progress,
    }))
}

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
    Ok(Some(calculate_progress(completed, total)))
}

fn calculate_progress(completed: usize, total: usize) -> OrchestrationProgress {
    let percent = if total > 0 {
        completed as f32 / total as f32
    } else {
        0.0
    };
    OrchestrationProgress {
        completed,
        total,
        percent,
    }
}

#[cfg(test)]
mod tests {
    use super::calculate_progress;

    #[test]
    fn calculate_progress_handles_zero_total() {
        let progress = calculate_progress(0, 0);
        assert_eq!(progress.completed, 0);
        assert_eq!(progress.total, 0);
        assert_eq!(progress.percent, 0.0);
    }

    #[test]
    fn calculate_progress_computes_ratio() {
        let progress = calculate_progress(3, 4);
        assert_eq!(progress.completed, 3);
        assert_eq!(progress.total, 4);
        assert_eq!(progress.percent, 0.75);
    }
}
