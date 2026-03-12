// src-tauri/src/commands/conversation.rs
//! Conversation management commands

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::OnceLock;
use uuid::Uuid;
use chrono::Utc;

/// Conversation record
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversationRecord {
    pub id: String,
    pub projectId: String,
    pub title: String,
    pub createdAt: String,
    pub updatedAt: String,
}

/// History entry record (message)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryEntryRecord {
    pub id: String,
    pub conversationId: String,
    pub role: String,
    pub content: String,
    pub createdAt: String,
}

/// Message route decision
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MessageRouteDecision {
    pub route: String,
    pub reason: String,
}

/// Conversations storage per project
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct ConversationsStorage {
    conversations: Vec<ConversationRecord>,
}

/// Messages storage per conversation
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct MessagesStorage {
    messages: Vec<HistoryEntryRecord>,
}

/// Get conversations directory path (~/.smanlocal/conversations/)
fn conversations_dir() -> &'static PathBuf {
    static PATH: OnceLock<PathBuf> = OnceLock::new();
    PATH.get_or_init(|| {
        let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("/"));
        home.join(".smanlocal").join("conversations")
    })
}

/// Ensure conversations directory exists
fn ensure_conversations_dir() -> Result<(), String> {
    let dir = conversations_dir();
    if !dir.exists() {
        fs::create_dir_all(dir).map_err(|e| format!("Failed to create conversations dir: {}", e))?;
    }
    Ok(())
}

/// Get project conversations file path
fn project_conversations_path(project_id: &str) -> PathBuf {
    conversations_dir().join(format!("{}.json", project_id))
}

/// Get conversation messages file path
fn conversation_messages_path(conversation_id: &str) -> PathBuf {
    conversations_dir().join(format!("{}_messages.json", conversation_id))
}

/// Load project conversations
fn load_project_conversations(project_id: &str) -> Result<ConversationsStorage, String> {
    let path = project_conversations_path(project_id);
    if !path.exists() {
        return Ok(ConversationsStorage::default());
    }
    let content = fs::read_to_string(&path)
        .map_err(|e| format!("Failed to read conversations: {}", e))?;
    let storage: ConversationsStorage = serde_json::from_str(&content)
        .unwrap_or_else(|_| ConversationsStorage::default());
    Ok(storage)
}

/// Save project conversations
fn save_project_conversations(project_id: &str, storage: &ConversationsStorage) -> Result<(), String> {
    ensure_conversations_dir()?;
    let path = project_conversations_path(project_id);
    let content = serde_json::to_string_pretty(storage)
        .map_err(|e| format!("Failed to serialize conversations: {}", e))?;
    fs::write(path, &content)
        .map_err(|e| format!("Failed to write conversations: {}", e))?;
    Ok(())
}

/// Load conversation messages
fn load_conversation_messages(conversation_id: &str) -> Result<MessagesStorage, String> {
    let path = conversation_messages_path(conversation_id);
    if !path.exists() {
        return Ok(MessagesStorage::default());
    }
    let content = fs::read_to_string(&path)
        .map_err(|e| format!("Failed to read messages: {}", e))?;
    let storage: MessagesStorage = serde_json::from_str(&content)
        .unwrap_or_else(|_| MessagesStorage::default());
    Ok(storage)
}

/// Save conversation messages
fn save_conversation_messages(conversation_id: &str, storage: &MessagesStorage) -> Result<(), String> {
    ensure_conversations_dir()?;
    let path = conversation_messages_path(conversation_id);
    let content = serde_json::to_string_pretty(storage)
        .map_err(|e| format!("Failed to serialize messages: {}", e))?;
    fs::write(path, &content)
        .map_err(|e| format!("Failed to write messages: {}", e))?;
    Ok(())
}

fn touch_conversation_updated_at(project_id: &str, conversation_id: &str) -> Result<(), String> {
    let mut storage = load_project_conversations(project_id)?;
    let now = Utc::now().to_rfc3339();
    let mut updated = false;
    for conversation in &mut storage.conversations {
        if conversation.id == conversation_id {
            conversation.updatedAt = now.clone();
            updated = true;
            break;
        }
    }
    if !updated {
        return Ok(());
    }
    storage
        .conversations
        .sort_by(|a, b| b.updatedAt.cmp(&a.updatedAt));
    save_project_conversations(project_id, &storage)
}

#[tauri::command]
pub fn list_conversations(projectId: String) -> Result<Vec<ConversationRecord>, String> {
    let storage = load_project_conversations(&projectId)?;
    Ok(storage.conversations)
}

#[tauri::command]
pub fn create_conversation(projectId: String, title: String) -> Result<ConversationRecord, String> {
    let now = Utc::now().to_rfc3339();
    let conversation = ConversationRecord {
        id: Uuid::new_v4().to_string(),
        projectId: projectId.clone(),
        title,
        createdAt: now.clone(),
        updatedAt: now,
    };

    let mut storage = load_project_conversations(&projectId)?;
    storage.conversations.insert(0, conversation.clone());
    save_project_conversations(&projectId, &storage)?;

    Ok(conversation)
}

#[tauri::command]
pub fn get_conversation_messages(conversationId: String) -> Result<Vec<HistoryEntryRecord>, String> {
    let storage = load_conversation_messages(&conversationId)?;
    Ok(storage.messages)
}

#[tauri::command]
pub fn send_message(
    conversationId: String,
    content: String,
    role: Option<String>,
    projectId: Option<String>,
) -> Result<HistoryEntryRecord, String> {
    let now = Utc::now().to_rfc3339();
    let normalized_role = role
        .unwrap_or_else(|| "user".to_string())
        .to_lowercase();
    let message = HistoryEntryRecord {
        id: Uuid::new_v4().to_string(),
        conversationId: conversationId.clone(),
        role: normalized_role,
        content,
        createdAt: now.clone(),
    };

    let mut storage = load_conversation_messages(&conversationId)?;
    storage.messages.push(message.clone());
    save_conversation_messages(&conversationId, &storage)?;

    if let Some(project_id) = projectId {
        touch_conversation_updated_at(&project_id, &conversationId)?;
    }

    Ok(message)
}

#[tauri::command]
pub fn decide_message_route(projectId: String, content: String) -> Result<MessageRouteDecision, String> {
    // Simple route decision based on content
    let content_lower = content.to_lowercase();

    let (route, reason) = if content_lower.starts_with('/') || content_lower.starts_with('\\') {
        ("skill".to_string(), "Content starts with skill trigger".to_string())
    } else if content_lower.contains("搜索") || content_lower.contains("search") {
        ("web_search".to_string(), "Content contains search keywords".to_string())
    } else {
        ("chat".to_string(), "Default chat route".to_string())
    };

    Ok(MessageRouteDecision { route, reason })
}
