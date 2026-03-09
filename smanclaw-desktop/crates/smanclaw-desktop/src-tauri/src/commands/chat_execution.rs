use chrono::Utc;
use smanclaw_ffi::ZeroclawBridge;
use smanclaw_core::skill_store::find_claude_skill_run_script;
use smanclaw_types::{HistoryEntry, ProgressEvent, Role};
use std::sync::Arc;
use tauri::{AppHandle, State};

use crate::commands::utility_commands::{
    persist_user_input_experience, wrap_prompt_with_project_knowledge,
};
use crate::events::emit_progress_event;

use super::{
    open_project_history_store, resolve_conversation_project, AppState, TauriError, TauriResult,
};

const CHAT_EXECUTION_HEARTBEAT_SECS: u64 = 300;

pub async fn send_message(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    conversation_id: String,
    content: String,
) -> TauriResult<HistoryEntry> {
    if conversation_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Conversation ID cannot be empty".to_string(),
        ));
    }
    if content.is_empty() {
        return Err(TauriError::InvalidInput(
            "Message content cannot be empty".to_string(),
        ));
    }

    let (_conversation, project_path) = resolve_conversation_project(&state, &conversation_id)
        .await?
        .ok_or_else(|| TauriError::ConversationNotFound(conversation_id.clone()))?;
    persist_user_input_experience(&project_path, &content);
    let history_store = open_project_history_store(&state.config_dir, &project_path)?;

    let user_entry = HistoryEntry {
        id: uuid::Uuid::new_v4().to_string(),
        conversation_id: conversation_id.clone(),
        role: Role::User,
        content: content.clone(),
        timestamp: Utc::now(),
    };
    history_store.save_entry(&user_entry)?;

    // Check if this is a skill command (starts with /)
    let content_trimmed = content.trim();
    if content_trimmed.starts_with('/') {
        // Extract skill name (e.g., /war fetch -> war)
        let skill_input = content_trimmed.trim_start_matches('/');
        let skill_name = skill_input.split_whitespace().next().unwrap_or(&skill_input);

        // Try to find and execute the skill
        if let Some(skill_script) = smanclaw_core::skill_store::find_claude_skill_run_script(&project_path, skill_name) {
            tracing::info!("Executing skill: {} with script: {:?}", skill_name, skill_script);

            // Emit progress event: skill started
            let start_event = ProgressEvent::Progress {
                message: format!("正在启动技能: {}", skill_name),
                percent: 0.0,
            };
            let _ = emit_progress_event(&app_handle, &start_event);

            // Parse skill arguments (e.g., /war fetch -> args: ["fetch"])
            let skill_args: Vec<&str> = skill_input.split_whitespace().skip(1).collect();

            // Build command with arguments - add verbose mode for detailed output
            let mut cmd = std::process::Command::new("python");
            cmd.arg("-u");  // Unbuffered output
            cmd.arg(skill_script);
            for arg in &skill_args {
                cmd.arg(arg);
            }
            // cmd.arg("--verbose");  // Disabled: run.py doesn't support this
            cmd.current_dir(&project_path);

            // Capture both stdout and stderr
            let output = cmd.output();

            let skill_result = match output {
                Ok(out) => {
                    // Combine stdout and stderr for full output
                    let mut full_output = String::new();
                    if !out.stderr.is_empty() {
                        full_output.push_str(&String::from_utf8_lossy(&out.stderr));
                    }
                    if !out.stdout.is_empty() {
                        if !full_output.is_empty() {
                            full_output.push('\n');
                        }
                        full_output.push_str(&String::from_utf8_lossy(&out.stdout));
                    }

                    // Emit progress events for each line of output
                    let mut line_count = 0;
                    let total_lines = full_output.lines().count();
                    for line in full_output.lines() {
                        // Emit all non-empty lines as progress events
                        let trimmed = line.trim();
                        if !trimmed.is_empty() {
                            line_count += 1;
                            let percent = if total_lines > 0 {
                                (line_count as f64 / total_lines as f64 * 0.8) as f32
                            } else {
                                0.5
                            };
                            let progress_event = ProgressEvent::Progress {
                                message: trimmed.to_string(),
                                percent,
                            };
                            let _ = emit_progress_event(&app_handle, &progress_event);
                        }
                    }
                    if out.status.success() {
                        if full_output.is_empty() {
                            "技能执行完成".to_string()
                        } else {
                            full_output
                        }
                    } else {
                        format!("Skill execution failed: {}", full_output)
                    }
                }
                Err(e) => format!("Failed to execute skill: {}", e),
            };

            // Emit completion event
            let complete_event = ProgressEvent::Progress {
                message: "技能执行完成".to_string(),
                percent: 1.0,
            };
            let _ = emit_progress_event(&app_handle, &complete_event);

            // Save assistant response
            let assistant_entry = HistoryEntry {
                id: uuid::Uuid::new_v4().to_string(),
                conversation_id: conversation_id.clone(),
                role: Role::Assistant,
                content: skill_result.clone(),
                timestamp: Utc::now(),
            };
            history_store.save_entry(&assistant_entry)?;

            return Ok(assistant_entry);
        }
    }

    let settings = state.settings_store.lock().await.load()?;
    let bridge = Arc::new(ZeroclawBridge::from_project_with_settings(
        &project_path,
        &settings,
    )?);
    let (tx, mut rx) = tokio::sync::mpsc::channel(64);

    let app_handle_clone = app_handle.clone();
    let project_path_for_save = project_path.clone();
    let config_dir_for_save = state.config_dir.clone();
    let conv_id = conversation_id.clone();
    let content_clone = wrap_prompt_with_project_knowledge(&project_path, &content, &content);

    tokio::spawn(async move {
        let mut execution_future =
            Box::pin(bridge.execute_task_stream(&conv_id, &content_clone, tx.clone()));
        let started_at = std::time::Instant::now();
        let heartbeat_interval = std::time::Duration::from_secs(CHAT_EXECUTION_HEARTBEAT_SECS);
        let mut next_heartbeat = tokio::time::Instant::now() + heartbeat_interval;
        let truncate = |text: &str, max_len: usize| -> String {
            if text.chars().count() <= max_len {
                text.to_string()
            } else {
                let mut out = String::new();
                for ch in text.chars().take(max_len) {
                    out.push(ch);
                }
                out.push('…');
                out
            }
        };
        let mut tool_calls = 0usize;
        let mut file_reads = 0usize;
        let mut file_writes = 0usize;
        let mut command_runs = 0usize;
        let mut last_stage = "已接收需求，正在规划执行路径".to_string();
        let mut last_progress_message = String::new();
        let mut reported_tool_calls = 0usize;
        let mut reported_file_reads = 0usize;
        let mut reported_file_writes = 0usize;
        let mut reported_command_runs = 0usize;
        let mut reported_stage = String::new();
        let mut reported_progress_message = String::new();
        let execution_result = loop {
            tokio::select! {
                result = &mut execution_future => {
                    break result;
                }
                incoming = rx.recv() => {
                    if let Some(event) = incoming {
                        match &event {
                            ProgressEvent::TaskStarted { .. } => {
                                last_stage = "已启动执行，正在梳理任务步骤".to_string();
                            }
                            ProgressEvent::ToolCall { tool, .. } => {
                                tool_calls += 1;
                                let tool_name = truncate(tool, 40);
                                let stage = if tool_name.contains("search")
                                    || tool_name.contains("read")
                                    || tool_name.contains("grep")
                                {
                                    "正在分析现有实现"
                                } else if tool_name.contains("edit")
                                    || tool_name.contains("write")
                                    || tool_name.contains("patch")
                                {
                                    "正在落地代码改动"
                                } else if tool_name.contains("test")
                                    || tool_name.contains("lint")
                                    || tool_name.contains("check")
                                    || tool_name.contains("build")
                                {
                                    "正在验证改动质量"
                                } else {
                                    "正在调用能力推进任务"
                                };
                                last_stage = format!("{}（{}）", stage, tool_name);
                            }
                            ProgressEvent::FileRead { path } => {
                                file_reads += 1;
                                last_stage = format!("正在分析现有实现（{}）", truncate(path, 60));
                            }
                            ProgressEvent::FileWritten { path, .. } => {
                                file_writes += 1;
                                last_stage = format!("正在落地代码改动（{}）", truncate(path, 60));
                            }
                            ProgressEvent::CommandRun { command } => {
                                command_runs += 1;
                                let command_text = truncate(command, 60);
                                let stage = if command_text.contains("test")
                                    || command_text.contains("lint")
                                    || command_text.contains("check")
                                {
                                    "正在执行质量验证"
                                } else if command_text.contains("build") {
                                    "正在构建并确认可交付性"
                                } else {
                                    "正在执行自动化步骤"
                                };
                                last_stage = format!("{}（{}）", stage, command_text);
                            }
                            ProgressEvent::Progress { message, .. } => {
                                last_stage = format!("正在推进：{}", truncate(message, 80));
                                last_progress_message = last_stage.clone();
                            }
                            ProgressEvent::TaskCompleted { .. } => {
                                last_stage = "执行完成，正在整理交付结果".to_string();
                            }
                            ProgressEvent::TaskFailed { error } => {
                                last_stage = format!(
                                    "执行遇到问题，正在收敛错误信息（{}）",
                                    truncate(error, 80)
                                );
                            }
                        }
                        if let Err(e) = emit_progress_event(&app_handle_clone, &event) {
                            tracing::error!("Failed to emit progress event: {}", e);
                        }
                    }
                }
                _ = tokio::time::sleep_until(next_heartbeat) => {
                    let elapsed = started_at.elapsed().as_secs();
                    let elapsed_minutes = elapsed / 60;
                    let delta_tool_calls = tool_calls.saturating_sub(reported_tool_calls);
                    let delta_file_reads = file_reads.saturating_sub(reported_file_reads);
                    let delta_file_writes = file_writes.saturating_sub(reported_file_writes);
                    let delta_command_runs = command_runs.saturating_sub(reported_command_runs);
                    let stage_changed = last_stage != reported_stage;
                    let progress_changed = !last_progress_message.is_empty()
                        && last_progress_message != reported_progress_message;
                    let mut changed = Vec::new();
                    if delta_tool_calls > 0 {
                        changed.push(format!("工具调用 {} 次", delta_tool_calls));
                    }
                    if delta_file_reads > 0 {
                        changed.push(format!("读取文件 {} 个", delta_file_reads));
                    }
                    if delta_file_writes > 0 {
                        changed.push(format!("写入文件 {} 个", delta_file_writes));
                    }
                    if delta_command_runs > 0 {
                        changed.push(format!("执行命令 {} 次", delta_command_runs));
                    }
                    let heartbeat_message = if !changed.is_empty() {
                        format!(
                            "过去 5 分钟新增：{}。当前阶段：{}。我会继续推进并及时同步给你。",
                            changed.join("，"),
                            last_stage
                        )
                    } else if stage_changed || progress_changed {
                        format!(
                            "进度更新：{}。当前正在持续推进，预计有明确产出后会第一时间反馈。",
                            last_stage
                        )
                    } else {
                        format!(
                            "任务仍在稳步执行（已 {} 分钟），暂未产生新的可见变更。感谢你的耐心等待，我会在下一次有变化时立刻通知你。",
                            elapsed_minutes
                        )
                    };
                    let heartbeat_event = ProgressEvent::Progress {
                        message: heartbeat_message,
                        percent: 0.35,
                    };
                    if let Err(e) = emit_progress_event(&app_handle_clone, &heartbeat_event) {
                        tracing::error!("Failed to emit heartbeat progress event: {}", e);
                    }
                    reported_tool_calls = tool_calls;
                    reported_file_reads = file_reads;
                    reported_file_writes = file_writes;
                    reported_command_runs = command_runs;
                    reported_stage = last_stage.clone();
                    reported_progress_message = last_progress_message.clone();
                    next_heartbeat += heartbeat_interval;
                    while next_heartbeat <= tokio::time::Instant::now() {
                        next_heartbeat += heartbeat_interval;
                    }
                }
            }
        };
        while let Ok(event) = rx.try_recv() {
            if let Err(e) = emit_progress_event(&app_handle_clone, &event) {
                tracing::error!("Failed to emit progress event: {}", e);
            }
        }
        match execution_result {
            Ok(result) => {
                let assistant_entry = HistoryEntry {
                    id: uuid::Uuid::new_v4().to_string(),
                    conversation_id: conv_id.clone(),
                    role: Role::Assistant,
                    content: result.output.clone(),
                    timestamp: Utc::now(),
                };

                let save_store = match open_project_history_store(
                    &config_dir_for_save,
                    &project_path_for_save,
                ) {
                    Ok(store) => store,
                    Err(e) => {
                        tracing::error!("Failed to open project history store: {}", e);
                        return;
                    }
                };
                if let Err(e) = save_store.save_entry(&assistant_entry) {
                    tracing::error!("Failed to save assistant message: {}", e);
                }
            }
            Err(e) => {
                tracing::error!("Task execution failed: {}", e);
                let assistant_entry = HistoryEntry {
                    id: uuid::Uuid::new_v4().to_string(),
                    conversation_id: conv_id.clone(),
                    role: Role::Assistant,
                    content: format!("Error: {}", e),
                    timestamp: Utc::now(),
                };
                match open_project_history_store(&config_dir_for_save, &project_path_for_save) {
                    Ok(store) => {
                        if let Err(save_error) = store.save_entry(&assistant_entry) {
                            tracing::error!(
                                "Failed to save assistant error message: {}",
                                save_error
                            );
                        }
                    }
                    Err(open_error) => {
                        tracing::error!("Failed to open project history store: {}", open_error);
                    }
                }
            }
        }
    });

    Ok(user_entry)
}
