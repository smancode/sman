//! Project manager for discovering and managing projects

use chrono::Utc;
use rusqlite::Connection;
use smanclaw_types::{Project, ProjectConfig};
use std::path::{Path, PathBuf};

use crate::error::{CoreError, Result as CoreResult};

/// Project manager handles project discovery and configuration
pub struct ProjectManager {
    config_dir: PathBuf,
    conn: Connection,
}

impl ProjectManager {
    /// Create a new project manager
    pub fn new(config_dir: PathBuf) -> CoreResult<Self> {
        std::fs::create_dir_all(&config_dir)?;
        let db_path = config_dir.join("projects.db");
        let conn = Connection::open(db_path)?;
        let manager = Self { config_dir, conn };
        manager.initialize()?;
        Ok(manager)
    }

    /// Create an in-memory project manager (for testing)
    pub fn in_memory() -> CoreResult<Self> {
        let conn = Connection::open_in_memory()?;
        let manager = Self {
            config_dir: PathBuf::from("."),
            conn,
        };
        manager.initialize()?;
        Ok(manager)
    }

    /// Initialize database schema
    fn initialize(&self) -> CoreResult<()> {
        self.conn.execute(
            r#"
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                path TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                last_accessed TEXT NOT NULL
            )
            "#,
            [],
        )?;

        self.conn.execute(
            r#"
            CREATE TABLE IF NOT EXISTS project_configs (
                project_id TEXT PRIMARY KEY,
                default_provider TEXT,
                default_model TEXT,
                FOREIGN KEY (project_id) REFERENCES projects(id)
            )
            "#,
            [],
        )?;

        Ok(())
    }

    /// Add a project
    pub fn add_project(&self, path: &Path) -> CoreResult<Project> {
        let path_str = path.to_string_lossy().to_string();
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "Untitled".to_string());

        let project = Project {
            id: uuid::Uuid::new_v4().to_string(),
            name,
            path: path_str,
            created_at: Utc::now(),
            last_accessed: Utc::now(),
        };

        self.conn.execute(
            "INSERT INTO projects (id, name, path, created_at, last_accessed) VALUES (?1, ?2, ?3, ?4, ?5)",
            [
                &project.id,
                &project.name,
                &project.path,
                &project.created_at.to_rfc3339(),
                &project.last_accessed.to_rfc3339(),
            ],
        )?;

        Ok(project)
    }

    /// Remove a project
    pub fn remove_project(&self, project_id: &str) -> CoreResult<()> {
        let rows_affected = self
            .conn
            .execute("DELETE FROM projects WHERE id = ?1", [project_id])?;

        if rows_affected == 0 {
            return Err(CoreError::ProjectNotFound(project_id.to_string()));
        }

        // Also remove associated config
        self.conn.execute(
            "DELETE FROM project_configs WHERE project_id = ?1",
            [project_id],
        )?;

        Ok(())
    }

    /// Get a project by ID
    pub fn get_project(&self, project_id: &str) -> CoreResult<Option<Project>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, path, created_at, last_accessed FROM projects WHERE id = ?1",
        )?;

        let result = stmt.query_row([project_id], |row| {
            let created_at_str: String = row.get(3)?;
            let last_accessed_str: String = row.get(4)?;

            Ok(Project {
                id: row.get(0)?,
                name: row.get(1)?,
                path: row.get(2)?,
                created_at: chrono::DateTime::parse_from_rfc3339(&created_at_str)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
                last_accessed: chrono::DateTime::parse_from_rfc3339(&last_accessed_str)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        });

        match result {
            Ok(project) => Ok(Some(project)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(CoreError::from(e)),
        }
    }

    /// List all projects
    pub fn list_projects(&self) -> CoreResult<Vec<Project>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, path, created_at, last_accessed FROM projects ORDER BY last_accessed DESC",
        )?;

        let projects = stmt
            .query_map([], |row| {
                let created_at_str: String = row.get(3)?;
                let last_accessed_str: String = row.get(4)?;

                Ok(Project {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    path: row.get(2)?,
                    created_at: chrono::DateTime::parse_from_rfc3339(&created_at_str)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                    last_accessed: chrono::DateTime::parse_from_rfc3339(&last_accessed_str)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;

        Ok(projects)
    }

    /// Update last accessed time
    pub fn touch_project(&self, project_id: &str) -> CoreResult<()> {
        let now = Utc::now().to_rfc3339();
        let rows_affected = self.conn.execute(
            "UPDATE projects SET last_accessed = ?1 WHERE id = ?2",
            [&now, project_id],
        )?;

        if rows_affected == 0 {
            return Err(CoreError::ProjectNotFound(project_id.to_string()));
        }

        Ok(())
    }

    /// Get project config
    pub fn get_project_config(&self, project_id: &str) -> CoreResult<ProjectConfig> {
        let mut stmt = self.conn.prepare(
            "SELECT project_id, default_provider, default_model FROM project_configs WHERE project_id = ?1",
        )?;

        let result = stmt.query_row([project_id], |row| {
            Ok(ProjectConfig {
                project_id: row.get(0)?,
                default_provider: row.get(1)?,
                default_model: row.get(2)?,
            })
        });

        match result {
            Ok(config) => Ok(config),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(ProjectConfig {
                project_id: project_id.to_string(),
                ..Default::default()
            }),
            Err(e) => Err(CoreError::from(e)),
        }
    }

    /// Update project config
    pub fn update_project_config(&self, config: &ProjectConfig) -> CoreResult<()> {
        self.conn.execute(
            r#"
            INSERT INTO project_configs (project_id, default_provider, default_model)
            VALUES (?1, ?2, ?3)
            ON CONFLICT(project_id) DO UPDATE SET
                default_provider = excluded.default_provider,
                default_model = excluded.default_model
            "#,
            [
                &config.project_id,
                &config.default_provider.clone().unwrap_or_default(),
                &config.default_model.clone().unwrap_or_default(),
            ],
        )?;

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn add_project_should_succeed() {
        let manager = ProjectManager::in_memory().expect("create manager");
        let temp_dir = TempDir::new().expect("temp dir");
        let project_path = temp_dir.path();

        let project = manager.add_project(project_path).expect("add project");

        assert_eq!(project.path, project_path.to_string_lossy());
    }

    #[test]
    fn add_and_get_project() {
        let manager = ProjectManager::in_memory().expect("create manager");
        let temp_dir = TempDir::new().expect("temp dir");
        let created = manager.add_project(temp_dir.path()).expect("add");

        let retrieved = manager.get_project(&created.id).expect("get");
        assert!(retrieved.is_some());
        let retrieved = retrieved.unwrap();
        assert_eq!(retrieved.id, created.id);
    }

    #[test]
    fn remove_project_should_succeed() {
        let manager = ProjectManager::in_memory().expect("create manager");
        let temp_dir = TempDir::new().expect("temp dir");
        let project = manager.add_project(temp_dir.path()).expect("add");

        manager.remove_project(&project.id).expect("remove");
        let result = manager.get_project(&project.id).expect("get");
        assert!(result.is_none());
    }

    #[test]
    fn remove_nonexistent_should_fail() {
        let manager = ProjectManager::in_memory().expect("create manager");
        let result = manager.remove_project("nonexistent");
        assert!(result.is_err());
    }

    #[test]
    fn list_projects() {
        let manager = ProjectManager::in_memory().expect("create manager");
        let temp1 = TempDir::new().expect("temp1");
        let temp2 = TempDir::new().expect("temp2");

        manager.add_project(temp1.path()).expect("add1");
        manager.add_project(temp2.path()).expect("add2");

        let projects = manager.list_projects().expect("list");
        assert_eq!(projects.len(), 2);
    }

    #[test]
    fn touch_project_updates_last_accessed() {
        let manager = ProjectManager::in_memory().expect("create manager");
        let temp = TempDir::new().expect("temp");
        let project = manager.add_project(temp.path()).expect("add");

        std::thread::sleep(std::time::Duration::from_millis(10));

        manager.touch_project(&project.id).expect("touch");
        let updated = manager
            .get_project(&project.id)
            .expect("get")
            .expect("exists");
        assert!(updated.last_accessed > project.last_accessed);
    }

    #[test]
    fn project_config_crud() {
        let manager = ProjectManager::in_memory().expect("create manager");
        let temp = TempDir::new().expect("temp");
        let project = manager.add_project(temp.path()).expect("add");

        // Default config
        let config = manager.get_project_config(&project.id).expect("get config");
        assert!(config.default_provider.is_none());

        // Update config
        let updated_config = ProjectConfig {
            project_id: project.id.clone(),
            default_provider: Some("openai".to_string()),
            default_model: Some("gpt-4".to_string()),
        };
        manager
            .update_project_config(&updated_config)
            .expect("update");

        // Verify update
        let retrieved = manager.get_project_config(&project.id).expect("get config");
        assert_eq!(retrieved.default_provider, Some("openai".to_string()));
        assert_eq!(retrieved.default_model, Some("gpt-4".to_string()));
    }
}
