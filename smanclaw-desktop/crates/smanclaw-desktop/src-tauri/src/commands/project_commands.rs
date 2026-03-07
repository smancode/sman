use smanclaw_core::{AgentsGenerator, IdentityFiles, ProjectExplorer};
use smanclaw_types::{Project, ProjectConfig};
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

    let mut pm = state.project_manager.lock().await;
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

    let mut pm = state.project_manager.lock().await;
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

    let mut pm = state.project_manager.lock().await;
    pm.update_project_config(&config)?;
    Ok(())
}
