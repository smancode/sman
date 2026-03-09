use smanclaw_core::{AgentsGenerator, IdentityFiles, ProjectExplorer};
use smanclaw_types::{Project, ProjectConfig, SkillMeta};
use std::path::{Path, PathBuf};
use tauri::State;

use super::{AppState, TauriError, TauriResult};

fn generate_fallback_agents(project_path: &Path) -> String {
    let project_name = project_path
        .file_name()
        .map(|name| name.to_string_lossy().to_string())
        .unwrap_or_else(|| "Project".to_string());

    format!(
        "# {}\n\n## Project Structure\n\n```\n(project root)\n```\n\n## Build Commands\n\n- Build: detect from project\n- Test: detect from project\n",
        project_name
    )
}

fn ensure_default_identity_files(project_path: &Path) -> TauriResult<()> {
    let identity = IdentityFiles::new(project_path);

    if !identity.soul_exists() {
        std::fs::write(
            project_path.join("SOUL.md"),
            include_str!("../../../../../SOUL.md"),
        )?;
    }

    if !identity.user_exists() {
        std::fs::write(
            project_path.join("USER.md"),
            include_str!("../../../../../USER.md"),
        )?;
    }

    if !identity.agents_exists() {
        let explorer = ProjectExplorer::new();
        let agents_content = match explorer.explore(project_path) {
            Ok(knowledge) => AgentsGenerator::generate(&knowledge),
            Err(_) => generate_fallback_agents(project_path),
        };
        identity.write_agents(&agents_content)?;
    }
    ensure_project_agent_cache(project_path)?;

    Ok(())
}

fn ensure_project_agent_cache(project_path: &Path) -> TauriResult<()> {
    let sman_dir = project_path.join(".sman");
    let cache_path = sman_dir.join("AGENT.md");
    if cache_path.exists() {
        return Ok(());
    }
    std::fs::create_dir_all(&sman_dir)?;
    let explorer = ProjectExplorer::new();
    let content = match explorer.explore(project_path) {
        Ok(knowledge) => {
            let mut lines = vec![
                format!("# AGENT Cache - {}", knowledge.name),
                String::new(),
                "## Working Rules".to_string(),
                "- 优先复用 .sman/paths 与 .sman/skills".to_string(),
                "- 执行前优先读取 CLAUDE.md 与 AGENTS.md".to_string(),
                "- .claude/skills 中已有技能可直接复用".to_string(),
                String::new(),
                "## Build Commands".to_string(),
            ];
            lines.push(format!(
                "- test: {}",
                knowledge
                    .build_config
                    .test_cmd
                    .unwrap_or_else(|| "unknown".to_string())
            ));
            lines.push(format!(
                "- lint: {}",
                knowledge
                    .build_config
                    .lint_cmd
                    .unwrap_or_else(|| "unknown".to_string())
            ));
            lines.push(format!(
                "- build: {}",
                knowledge
                    .build_config
                    .build_cmd
                    .unwrap_or_else(|| "unknown".to_string())
            ));
            let skill_names = read_project_skills(project_path)?
                .into_iter()
                .map(|skill| skill.id)
                .collect::<Vec<_>>();
            lines.push(String::new());
            lines.push("## Claude Skills".to_string());
            if skill_names.is_empty() {
                lines.push("- none".to_string());
            } else {
                for name in skill_names {
                    lines.push(format!("- {}", name));
                }
            }
            lines.join("\n") + "\n"
        }
        Err(_) => {
            "# AGENT Cache\n\n## Working Rules\n- 优先复用 .sman/paths 与 .sman/skills\n- 执行前优先读取 CLAUDE.md 与 AGENTS.md\n- .claude/skills 中已有技能可直接复用\n".to_string()
        }
    };
    std::fs::write(cache_path, content)?;
    Ok(())
}

#[tauri::command]
pub async fn get_projects(state: State<'_, AppState>) -> TauriResult<Vec<Project>> {
    let pm = state.project_manager.lock().await;
    let projects = pm.list_projects()?;
    Ok(projects)
}

#[tauri::command]
pub async fn add_project(state: State<'_, AppState>, path: String) -> TauriResult<Project> {
    if path.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project path cannot be empty".to_string(),
        ));
    }

    let project_path = PathBuf::from(&path);
    if !project_path.exists() {
        return Err(TauriError::InvalidInput(format!(
            "Project path does not exist: {}",
            path
        )));
    }

    let pm = state.project_manager.lock().await;
    let project = pm.add_project(&project_path)?;
    drop(pm);
    ensure_default_identity_files(&project_path)?;

    Ok(project)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn remove_project(state: State<'_, AppState>, project_id: String) -> TauriResult<()> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let pm = state.project_manager.lock().await;
    pm.remove_project(&project_id)?;
    Ok(())
}

#[tauri::command(rename_all = "snake_case")]
pub async fn get_project_config(
    state: State<'_, AppState>,
    project_id: String,
) -> TauriResult<ProjectConfig> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let pm = state.project_manager.lock().await;
    let config = pm.get_project_config(&project_id)?;
    Ok(config)
}

#[tauri::command]
pub async fn update_project_config(
    state: State<'_, AppState>,
    config: ProjectConfig,
) -> TauriResult<()> {
    if config.project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let pm = state.project_manager.lock().await;
    pm.update_project_config(&config)?;
    Ok(())
}

/// Read skills from the project's .claude/skills directory
fn read_project_skills(project_path: &Path) -> TauriResult<Vec<SkillMeta>> {
    let skills_dir = project_path.join(".claude").join("skills");

    if !skills_dir.exists() {
        return Ok(Vec::new());
    }

    let mut skills = Vec::new();

    // Read each subdirectory as a skill
    if let Ok(entries) = std::fs::read_dir(&skills_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                let skill_name = path
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_default();

                // Skip hidden directories
                if skill_name.starts_with('.') {
                    continue;
                }

                // Try to read tags from skill.md or SUMMARY.md
                let tags = read_skill_tags(&path);

                // Get modification time
                let updated_at = std::fs::metadata(&path)
                    .and_then(|m| m.modified())
                    .map(|t| {
                        t.duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_secs() as i64)
                            .unwrap_or(0)
                    })
                    .unwrap_or(0);

                skills.push(SkillMeta {
                    id: skill_name.clone(),
                    path: skill_name,
                    tags,
                    learned_from: "project".to_string(),
                    updated_at,
                });
            }
        }
    }

    Ok(skills)
}

/// Read tags from skill.md or SUMMARY.md files
fn read_skill_tags(skill_dir: &Path) -> Vec<String> {
    let mut tags = Vec::new();

    // Try skill.md
    let skill_md_path = skill_dir.join("skill.md");
    if let Ok(content) = std::fs::read_to_string(&skill_md_path) {
        // Extract tags from content (look for tag patterns)
        for line in content.lines() {
            let line = line.trim();
            if line.starts_with("## ") {
                break; // Stop at first section header
            }
            // Look for tag-like content
            if line.starts_with("- **") && line.contains("**:") {
                if let Some(tag) = line
                    .strip_prefix("- **")
                    .and_then(|s| s.split("**").next())
                {
                    let tag = tag.trim().to_string();
                    if !tag.is_empty() {
                        tags.push(tag);
                    }
                }
            }
        }
    }

    // Try SUMMARY.md for additional tags
    let summary_path = skill_dir.join("SUMMARY.md");
    if let Ok(content) = std::fs::read_to_string(&summary_path) {
        for line in content.lines() {
            let line = line.trim();
            if line.starts_with("- **") {
                if let Some(tag) = line.strip_prefix("- **").and_then(|s| s.split(':').next()) {
                    let tag = tag.trim().to_string();
                    if !tags.contains(&tag) && !tag.is_empty() {
                        tags.push(tag);
                    }
                }
            }
        }
    }

    tags
}

#[tauri::command(rename_all = "snake_case")]
pub async fn get_project_skills(
    state: State<'_, AppState>,
    project_id: String,
) -> TauriResult<Vec<SkillMeta>> {
    if project_id.is_empty() {
        return Err(TauriError::InvalidInput(
            "Project ID cannot be empty".to_string(),
        ));
    }

    let pm = state.project_manager.lock().await;
    let project = pm.get_project(&project_id)?;
    let project = project.ok_or_else(|| {
        TauriError::InvalidInput(format!("Project not found: {}", project_id))
    })?;
    let project_path = PathBuf::from(&project.path);

    let skills = read_project_skills(&project_path)?;
    Ok(skills)
}
