use chrono::Utc;
use smanclaw_core::{
    Experience, ExperienceSink, IdentityFiles, LearnedItem, Skill, SkillMeta, SkillStore,
    UserExperienceExtractor,
};
use std::path::{Path, PathBuf};
use tauri::AppHandle;

use crate::error::{TauriError, TauriResult};
use crate::state::AppState;

#[tauri::command]
pub fn get_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[tauri::command]
pub fn path_exists(path: String) -> bool {
    PathBuf::from(&path).exists()
}

#[tauri::command]
pub async fn select_folder(app_handle: AppHandle) -> TauriResult<Option<String>> {
    use tauri_plugin_dialog::DialogExt;

    let folder = app_handle.dialog().file().blocking_pick_folder();

    Ok(folder.map(|p| p.to_string()))
}

pub(crate) fn persist_user_memory(
    project_path: &std::path::Path,
    raw_input: &str,
    extracted_content: &str,
    tags: &[String],
) {
    let skill_store = match SkillStore::new(project_path) {
        Ok(store) => store,
        Err(e) => {
            tracing::error!("Failed to create SkillStore for user memory: {}", e);
            return;
        }
    };
    let sink = ExperienceSink::new(skill_store);
    let mut experience = Experience::new("user-input");
    let mut learned = LearnedItem::new("user-preference", extracted_content.to_string());
    for tag in tags {
        learned = learned.with_tag(tag.clone());
    }
    experience.add_learned(learned);
    experience.add_pattern(raw_input.to_string());
    if let Err(e) = sink.update_memory(project_path, &experience) {
        tracing::error!("Failed to persist user memory: {}", e);
    }
}

fn is_quick_agent_note_candidate(input: &str) -> bool {
    let text = input.trim();
    if text.is_empty() {
        return false;
    }
    let lowered = text.to_lowercase();
    let is_question = lowered.contains("是哪个")
        || lowered.contains("在哪")
        || lowered.contains("在哪里")
        || lowered.contains("怎么找")
        || lowered.contains("哪个文件")
        || lowered.contains("哪个接口")
        || lowered.contains("接口是");
    let has_locator = lowered.contains("接口")
        || lowered.contains("路径")
        || lowered.contains(".xml")
        || lowered.contains(".java")
        || lowered.contains(".sql")
        || lowered.contains(".yaml")
        || lowered.contains(".yml")
        || lowered.contains(".json");
    let is_short = text.chars().count() <= 120;
    let is_complex = lowered.contains("完整流程")
        || lowered.contains("跨模块")
        || lowered.contains("端到端")
        || lowered.contains("会计核算")
        || lowered.contains("业务流程");
    is_short && is_question && has_locator && !is_complex
}

fn persist_agent_quick_note(project_path: &std::path::Path, input: &str) {
    let identity = IdentityFiles::new(project_path);
    let mut content = identity.read_agents().unwrap_or_default();
    let note = input.trim().replace('\n', " ");
    if note.is_empty() {
        return;
    }
    let note_line = format!("- {note}\n");
    if content.contains(&note_line) {
        return;
    }
    let section_title = "## 业务快速索引";
    if !content.contains(section_title) {
        if !content.is_empty() && !content.ends_with('\n') {
            content.push('\n');
        }
        content.push('\n');
        content.push_str(section_title);
        content.push_str("\n\n");
    } else if !content.ends_with('\n') {
        content.push('\n');
    }
    content.push_str(&note_line);
    if let Err(e) = identity.write_agents(&content) {
        tracing::error!("Failed to persist AGENTS quick note: {}", e);
    }
}

pub(crate) fn persist_user_input_experience(project_path: &std::path::Path, input: &str) {
    if is_quick_agent_note_candidate(input) {
        persist_agent_quick_note(project_path, input);
        let tags = vec!["quick-note".to_string(), "agents".to_string()];
        persist_user_memory(project_path, input, input, &tags);
        return;
    }
    if let Ok(skill_store) = SkillStore::new(project_path) {
        let extractor = match SkillStore::for_paths(project_path) {
            Ok(path_store) => UserExperienceExtractor::new(skill_store).with_path_store(path_store),
            Err(_) => UserExperienceExtractor::new(skill_store),
        };
        if let Some(experience) = extractor.analyze(input) {
            if let Err(e) = extractor.store_as_skill(&experience) {
                tracing::error!("Failed to persist user experience: {}", e);
            }
            persist_user_memory(project_path, input, &experience.content, &experience.tags);
        }
    }
}

pub(crate) fn should_generate_path_from_task_experience(experience: &Experience) -> bool {
    if !experience.reusable_patterns.is_empty() {
        return true;
    }
    experience.learned.iter().any(|item| {
        item.content.contains("必须") || item.content.contains("默认") || item.tags.len() >= 2
    })
}

pub(crate) fn persist_path_from_task_experience(
    project_path: &std::path::Path,
    experience: &Experience,
) {
    if !should_generate_path_from_task_experience(experience) {
        return;
    }
    let path_store = match SkillStore::for_paths(project_path) {
        Ok(store) => store,
        Err(e) => {
            tracing::error!("Failed to create path store from task experience: {}", e);
            return;
        }
    };
    let timestamp = Utc::now().timestamp_millis();
    let source = sanitize_path_fragment(&experience.source_task);
    let id = format!("path-task-{}-{}", source, timestamp);
    let path = format!("auto/operation-{}-{}.md", source, timestamp);

    let learned_lines = experience
        .learned
        .iter()
        .map(|item| format!("- [{}] {}", item.category, item.content))
        .collect::<Vec<_>>()
        .join("\n");
    let pattern_lines = experience
        .reusable_patterns
        .iter()
        .map(|pattern| format!("- {}", pattern))
        .collect::<Vec<_>>()
        .join("\n");

    let content = format!(
        "# Path - {}\n\n## 来源任务\n\n{}\n\n## 可复用步骤\n\n{}\n\n## 关键模式\n\n{}\n",
        experience.source_task,
        experience.source_task,
        if learned_lines.is_empty() {
            "- 无".to_string()
        } else {
            learned_lines
        },
        if pattern_lines.is_empty() {
            "- 无".to_string()
        } else {
            pattern_lines
        },
    );
    let mut tags = vec![
        "path".to_string(),
        "auto-generated".to_string(),
        "task-experience".to_string(),
    ];
    tags.extend(
        experience
            .learned
            .iter()
            .flat_map(|item| item.tags.iter().cloned()),
    );
    tags.sort();
    tags.dedup();

    let skill = Skill {
        meta: SkillMeta {
            id,
            path,
            tags,
            learned_from: experience.source_task.clone(),
            updated_at: Utc::now().timestamp(),
        },
        content,
    };
    if let Err(e) = path_store.create(&skill) {
        tracing::error!("Failed to persist path from task experience: {}", e);
    }
}

pub(crate) fn sanitize_path_fragment(raw: &str) -> String {
    let cleaned = raw
        .to_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect::<String>();
    let compact = cleaned
        .split('-')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    if compact.is_empty() {
        "task".to_string()
    } else {
        compact
    }
}

pub(crate) fn persist_task_experience_artifacts(
    project_path: &std::path::Path,
    sink: &ExperienceSink,
    experience: &Experience,
) {
    if sink.should_update_skill(experience) {
        if let Err(e) = sink.update_skill(experience) {
            tracing::error!("Failed to update skill from experience: {}", e);
        }
    }
    if experience.is_valuable() {
        if let Err(e) = sink.update_memory(project_path, experience) {
            tracing::error!("Failed to update MEMORY from experience: {}", e);
        }
        persist_path_from_task_experience(project_path, experience);
    }
}

fn truncate_for_prompt(input: &str, max_chars: usize) -> String {
    let mut output = String::new();
    for ch in input.chars().take(max_chars) {
        output.push(ch);
    }
    if input.chars().count() > max_chars {
        output.push('…');
    }
    output
}

fn compact_prompt_text(input: &str) -> String {
    input
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

fn relevance_score(input_lower: &str, meta: &SkillMeta) -> i64 {
    let mut score = 0i64;
    if input_lower.contains(&meta.id.to_lowercase()) {
        score += 6;
    }
    if input_lower.contains(&meta.learned_from.to_lowercase()) {
        score += 4;
    }
    for tag in &meta.tags {
        if input_lower.contains(&tag.to_lowercase()) {
            score += 3;
        }
    }
    score
}

fn load_store_prompt_items(
    store: &SkillStore,
    section_title: &str,
    input: &str,
    limit: usize,
) -> Vec<String> {
    let mut metas = match store.list() {
        Ok(items) => items,
        Err(_) => return Vec::new(),
    };
    let input_lower = input.to_lowercase();
    metas.sort_by(|a, b| {
        let score_b = relevance_score(&input_lower, b);
        let score_a = relevance_score(&input_lower, a);
        score_b
            .cmp(&score_a)
            .then_with(|| b.updated_at.cmp(&a.updated_at))
    });
    metas.truncate(limit);
    let mut items = Vec::new();
    for meta in metas {
        if let Ok(Some(skill)) = store.get(&meta.id) {
            let content = truncate_for_prompt(&compact_prompt_text(&skill.content), 280);
            let tags = if meta.tags.is_empty() {
                "none".to_string()
            } else {
                meta.tags.join(",")
            };
            items.push(format!(
                "- [{}] from={} tags={} content={}",
                meta.id,
                truncate_for_prompt(&meta.learned_from, 80),
                truncate_for_prompt(&tags, 80),
                content
            ));
        }
    }
    if items.is_empty() {
        Vec::new()
    } else {
        let mut section = Vec::new();
        section.push(format!("## {}", section_title));
        section.extend(items);
        section
    }
}

fn load_file_prompt_section(
    project_path: &Path,
    relative_path: &str,
    section_title: &str,
    max_chars: usize,
) -> Option<Vec<String>> {
    let file_path = project_path.join(relative_path);
    let content = std::fs::read_to_string(file_path).ok()?;
    let compact = compact_prompt_text(&content);
    if compact.is_empty() {
        return None;
    }
    Some(vec![
        format!("## {}", section_title),
        format!("- path={} content={}", relative_path, truncate_for_prompt(&compact, max_chars)),
    ])
}

fn load_claude_skill_prompt_items(project_path: &Path, input: &str, limit: usize) -> Vec<String> {
    let skills_dir = project_path.join(".claude").join("skills");
    let entries = match std::fs::read_dir(skills_dir) {
        Ok(entries) => entries,
        Err(_) => return Vec::new(),
    };
    let input_lower = input.to_lowercase();
    let mut scored = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let Some(name) = path.file_name().map(|n| n.to_string_lossy().to_string()) else {
            continue;
        };
        if name.starts_with('.') {
            continue;
        }
        let mut summary = String::new();
        for candidate in ["skill.md", "SUMMARY.md"] {
            let text = std::fs::read_to_string(path.join(candidate)).unwrap_or_default();
            if !text.trim().is_empty() {
                summary = text;
                break;
            }
        }
        let summary_compact = compact_prompt_text(&summary);
        let mut score = if input_lower.contains(&name.to_lowercase()) { 8 } else { 0 };
        if !summary_compact.is_empty() {
            let summary_lower = summary_compact.to_lowercase();
            for token in input_lower.split_whitespace() {
                if token.len() >= 2 && summary_lower.contains(token) {
                    score += 1;
                }
            }
        }
        scored.push((score, name, summary_compact));
    }
    scored.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
    scored.truncate(limit);
    if scored.is_empty() {
        return Vec::new();
    }
    let mut section = vec!["## Claude Skills".to_string()];
    for (_, name, summary) in scored {
        let summary_text = if summary.is_empty() {
            "no-summary".to_string()
        } else {
            truncate_for_prompt(&summary, 220)
        };
        section.push(format!(
            "- [{}] path=.claude/skills/{}/ skill_summary={}",
            name, name, summary_text
        ));
    }
    section
}

pub(crate) fn build_project_knowledge_block(project_path: &std::path::Path, input: &str) -> String {
    let mut sections = Vec::new();
    if let Ok(skill_store) = SkillStore::new(project_path) {
        sections.extend(load_store_prompt_items(
            &skill_store,
            "Project Skills",
            input,
            4,
        ));
    }
    if let Ok(path_store) = SkillStore::for_paths(project_path) {
        sections.extend(load_store_prompt_items(
            &path_store,
            "Project Paths",
            input,
            4,
        ));
    }
    if let Some(agent_cache) =
        load_file_prompt_section(project_path, ".sman/AGENT.md", "Project Agent Cache", 520)
    {
        sections.extend(agent_cache);
    }
    if let Some(claude_rules) = load_file_prompt_section(project_path, "CLAUDE.md", "Project CLAUDE Rules", 420) {
        sections.extend(claude_rules);
    }
    sections.extend(load_claude_skill_prompt_items(project_path, input, 5));
    if sections.is_empty() {
        String::new()
    } else {
        sections.join("\n")
    }
}

pub(crate) fn wrap_prompt_with_project_knowledge(
    project_path: &std::path::Path,
    input: &str,
    prompt_body: &str,
) -> String {
    let knowledge = build_project_knowledge_block(project_path, input);
    if knowledge.is_empty() {
        prompt_body.to_string()
    } else {
        format!(
            "{}\n\n你必须优先复用以上 Project Skills、Project Paths、Claude Skills、Project Agent Cache 与 Project CLAUDE Rules 中的约束与可复用做法。\n\n{}",
            knowledge, prompt_body
        )
    }
}

use crate::events::emit_chat_message;
use tauri::State;

/// Send a chat message to the frontend chat window
///
/// This command allows sending a message to the chat interface from external sources
/// (like skills or background processes)
#[tauri::command]
pub async fn send_chat_message(
    app_handle: tauri::AppHandle,
    state: State<'_, AppState>,
    project_id: String,
    content: String,
    role: String,
) -> TauriResult<()> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }
    if content.is_empty() {
        return Err(TauriError::InvalidInput(
            "Message content cannot be empty".to_string(),
        ));
    }

    // Default to assistant if role is not specified
    let role = if role.is_empty() {
        "assistant".to_string()
    } else {
        role
    };

    emit_chat_message(&app_handle, &project_id, &content, &role)
        .map_err(|e| TauriError::Internal(e.to_string()))?;

    Ok(())
}
