//! History store for conversations

use chrono::Utc;
use rusqlite::Connection;
use smanclaw_types::{Conversation, HistoryEntry, Role};
use std::path::Path;

use crate::error::{CoreError, Result as CoreResult};

/// SQLite-based history store
pub struct SqliteHistoryStore {
    conn: Connection,
}

impl SqliteHistoryStore {
    /// Create a new history store with the given database path
    pub fn new(db_path: &Path) -> CoreResult<Self> {
        let conn = Connection::open(db_path)?;
        let store = Self { conn };
        store.initialize()?;
        Ok(store)
    }

    /// Create an in-memory history store (for testing)
    pub fn in_memory() -> CoreResult<Self> {
        let conn = Connection::open_in_memory()?;
        let store = Self { conn };
        store.initialize()?;
        Ok(store)
    }

    /// Initialize database schema
    fn initialize(&self) -> CoreResult<()> {
        self.conn.execute(
            r#"
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            "#,
            [],
        )?;

        self.conn.execute(
            r#"
            CREATE TABLE IF NOT EXISTS history_entries (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            )
            "#,
            [],
        )?;

        Ok(())
    }

    /// Create a new conversation
    pub fn create_conversation(&self, project_id: &str, title: &str) -> CoreResult<Conversation> {
        let conversation = Conversation {
            id: uuid::Uuid::new_v4().to_string(),
            project_id: project_id.to_string(),
            title: title.to_string(),
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };

        self.conn.execute(
            "INSERT INTO conversations (id, project_id, title, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?5)",
            [
                &conversation.id,
                &conversation.project_id,
                &conversation.title,
                &conversation.created_at.to_rfc3339(),
                &conversation.updated_at.to_rfc3339(),
            ],
        )?;

        Ok(conversation)
    }

    /// Save a history entry
    pub fn save_entry(&self, entry: &HistoryEntry) -> CoreResult<()> {
        self.conn.execute(
            "INSERT INTO history_entries (id, conversation_id, role, content, timestamp) VALUES (?1, ?2, ?3, ?4, ?5)",
            [
                &entry.id,
                &entry.conversation_id,
                &entry.role.to_string(),
                &entry.content,
                &entry.timestamp.to_rfc3339(),
            ],
        )?;

        // Update conversation updated_at
        self.conn.execute(
            "UPDATE conversations SET updated_at = ?1 WHERE id = ?2",
            [&Utc::now().to_rfc3339(), &entry.conversation_id],
        )?;

        Ok(())
    }

    /// Load conversation history
    pub fn load_conversation(&self, conversation_id: &str) -> CoreResult<Vec<HistoryEntry>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, conversation_id, role, content, timestamp FROM history_entries WHERE conversation_id = ?1 ORDER BY timestamp ASC"
        )?;

        let entries = stmt
            .query_map([conversation_id], |row| {
                let role_str: String = row.get(2)?;
                let role: Role = role_str.parse().unwrap_or(Role::User);
                let timestamp_str: String = row.get(4)?;

                Ok(HistoryEntry {
                    id: row.get(0)?,
                    conversation_id: row.get(1)?,
                    role,
                    content: row.get(3)?,
                    timestamp: chrono::DateTime::parse_from_rfc3339(&timestamp_str)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;

        Ok(entries)
    }

    /// List conversations for a project
    pub fn list_conversations(&self, project_id: &str) -> CoreResult<Vec<Conversation>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, project_id, title, created_at, updated_at FROM conversations WHERE project_id = ?1 ORDER BY updated_at DESC"
        )?;

        let conversations = stmt
            .query_map([project_id], |row| {
                let created_at_str: String = row.get(3)?;
                let updated_at_str: String = row.get(4)?;

                Ok(Conversation {
                    id: row.get(0)?,
                    project_id: row.get(1)?,
                    title: row.get(2)?,
                    created_at: chrono::DateTime::parse_from_rfc3339(&created_at_str)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                    updated_at: chrono::DateTime::parse_from_rfc3339(&updated_at_str)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;

        Ok(conversations)
    }

    /// Get a conversation by ID
    pub fn get_conversation(&self, conversation_id: &str) -> CoreResult<Option<Conversation>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, project_id, title, created_at, updated_at FROM conversations WHERE id = ?1"
        )?;

        let result = stmt.query_row([conversation_id], |row| {
            let created_at_str: String = row.get(3)?;
            let updated_at_str: String = row.get(4)?;

            Ok(Conversation {
                id: row.get(0)?,
                project_id: row.get(1)?,
                title: row.get(2)?,
                created_at: chrono::DateTime::parse_from_rfc3339(&created_at_str)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
                updated_at: chrono::DateTime::parse_from_rfc3339(&updated_at_str)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        });

        match result {
            Ok(conv) => Ok(Some(conv)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(CoreError::from(e)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_conversation_should_succeed() {
        let store = SqliteHistoryStore::in_memory().expect("create store");
        let conv = store.create_conversation("proj-123", "Login Feature").expect("create");

        assert_eq!(conv.project_id, "proj-123");
        assert_eq!(conv.title, "Login Feature");
    }

    #[test]
    fn save_and_load_entries() {
        let store = SqliteHistoryStore::in_memory().expect("create store");
        let conv = store.create_conversation("proj-123", "Test").expect("create");

        let entry1 = HistoryEntry {
            id: uuid::Uuid::new_v4().to_string(),
            conversation_id: conv.id.clone(),
            role: Role::User,
            content: "Hello".to_string(),
            timestamp: Utc::now(),
        };
        let entry2 = HistoryEntry {
            id: uuid::Uuid::new_v4().to_string(),
            conversation_id: conv.id.clone(),
            role: Role::Assistant,
            content: "Hi there!".to_string(),
            timestamp: Utc::now(),
        };

        store.save_entry(&entry1).expect("save entry1");
        store.save_entry(&entry2).expect("save entry2");

        let entries = store.load_conversation(&conv.id).expect("load");
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].content, "Hello");
        assert_eq!(entries[1].content, "Hi there!");
    }

    #[test]
    fn list_conversations_by_project() {
        let store = SqliteHistoryStore::in_memory().expect("create store");
        store.create_conversation("proj-a", "Conv A1").expect("create");
        store.create_conversation("proj-a", "Conv A2").expect("create");
        store.create_conversation("proj-b", "Conv B1").expect("create");

        let convs_a = store.list_conversations("proj-a").expect("list");
        assert_eq!(convs_a.len(), 2);

        let convs_b = store.list_conversations("proj-b").expect("list");
        assert_eq!(convs_b.len(), 1);

        let convs_c = store.list_conversations("proj-c").expect("list");
        assert!(convs_c.is_empty());
    }

    #[test]
    fn get_conversation_should_return_none_for_nonexistent() {
        let store = SqliteHistoryStore::in_memory().expect("create store");
        let result = store.get_conversation("nonexistent").expect("get");
        assert!(result.is_none());
    }
}
