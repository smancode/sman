use smanclaw_core::{Orchestrator, SubTask};
use smanclaw_ffi::ZeroclawBridge;
use smanclaw_types::AppSettings;
use std::collections::HashSet;
use crate::commands::utility_commands::wrap_prompt_with_project_knowledge;

const SEMANTIC_DECOMPOSE_TIMEOUT_SECS: u64 = 20;

#[derive(Debug, serde::Deserialize)]
pub(crate) struct SemanticDecomposeResponse {
    pub(crate) subtasks: Vec<SemanticSubTask>,
}

#[derive(Debug, serde::Deserialize)]
pub(crate) struct SemanticSubTask {
    pub(crate) id: Option<String>,
    pub(crate) description: String,
    #[serde(default)]
    pub(crate) depends_on: Vec<String>,
    #[serde(default)]
    pub(crate) test_command: Option<String>,
}

pub(crate) async fn try_semantic_decompose_with_zeroclaw(
    project_path: &std::path::Path,
    settings: &AppSettings,
    input: &str,
) -> Option<Vec<SubTask>> {
    if !settings.llm.is_configured() {
        return None;
    }
    let bridge = ZeroclawBridge::from_project_with_settings(project_path, settings).ok()?;
    let prompt = wrap_prompt_with_project_knowledge(
        project_path,
        input,
        &build_semantic_decompose_prompt(input),
    );
    let result = match tokio::time::timeout(
        std::time::Duration::from_secs(SEMANTIC_DECOMPOSE_TIMEOUT_SECS),
        bridge.execute_task_async(&prompt),
    )
    .await
    {
        Ok(Ok(result)) => result,
        Ok(Err(error)) => {
            tracing::warn!(
                project_path = %project_path.display(),
                error = %error,
                "Semantic decompose request failed, falling back to single-context decomposition"
            );
            return None;
        }
        Err(_) => {
            tracing::warn!(
                project_path = %project_path.display(),
                timeout_secs = SEMANTIC_DECOMPOSE_TIMEOUT_SECS,
                "Semantic decompose request timed out, falling back to single-context decomposition"
            );
            return None;
        }
    };
    let parsed =
        enforce_subtask_context_independence(parse_semantic_subtasks(&result.output)?, input);
    if Orchestrator::build_dag(parsed.clone()).is_err() {
        return None;
    }
    Some(parsed)
}

pub(crate) fn build_semantic_decompose_prompt(input: &str) -> String {
    format!(
        "你是任务拆解器。把用户需求拆成可执行子任务，必要时可调用已安装 skills（如 ClawHub 安装的能力），但最终只输出 JSON，不要输出解释。\n\
输出必须是一个 JSON 对象，结构如下：\n\
{{\"subtasks\":[{{\"id\":\"task-1\",\"description\":\"...\",\"depends_on\":[],\"test_command\":\"...\"}}]}}\n\
规则：\n\
1) id 唯一，使用 kebab-case。\n\
2) depends_on 只能引用已有 id，不允许自依赖。\n\
3) 所有测试案例都必须放在 tests 目录中管理。\n\
4) 复杂需求子任务要覆盖：实现、验证、回归。\n\
5) 如果需求很小，可以只拆成 1 个子任务。\n\
6) 不要使用 markdown 代码块包裹。\n\
7) 每个子任务必须在独立上下文内可单独完成，不得依赖其他子任务的隐式上下文或口头同步。\n\
8) 如果多个步骤强关联，必须合并为同一个子任务，在该子任务内部串行执行。\n\
用户需求：{}",
        input
    )
}

pub(crate) fn parse_semantic_subtasks(output: &str) -> Option<Vec<SubTask>> {
    let payload = extract_json_payload(output)?;
    let response: SemanticDecomposeResponse = serde_json::from_str(&payload).ok()?;
    normalize_semantic_subtasks(response)
}

pub(crate) fn extract_json_payload(output: &str) -> Option<String> {
    let trimmed = output.trim();
    if serde_json::from_str::<serde_json::Value>(trimmed).is_ok() {
        return Some(trimmed.to_string());
    }
    if let Some(start) = trimmed.find("```json") {
        let rest = &trimmed[start + 7..];
        if let Some(end) = rest.find("```") {
            let candidate = rest[..end].trim();
            if serde_json::from_str::<serde_json::Value>(candidate).is_ok() {
                return Some(candidate.to_string());
            }
        }
    }
    if let Some(start) = trimmed.find("```") {
        let rest = &trimmed[start + 3..];
        if let Some(end) = rest.find("```") {
            let candidate = rest[..end].trim();
            if serde_json::from_str::<serde_json::Value>(candidate).is_ok() {
                return Some(candidate.to_string());
            }
        }
    }
    let mut depth = 0usize;
    let mut start_idx = None;
    for (idx, ch) in trimmed.char_indices() {
        if ch == '{' {
            if start_idx.is_none() {
                start_idx = Some(idx);
            }
            depth += 1;
        } else if ch == '}' {
            if depth == 0 {
                continue;
            }
            depth -= 1;
            if depth == 0 {
                if let Some(start) = start_idx {
                    let candidate = trimmed[start..=idx].trim();
                    if serde_json::from_str::<serde_json::Value>(candidate).is_ok() {
                        return Some(candidate.to_string());
                    }
                }
                start_idx = None;
            }
        }
    }
    None
}

pub(crate) fn sanitize_task_id(raw: &str, fallback_index: usize) -> String {
    let mut cleaned = raw
        .trim()
        .to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .collect::<String>();
    cleaned = cleaned.replace('_', "-");
    if cleaned.is_empty() || cleaned.chars().next().is_some_and(|c| c.is_ascii_digit()) {
        return format!("task-{}", fallback_index + 1);
    }
    cleaned
}

pub(crate) fn normalize_semantic_subtasks(
    response: SemanticDecomposeResponse,
) -> Option<Vec<SubTask>> {
    if response.subtasks.is_empty() {
        return None;
    }
    let mut ids = Vec::with_capacity(response.subtasks.len());
    let mut seen = HashSet::new();
    for (idx, task) in response.subtasks.iter().enumerate() {
        let mut id = sanitize_task_id(task.id.as_deref().unwrap_or_default(), idx);
        if seen.contains(&id) {
            let mut suffix = 2usize;
            loop {
                let candidate = format!("{id}-{suffix}");
                if !seen.contains(&candidate) {
                    id = candidate;
                    break;
                }
                suffix += 1;
            }
        }
        seen.insert(id.clone());
        ids.push(id);
    }
    let id_set = ids.iter().cloned().collect::<HashSet<_>>();
    let mut subtasks = Vec::new();
    for (idx, task) in response.subtasks.into_iter().enumerate() {
        let description = task.description.trim().to_string();
        if description.is_empty() {
            continue;
        }
        let id = ids
            .get(idx)
            .cloned()
            .unwrap_or_else(|| format!("task-{}", idx + 1));
        let mut subtask = SubTask::new(id.clone(), description);
        for dep in task.depends_on {
            let dep_id = sanitize_task_id(&dep, idx);
            if dep_id != id && id_set.contains(&dep_id) {
                subtask = subtask.depends_on(dep_id);
            }
        }
        if let Some(cmd) = task.test_command.map(|s| s.trim().to_string()) {
            if !cmd.is_empty() {
                subtask = subtask.with_test_command(cmd);
            }
        }
        subtasks.push(subtask);
    }
    if subtasks.is_empty() {
        None
    } else {
        Some(subtasks)
    }
}

pub(crate) fn enforce_subtask_context_independence(
    subtasks: Vec<SubTask>,
    _input: &str,
) -> Vec<SubTask> {
    subtasks
}

pub(crate) fn fallback_decompose_subtasks(input: &str) -> Vec<SubTask> {
    let summary = normalize_requirement_summary(input);
    vec![SubTask::new(
        "task-context-serial",
        format!(
            "在单一子Claw上下文内完成需求分析、实现、验证与回归，避免跨子任务上下文依赖：{}",
            summary
        ),
    )]
}

pub(crate) fn normalize_requirement_summary(input: &str) -> String {
    let compact = input
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .replace('\n', " ");
    if compact.chars().count() <= 72 {
        return compact;
    }
    let shortened = compact.chars().take(72).collect::<String>();
    format!("{}...", shortened)
}
