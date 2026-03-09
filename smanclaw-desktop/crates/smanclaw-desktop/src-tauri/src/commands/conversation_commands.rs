use smanclaw_types::{Conversation, HistoryEntry};
use std::path::PathBuf;
use tauri::{AppHandle, State};

use crate::commands::chat_execution;
use crate::commands::orchestration_decompose::{extract_json_payload, is_simple_requirement};
use crate::commands::utility_commands::wrap_prompt_with_project_knowledge;
use crate::commands::history_runtime::{open_project_history_store, resolve_conversation_project};
use crate::error::{TauriError, TauriResult};
use smanclaw_ffi::ZeroclawBridge;
use crate::state::AppState;

async fn get_project_path(state: &State<'_, AppState>, project_id: &str) -> TauriResult<PathBuf> {
    let pm = state.project_manager.lock().await;
    let project = pm
        .get_project(project_id)?
        .ok_or_else(|| TauriError::ProjectNotFound(project_id.to_string()))?;
    Ok(PathBuf::from(project.path))
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageRoute {
    Direct,
    Orchestrated,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct MessageRouteDecision {
    pub route: MessageRoute,
    pub reason: String,
    pub complexity: f32,
    pub confidence: f32,
}

#[derive(Debug, serde::Deserialize)]
struct RouteDecisionPayload {
    route: String,
    reason: Option<String>,
    complexity: Option<f32>,
    confidence: Option<f32>,
}

fn build_route_prompt(content: &str) -> String {
    format!(
        "你是任务路由器。你必须只输出 JSON，不要输出解释。\
\n目标：判断用户输入应走 direct 还是 orchestrated。\
\n\
\n可选路由：\
\n1) direct：单轮或连续对话中，单点修改、简短问答、定位说明、小范围修复。\
\n2) orchestrated：需要拆分为多个可执行子任务；可能涉及跨模块改造、实现+测试+回归、可并行步骤、显式依赖管理。\
\n\
\n编排能力边界：\
\n- 擅长：多步骤实现、任务依赖编排、并行子任务调度、阶段性验证与回归。\
\n- 不适合：纯聊天问答、非常小的改单文件微调、无需任务分解的直接操作。\
\n\
\n判断规则：\
\n- 如果需求包含多个目标、多个模块、明显先后依赖、或要求完整交付验证，优先 orchestrated。\
\n- 如果需求是单一问题或单一小改动，优先 direct。\
\n- 只有在确实需要拆分执行时才选择 orchestrated。\
\n\
\n输出 JSON 结构：\
\n{{\"route\":\"direct|orchestrated\",\"reason\":\"...\",\"complexity\":0-1,\"confidence\":0-1}}\
\n\
\n用户输入：{}",
        content
    )
}

fn fallback_route_decision(content: &str) -> MessageRouteDecision {
    let normalized = content.split_whitespace().collect::<Vec<_>>().join(" ");
    let lowered = normalized.to_lowercase();
    let has_multi_goal_signal = [
        "并且",
        "同时",
        "以及",
        "然后",
        "逐个",
        "调研并计划",
        "端到端",
        "重构",
        "回归",
        "orchestr",
    ]
    .iter()
    .any(|token| lowered.contains(token));
    let has_delimiters = ['，', ',', '；', ';', '、', '\n']
        .iter()
        .any(|ch| normalized.contains(*ch));
    let complex = !is_simple_requirement(content) || has_multi_goal_signal || has_delimiters;
    if complex {
        MessageRouteDecision {
            route: MessageRoute::Orchestrated,
            reason: "需求包含多步骤或多目标，适合拆分编排执行".to_string(),
            complexity: 0.78,
            confidence: 0.62,
        }
    } else {
        MessageRouteDecision {
            route: MessageRoute::Direct,
            reason: "需求单一且范围小，直接执行成本更低".to_string(),
            complexity: 0.24,
            confidence: 0.7,
        }
    }
}

fn parse_route_decision(output: &str, content: &str) -> Option<MessageRouteDecision> {
    let payload = extract_json_payload(output)?;
    let parsed: RouteDecisionPayload = serde_json::from_str(&payload).ok()?;
    let route = match parsed.route.trim().to_lowercase().as_str() {
        "orchestrated" => MessageRoute::Orchestrated,
        "direct" => MessageRoute::Direct,
        _ => return None,
    };
    let reason = parsed
        .reason
        .unwrap_or_else(|| fallback_route_decision(content).reason);
    let complexity = parsed.complexity.unwrap_or(0.5).clamp(0.0, 1.0);
    let confidence = parsed.confidence.unwrap_or(0.5).clamp(0.0, 1.0);
    Some(MessageRouteDecision {
        route,
        reason,
        complexity,
        confidence,
    })
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
    let project_path = get_project_path(&state, &project_id)
        .await
        .map_err(|error| {
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
    let conversations = history_store
        .list_conversations(&project_id)
        .map_err(|error| {
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
    let project_path = get_project_path(&state, &project_id)
        .await
        .map_err(|error| {
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

#[tauri::command(rename_all = "snake_case")]
pub async fn decide_message_route(
    state: State<'_, AppState>,
    project_id: String,
    content: String,
) -> TauriResult<MessageRouteDecision> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }
    if content.trim().is_empty() {
        return Err(TauriError::InvalidInput(
            "Message content cannot be empty".to_string(),
        ));
    }
    let project_path = get_project_path(&state, &project_id).await?;
    let settings = state.settings_store.lock().await.load()?;
    if !settings.llm.is_configured() {
        return Ok(fallback_route_decision(&content));
    }
    let bridge = match ZeroclawBridge::from_project_with_settings(&project_path, &settings) {
        Ok(bridge) => bridge,
        Err(error) => {
            tracing::warn!(
                project_id = %project_id,
                project_path = %project_path.display(),
                error = %error,
                "Failed to initialize LLM router bridge, using fallback route decision"
            );
            return Ok(fallback_route_decision(&content));
        }
    };
    let prompt = wrap_prompt_with_project_knowledge(
        &project_path,
        &content,
        &build_route_prompt(&content),
    );
    let result = match tokio::time::timeout(
        std::time::Duration::from_secs(45),
        bridge.execute_task_async(&prompt),
    )
    .await
    {
        Ok(Ok(result)) => result,
        Ok(Err(error)) => {
            tracing::warn!(
                project_id = %project_id,
                project_path = %project_path.display(),
                error = %error,
                "Route decision LLM request failed, using fallback route decision"
            );
            return Ok(fallback_route_decision(&content));
        }
        Err(_) => {
            tracing::warn!(
                project_id = %project_id,
                project_path = %project_path.display(),
                "Route decision LLM request timed out, using fallback route decision"
            );
            return Ok(fallback_route_decision(&content));
        }
    };
    Ok(parse_route_decision(&result.output, &content)
        .unwrap_or_else(|| fallback_route_decision(&content)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_route_decision_handles_valid_payload() {
        let output = r#"{"route":"orchestrated","reason":"多模块改造","complexity":0.9,"confidence":0.8}"#;
        let decision = parse_route_decision(output, "重构并补齐回归").expect("decision");
        assert!(matches!(decision.route, MessageRoute::Orchestrated));
        assert_eq!(decision.reason, "多模块改造");
        assert!((decision.complexity - 0.9).abs() < f32::EPSILON);
        assert!((decision.confidence - 0.8).abs() < f32::EPSILON);
    }

    #[test]
    fn parse_route_decision_rejects_invalid_route() {
        let output = r#"{"route":"unknown","reason":"x"}"#;
        let decision = parse_route_decision(output, "修复按钮颜色");
        assert!(decision.is_none());
    }

    #[test]
    fn fallback_route_prefers_orchestrated_for_multi_goal_input() {
        let decision = fallback_route_decision("重构任务编排，并且补齐测试与回归");
        assert!(matches!(decision.route, MessageRoute::Orchestrated));
    }

    #[test]
    fn fallback_route_prefers_direct_for_simple_input() {
        let decision = fallback_route_decision("修复按钮颜色");
        assert!(matches!(decision.route, MessageRoute::Direct));
    }
}
