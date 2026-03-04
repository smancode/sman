//! Identity file management for SmanClaw
//!
//! This module provides functionality to read and write identity files
//! that define the agent's personality, user preferences, and project context.

use std::fs;
use std::path::{Path, PathBuf};

use crate::error::{CoreError, Result};

/// Identity files manager
///
/// Manages SOUL.md, USER.md, and AGENTS.md files that provide context
/// for the main Claw (orchestrator).
pub struct IdentityFiles {
    project_path: PathBuf,
}

impl IdentityFiles {
    /// Create a new IdentityFiles manager for the given project path
    pub fn new(project_path: &Path) -> Self {
        Self {
            project_path: project_path.to_path_buf(),
        }
    }

    /// Read the SOUL.md file (agent personality)
    ///
    /// Returns None if the file doesn't exist
    pub fn read_soul(&self) -> Option<String> {
        self.read_file("SOUL.md")
    }

    /// Read the USER.md file (user preferences)
    ///
    /// Returns None if the file doesn't exist
    pub fn read_user(&self) -> Option<String> {
        self.read_file("USER.md")
    }

    /// Read the AGENTS.md file (project configuration)
    ///
    /// Returns None if the file doesn't exist
    pub fn read_agents(&self) -> Option<String> {
        self.read_file("AGENTS.md")
    }

    /// Write to the USER.md file
    ///
    /// Creates the file if it doesn't exist
    pub fn write_user(&self, content: &str) -> Result<()> {
        self.write_file("USER.md", content)
    }

    /// Write to the AGENTS.md file
    ///
    /// Creates the file if it doesn't exist
    pub fn write_agents(&self, content: &str) -> Result<()> {
        self.write_file("AGENTS.md", content)
    }

    /// Check if AGENTS.md exists
    pub fn agents_exists(&self) -> bool {
        self.project_path.join("AGENTS.md").exists()
    }

    /// Check if SOUL.md exists
    pub fn soul_exists(&self) -> bool {
        self.project_path.join("SOUL.md").exists()
    }

    /// Check if USER.md exists
    pub fn user_exists(&self) -> bool {
        self.project_path.join("USER.md").exists()
    }

    /// Get the project path
    pub fn project_path(&self) -> &Path {
        &self.project_path
    }

    /// Read a file from the project root
    fn read_file(&self, name: &str) -> Option<String> {
        fs::read_to_string(self.project_path.join(name)).ok()
    }

    /// Write a file to the project root
    fn write_file(&self, name: &str, content: &str) -> Result<()> {
        fs::write(self.project_path.join(name), content).map_err(CoreError::Io)
    }

    /// Build context string from all identity files
    ///
    /// Combines SOUL.md, USER.md, and AGENTS.md into a single context string
    pub fn build_context(&self) -> IdentityContext {
        let soul = self.read_soul().unwrap_or_default();
        let user = self.read_user().unwrap_or_default();
        let agents = self.read_agents().unwrap_or_default();

        IdentityContext {
            soul,
            user,
            agents,
            has_soul: self.soul_exists(),
            has_user: self.user_exists(),
            has_agents: self.agents_exists(),
        }
    }
}

/// Combined context from identity files
#[derive(Debug, Clone, Default)]
pub struct IdentityContext {
    /// Content of SOUL.md
    pub soul: String,
    /// Content of USER.md
    pub user: String,
    /// Content of AGENTS.md
    pub agents: String,
    /// Whether SOUL.md exists
    pub has_soul: bool,
    /// Whether USER.md exists
    pub has_user: bool,
    /// Whether AGENTS.md exists
    pub has_agents: bool,
}

impl IdentityContext {
    /// Check if any identity files exist
    pub fn has_any(&self) -> bool {
        self.has_soul || self.has_user || self.has_agents
    }

    /// Check if all identity files exist
    pub fn has_all(&self) -> bool {
        self.has_soul && self.has_user && self.has_agents
    }

    /// Get combined context as a single string
    pub fn combined(&self) -> String {
        let mut result = String::new();

        if !self.soul.is_empty() {
            result.push_str("## SOUL (Agent Personality)\n\n");
            result.push_str(&self.soul);
            result.push_str("\n\n");
        }

        if !self.user.is_empty() {
            result.push_str("## USER (User Preferences)\n\n");
            result.push_str(&self.user);
            result.push_str("\n\n");
        }

        if !self.agents.is_empty() {
            result.push_str("## AGENTS (Project Context)\n\n");
            result.push_str(&self.agents);
            result.push_str("\n\n");
        }

        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_identity_files() -> (TempDir, IdentityFiles) {
        let temp_dir = TempDir::new().expect("temp dir");
        let identity = IdentityFiles::new(temp_dir.path());
        (temp_dir, identity)
    }

    #[test]
    fn test_identity_files_creation() {
        let (_temp_dir, identity) = create_identity_files();
        assert!(identity.project_path.exists());
    }

    #[test]
    fn test_read_soul_exists() {
        let (temp_dir, identity) = create_identity_files();
        fs::write(temp_dir.path().join("SOUL.md"), "Test soul content").expect("write");

        let content = identity.read_soul();
        assert_eq!(content, Some("Test soul content".to_string()));
    }

    #[test]
    fn test_read_soul_not_exists() {
        let (_temp_dir, identity) = create_identity_files();
        let content = identity.read_soul();
        assert!(content.is_none());
    }

    #[test]
    fn test_read_user_exists() {
        let (temp_dir, identity) = create_identity_files();
        fs::write(temp_dir.path().join("USER.md"), "Test user content").expect("write");

        let content = identity.read_user();
        assert_eq!(content, Some("Test user content".to_string()));
    }

    #[test]
    fn test_read_user_not_exists() {
        let (_temp_dir, identity) = create_identity_files();
        let content = identity.read_user();
        assert!(content.is_none());
    }

    #[test]
    fn test_read_agents_exists() {
        let (temp_dir, identity) = create_identity_files();
        fs::write(temp_dir.path().join("AGENTS.md"), "Test agents content").expect("write");

        let content = identity.read_agents();
        assert_eq!(content, Some("Test agents content".to_string()));
    }

    #[test]
    fn test_read_agents_not_exists() {
        let (_temp_dir, identity) = create_identity_files();
        let content = identity.read_agents();
        assert!(content.is_none());
    }

    #[test]
    fn test_write_user() {
        let (temp_dir, identity) = create_identity_files();
        identity.write_user("New user content").expect("write");

        let content = fs::read_to_string(temp_dir.path().join("USER.md")).expect("read");
        assert_eq!(content, "New user content");
    }

    #[test]
    fn test_write_agents() {
        let (temp_dir, identity) = create_identity_files();
        identity.write_agents("New agents content").expect("write");

        let content = fs::read_to_string(temp_dir.path().join("AGENTS.md")).expect("read");
        assert_eq!(content, "New agents content");
    }

    #[test]
    fn test_agents_exists_true() {
        let (temp_dir, identity) = create_identity_files();
        fs::write(temp_dir.path().join("AGENTS.md"), "content").expect("write");
        assert!(identity.agents_exists());
    }

    #[test]
    fn test_agents_exists_false() {
        let (_temp_dir, identity) = create_identity_files();
        assert!(!identity.agents_exists());
    }

    #[test]
    fn test_soul_exists_true() {
        let (temp_dir, identity) = create_identity_files();
        fs::write(temp_dir.path().join("SOUL.md"), "content").expect("write");
        assert!(identity.soul_exists());
    }

    #[test]
    fn test_soul_exists_false() {
        let (_temp_dir, identity) = create_identity_files();
        assert!(!identity.soul_exists());
    }

    #[test]
    fn test_user_exists_true() {
        let (temp_dir, identity) = create_identity_files();
        fs::write(temp_dir.path().join("USER.md"), "content").expect("write");
        assert!(identity.user_exists());
    }

    #[test]
    fn test_user_exists_false() {
        let (_temp_dir, identity) = create_identity_files();
        assert!(!identity.user_exists());
    }

    #[test]
    fn test_project_path() {
        let (temp_dir, identity) = create_identity_files();
        assert_eq!(identity.project_path(), temp_dir.path());
    }

    #[test]
    fn test_build_context_empty() {
        let (_temp_dir, identity) = create_identity_files();
        let context = identity.build_context();

        assert!(context.soul.is_empty());
        assert!(context.user.is_empty());
        assert!(context.agents.is_empty());
        assert!(!context.has_any());
    }

    #[test]
    fn test_build_context_with_files() {
        let (temp_dir, identity) = create_identity_files();
        fs::write(temp_dir.path().join("SOUL.md"), "soul").expect("write");
        fs::write(temp_dir.path().join("USER.md"), "user").expect("write");
        fs::write(temp_dir.path().join("AGENTS.md"), "agents").expect("write");

        let context = identity.build_context();

        assert_eq!(context.soul, "soul");
        assert_eq!(context.user, "user");
        assert_eq!(context.agents, "agents");
        assert!(context.has_all());
    }

    #[test]
    fn test_identity_context_combined() {
        let context = IdentityContext {
            soul: "Soul content".to_string(),
            user: "User content".to_string(),
            agents: "Agents content".to_string(),
            has_soul: true,
            has_user: true,
            has_agents: true,
        };

        let combined = context.combined();
        assert!(combined.contains("SOUL"));
        assert!(combined.contains("Soul content"));
        assert!(combined.contains("USER"));
        assert!(combined.contains("User content"));
        assert!(combined.contains("AGENTS"));
        assert!(combined.contains("Agents content"));
    }

    #[test]
    fn test_identity_context_has_any() {
        let context = IdentityContext {
            soul: String::new(),
            user: String::new(),
            agents: String::new(),
            has_soul: true,
            has_user: false,
            has_agents: false,
        };
        assert!(context.has_any());
        assert!(!context.has_all());
    }
}
