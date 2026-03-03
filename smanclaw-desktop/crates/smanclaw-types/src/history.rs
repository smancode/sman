//! History-related types

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// A history entry in a conversation
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct HistoryEntry {
    /// Unique entry identifier
    pub id: String,
    /// Conversation this entry belongs to
    pub conversation_id: String,
    /// Role (user, assistant, system)
    pub role: Role,
    /// Message content
    pub content: String,
    /// Timestamp
    pub timestamp: DateTime<Utc>,
}

/// Role in a conversation
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum Role {
    /// User message
    User,
    /// Assistant message
    Assistant,
    /// System message
    System,
}

impl std::fmt::Display for Role {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Role::User => write!(f, "user"),
            Role::Assistant => write!(f, "assistant"),
            Role::System => write!(f, "system"),
        }
    }
}

impl std::str::FromStr for Role {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s.to_lowercase().as_str() {
            "user" => Ok(Role::User),
            "assistant" => Ok(Role::Assistant),
            "system" => Ok(Role::System),
            _ => Err(format!("Invalid role: {}", s)),
        }
    }
}

/// A conversation containing multiple history entries
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Conversation {
    /// Unique conversation identifier
    pub id: String,
    /// Project this conversation belongs to
    pub project_id: String,
    /// Conversation title
    pub title: String,
    /// Creation timestamp
    pub created_at: DateTime<Utc>,
    /// Last update timestamp
    pub updated_at: DateTime<Utc>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn history_entry_serialization() {
        let entry = HistoryEntry {
            id: "entry-123".to_string(),
            conversation_id: "conv-456".to_string(),
            role: Role::User,
            content: "Hello".to_string(),
            timestamp: Utc::now(),
        };

        let json = serde_json::to_string(&entry).expect("serialize");
        let deserialized: HistoryEntry = serde_json::from_str(&json).expect("deserialize");

        assert_eq!(entry, deserialized);
    }

    #[test]
    fn role_display_and_from_str() {
        assert_eq!(Role::User.to_string(), "user");
        assert_eq!(Role::Assistant.to_string(), "assistant");
        assert_eq!(Role::System.to_string(), "system");

        assert_eq!("user".parse::<Role>(), Ok(Role::User));
        assert_eq!("ASSISTANT".parse::<Role>(), Ok(Role::Assistant));
        assert!("invalid".parse::<Role>().is_err());
    }

    #[test]
    fn conversation_serialization() {
        let conv = Conversation {
            id: "conv-123".to_string(),
            project_id: "proj-456".to_string(),
            title: "Login Feature".to_string(),
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };

        let json = serde_json::to_string(&conv).expect("serialize");
        let deserialized: Conversation = serde_json::from_str(&json).expect("deserialize");

        assert_eq!(conv, deserialized);
    }
}
