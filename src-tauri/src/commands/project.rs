// src-tauri/src/commands/project.rs
//! Project management commands

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::OnceLock;

/// Project metadata
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Project {
    pub id: String,
    pub name: String,
    pub path: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub lastOpenedAt: Option<String>,
}

/// Skill metadata
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillMeta {
    pub name: String,
    pub description: String,
    pub filePath: String,
}

/// Projects storage
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct ProjectsStorage {
    projects: Vec<Project>,
}

/// Get projects file path (~/.smanlocal/projects.json)
fn projects_path() -> &'static PathBuf {
    static PATH: OnceLock<PathBuf> = OnceLock::new();
    PATH.get_or_init(|| {
        let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("/"));
        home.join(".smanlocal").join("projects.json")
    })
}

/// Ensure .smanlocal directory exists
fn ensure_smanlocal_dir() -> Result<(), String> {
    let home = dirs::home_dir().ok_or("Cannot find home directory")?;
    let dir = home.join(".smanlocal");
    if !dir.exists() {
        fs::create_dir_all(&dir).map_err(|e| format!("Failed to create .smanlocal: {}", e))?;
    }
    Ok(())
}

/// Load projects from storage
fn load_projects() -> Result<ProjectsStorage, String> {
    let path = projects_path();

    if !path.exists() {
        return Ok(ProjectsStorage::default());
    }

    let content = fs::read_to_string(path)
        .map_err(|e| format!("Failed to read projects: {}", e))?;

    let storage: ProjectsStorage = serde_json::from_str(&content)
        .unwrap_or_else(|_| ProjectsStorage::default());

    Ok(storage)
}

/// Save projects to storage
fn save_projects(storage: &ProjectsStorage) -> Result<(), String> {
    ensure_smanlocal_dir()?;

    let path = projects_path();
    let content = serde_json::to_string_pretty(storage)
        .map_err(|e| format!("Failed to serialize projects: {}", e))?;

    fs::write(path, &content)
        .map_err(|e| format!("Failed to write projects: {}", e))?;

    Ok(())
}

/// Generate a unique ID
fn generate_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

#[tauri::command]
pub fn get_projects() -> Result<Vec<Project>, String> {
    let storage = load_projects()?;
    Ok(storage.projects)
}

#[tauri::command]
pub fn add_project(path: String) -> Result<Project, String> {
    let path_buf = PathBuf::from(&path);

    // Validate path exists
    if !path_buf.exists() {
        return Err(format!("Path does not exist: {}", path));
    }

    // Get project name from directory name
    let name = path_buf
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "Unknown Project".to_string());

    let project = Project {
        id: generate_id(),
        name,
        path: path.clone(),
        description: None,
        lastOpenedAt: Some(chrono::Utc::now().to_rfc3339()),
    };

    let mut storage = load_projects()?;

    // Check if project already exists
    if storage.projects.iter().any(|p| p.path == path) {
        return Err("Project already exists".to_string());
    }

    storage.projects.push(project.clone());
    save_projects(&storage)?;

    Ok(project)
}

#[tauri::command]
pub fn remove_project(project_id: String) -> Result<(), String> {
    let mut storage = load_projects()?;

    let initial_len = storage.projects.len();
    storage.projects.retain(|p| p.id != project_id);

    if storage.projects.len() == initial_len {
        return Err("Project not found".to_string());
    }

    save_projects(&storage)?;
    Ok(())
}

#[tauri::command]
pub fn get_project_skills(project_id: String) -> Result<Vec<SkillMeta>, String> {
    let storage = load_projects()?;

    let project = storage
        .projects
        .iter()
        .find(|p| p.id == project_id)
        .ok_or("Project not found")?;

    let skills_dir = PathBuf::from(&project.path).join(".sman").join("skills");

    if !skills_dir.exists() {
        return Ok(Vec::new());
    }

    let mut skills = Vec::new();

    if let Ok(entries) = fs::read_dir(&skills_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().map(|e| e == "md").unwrap_or(false) {
                let name = path
                    .file_stem()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_default();

                let content = fs::read_to_string(&path).unwrap_or_default();

                // Extract description from first paragraph or heading
                let description = content
                    .lines()
                    .skip_while(|l| l.starts_with('#'))
                    .find(|l| !l.trim().is_empty())
                    .map(|l| l.trim().to_string())
                    .unwrap_or_else(|| format!("Skill: {}", name));

                skills.push(SkillMeta {
                    name,
                    description,
                    filePath: path.to_string_lossy().to_string(),
                });
            }
        }
    }

    Ok(skills)
}
