//! Task manager for creating and tracking tasks

use anyhow::Result;
use chrono::Utc;
use rusqlite::Connection;
use smanclaw_types::{Task, TaskId, TaskStatus};
use std::path::Path;

use crate::error::{CoreError, Result as CoreResult};

/// Task manager handles task lifecycle
pub struct TaskManager {
    conn: Connection,
}

impl TaskManager {
    /// Create a new task manager with the given database path
    pub fn new(db_path: &Path) -> CoreResult<Self> {
        let conn = Connection::open(db_path)?;
        let manager = Self { conn };
        manager.initialize()?;
        Ok(manager)
    }

    /// Create an in-memory task manager (for testing)
    pub fn in_memory() -> CoreResult<Self> {
        let conn = Connection::open_in_memory()?;
        let manager = Self { conn };
        manager.initialize()?;
        Ok(manager)
    }

    /// Initialize database schema
    fn initialize(&self) -> CoreResult<()> {
        self.conn.execute(
            r#"
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                input TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            "#,
            [],
        )?;
        Ok(())
    }

    /// Create a new task
    pub fn create_task(&self, project_id: &str, input: &str) -> CoreResult<Task> {
        let task = Task {
            id: uuid::Uuid::new_v4().to_string(),
            project_id: project_id.to_string(),
            input: input.to_string(),
            status: TaskStatus::Pending,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };

        self.conn.execute(
            "INSERT INTO tasks (id, project_id, input, status, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            [
                &task.id,
                &task.project_id,
                &task.input,
                &serde_json::to_string(&task.status)?,
                &task.created_at.to_rfc3339(),
                &task.updated_at.to_rfc3339(),
            ],
        )?;

        Ok(task)
    }

    /// Get a task by ID
    pub fn get_task(&self, task_id: &str) -> CoreResult<Option<Task>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, project_id, input, status, created_at, updated_at FROM tasks WHERE id = ?1"
        )?;

        let result = stmt.query_row([task_id], |row| {
            Ok(Task {
                id: row.get(0)?,
                project_id: row.get(1)?,
                input: row.get(2)?,
                status: serde_json::from_str(&row.get::<_, String>(3)?).unwrap_or(TaskStatus::Pending),
                created_at: chrono::DateTime::parse_from_rfc3339(&row.get::<_, String>(4)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
                updated_at: chrono::DateTime::parse_from_rfc3339(&row.get::<_, String>(5)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        });

        match result {
            Ok(task) => Ok(Some(task)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(CoreError::from(e)),
        }
    }

    /// List all tasks for a project
    pub fn list_tasks(&self, project_id: &str) -> CoreResult<Vec<Task>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, project_id, input, status, created_at, updated_at FROM tasks WHERE project_id = ?1 ORDER BY created_at DESC"
        )?;

        let tasks = stmt
            .query_map([project_id], |row| {
                Ok(Task {
                    id: row.get(0)?,
                    project_id: row.get(1)?,
                    input: row.get(2)?,
                    status: serde_json::from_str(&row.get::<_, String>(3)?).unwrap_or(TaskStatus::Pending),
                    created_at: chrono::DateTime::parse_from_rfc3339(&row.get::<_, String>(4)?)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                    updated_at: chrono::DateTime::parse_from_rfc3339(&row.get::<_, String>(5)?)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;

        Ok(tasks)
    }

    /// Update task status
    pub fn update_task_status(&self, task_id: &str, status: TaskStatus) -> CoreResult<()> {
        let now = Utc::now().to_rfc3339();
        let status_json = serde_json::to_string(&status)?;

        let rows_affected = self.conn.execute(
            "UPDATE tasks SET status = ?1, updated_at = ?2 WHERE id = ?3",
            [&status_json, &now, task_id],
        )?;

        if rows_affected == 0 {
            return Err(CoreError::TaskNotFound(task_id.to_string()));
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_task_should_succeed() {
        let manager = TaskManager::in_memory().expect("create manager");
        let task = manager.create_task("proj-123", "Implement login").expect("create task");

        assert_eq!(task.project_id, "proj-123");
        assert_eq!(task.input, "Implement login");
        assert_eq!(task.status, TaskStatus::Pending);
    }

    #[test]
    fn get_task_should_return_created_task() {
        let manager = TaskManager::in_memory().expect("create manager");
        let created = manager.create_task("proj-123", "Test task").expect("create task");

        let retrieved = manager.get_task(&created.id).expect("get task");
        assert!(retrieved.is_some());
        let retrieved = retrieved.unwrap();
        assert_eq!(retrieved.id, created.id);
        assert_eq!(retrieved.input, "Test task");
    }

    #[test]
    fn get_task_should_return_none_for_nonexistent() {
        let manager = TaskManager::in_memory().expect("create manager");
        let result = manager.get_task("nonexistent").expect("get task");
        assert!(result.is_none());
    }

    #[test]
    fn list_tasks_should_return_project_tasks() {
        let manager = TaskManager::in_memory().expect("create manager");
        manager.create_task("proj-a", "Task A1").expect("create");
        manager.create_task("proj-a", "Task A2").expect("create");
        manager.create_task("proj-b", "Task B1").expect("create");

        let tasks_a = manager.list_tasks("proj-a").expect("list");
        assert_eq!(tasks_a.len(), 2);

        let tasks_b = manager.list_tasks("proj-b").expect("list");
        assert_eq!(tasks_b.len(), 1);

        let tasks_c = manager.list_tasks("proj-c").expect("list");
        assert!(tasks_c.is_empty());
    }

    #[test]
    fn update_task_status_should_update_status() {
        let manager = TaskManager::in_memory().expect("create manager");
        let task = manager.create_task("proj-123", "Test").expect("create");

        manager.update_task_status(&task.id, TaskStatus::Running).expect("update");
        let updated = manager.get_task(&task.id).expect("get").expect("task exists");
        assert_eq!(updated.status, TaskStatus::Running);

        manager.update_task_status(&task.id, TaskStatus::Completed).expect("update");
        let updated = manager.get_task(&task.id).expect("get").expect("task exists");
        assert_eq!(updated.status, TaskStatus::Completed);
    }

    #[test]
    fn update_task_status_should_fail_for_nonexistent() {
        let manager = TaskManager::in_memory().expect("create manager");
        let result = manager.update_task_status("nonexistent", TaskStatus::Running);
        assert!(result.is_err());
    }
}
