use chrono::Utc;
use smanclaw_core::{
    Experience, ExperienceSink, IdentityFiles, LearnedItem, Skill, SkillMeta, SkillStore,
    UserExperienceExtractor,
};
use std::path::PathBuf;
use tauri::AppHandle;

use crate::error::TauriResult;

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
