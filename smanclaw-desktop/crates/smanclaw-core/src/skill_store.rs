//! Skill store for managing project experience knowledge
//!
//! Skills are stored in the project's `.skills/` directory as Markdown files
//! with an index.json file for metadata tracking.

use chrono::Utc;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

use crate::error::{CoreError, Result};

/// Index file version
const INDEX_VERSION: &str = "1.0";
/// Index file name
const INDEX_FILE: &str = "index.json";
/// Skills directory name
const SKILLS_DIR: &str = ".skills";
/// Paths directory name
const PATHS_DIR: &str = "paths";

/// Skill content with metadata
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Skill {
    /// Skill metadata
    pub meta: SkillMeta,
    /// Markdown content
    pub content: String,
}

/// Skill metadata
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillMeta {
    /// Unique identifier
    pub id: String,
    /// Relative path from skills directory (e.g., "coding/rust-style.md")
    pub path: String,
    /// Tags for categorization and search
    pub tags: Vec<String>,
    /// Source of the skill (e.g., "task-xxx" or "user-input")
    pub learned_from: String,
    /// Last update timestamp (Unix timestamp in seconds)
    pub updated_at: i64,
}

/// Index file structure for skills directory
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillIndex {
    /// Index format version
    pub version: String,
    /// Project name
    pub project_name: String,
    /// Last update timestamp (Unix timestamp in seconds)
    pub last_updated: i64,
    /// List of skill metadata
    pub skills: Vec<SkillMeta>,
}

impl Default for SkillIndex {
    fn default() -> Self {
        Self {
            version: INDEX_VERSION.to_string(),
            project_name: String::new(),
            last_updated: Utc::now().timestamp(),
            skills: Vec::new(),
        }
    }
}

/// Store for managing skills in a project
pub struct SkillStore {
    /// Path to the skills directory (.skills/)
    skills_dir: PathBuf,
}

impl SkillStore {
    /// Create a new SkillStore for a project
    ///
    /// # Arguments
    /// * `project_path` - Path to the project root directory
    ///
    /// # Returns
    /// A SkillStore instance or an error
    pub fn new(project_path: &std::path::Path) -> Result<Self> {
        Self::new_with_dir(project_path, SKILLS_DIR)
    }

    pub fn for_paths(project_path: &std::path::Path) -> Result<Self> {
        Self::new_with_dir(project_path, PATHS_DIR)
    }

    fn new_with_dir(project_path: &std::path::Path, dir_name: &str) -> Result<Self> {
        if !project_path.exists() {
            return Err(CoreError::InvalidInput(
                "Project path does not exist".to_string(),
            ));
        }

        let skills_dir = project_path.join(dir_name);
        let store = Self { skills_dir };
        store.ensure_directory()?;
        Ok(store)
    }

    /// Ensure the skills directory exists
    fn ensure_directory(&self) -> Result<()> {
        if !self.skills_dir.exists() {
            fs::create_dir_all(&self.skills_dir)?;
        }
        Ok(())
    }

    /// Get the path to the index file
    fn index_path(&self) -> PathBuf {
        self.skills_dir.join(INDEX_FILE)
    }

    /// Get the full path for a skill file
    fn skill_path(&self, relative_path: &str) -> PathBuf {
        self.skills_dir.join(relative_path)
    }

    /// Load the skill index from disk
    fn load_index(&self) -> Result<SkillIndex> {
        let index_path = self.index_path();
        if !index_path.exists() {
            return Ok(SkillIndex::default());
        }

        let content = fs::read_to_string(&index_path)?;
        let index: SkillIndex = serde_json::from_str(&content)?;
        Ok(index)
    }

    /// Save the skill index to disk
    fn save_index(&self, index: &SkillIndex) -> Result<()> {
        let index_path = self.index_path();
        let content = serde_json::to_string_pretty(index)?;
        fs::write(&index_path, content)?;
        Ok(())
    }

    /// List all skills (metadata only)
    ///
    /// # Returns
    /// A list of skill metadata
    pub fn list(&self) -> Result<Vec<SkillMeta>> {
        let index = self.load_index()?;
        Ok(index.skills)
    }

    /// Get a skill by ID (including content)
    ///
    /// # Arguments
    /// * `id` - The skill ID
    ///
    /// # Returns
    /// The skill with content, or None if not found
    pub fn get(&self, id: &str) -> Result<Option<Skill>> {
        let index = self.load_index()?;

        if let Some(meta) = index.skills.iter().find(|s| s.id == id) {
            let skill_path = self.skill_path(&meta.path);
            if skill_path.exists() {
                let content = fs::read_to_string(&skill_path)?;
                return Ok(Some(Skill {
                    meta: meta.clone(),
                    content,
                }));
            }
        }

        Ok(None)
    }

    /// Create a new skill
    ///
    /// # Arguments
    /// * `skill` - The skill to create
    ///
    /// # Returns
    /// An error if a skill with the same ID already exists
    pub fn create(&self, skill: &Skill) -> Result<()> {
        if skill.meta.id.is_empty() {
            return Err(CoreError::InvalidInput(
                "Skill ID cannot be empty".to_string(),
            ));
        }
        if skill.meta.path.is_empty() {
            return Err(CoreError::InvalidInput(
                "Skill path cannot be empty".to_string(),
            ));
        }
        if skill.content.trim().is_empty() {
            return Err(CoreError::InvalidInput(
                "Skill content cannot be empty".to_string(),
            ));
        }

        let mut index = self.load_index()?;

        // Check if skill already exists
        if index.skills.iter().any(|s| s.id == skill.meta.id) {
            return Err(CoreError::SkillAlreadyExists(skill.meta.id.clone()));
        }

        // Ensure parent directory exists
        let skill_path = self.skill_path(&skill.meta.path);
        if let Some(parent) = skill_path.parent() {
            fs::create_dir_all(parent)?;
        }

        // Write skill file
        fs::write(&skill_path, &skill.content)?;

        // Update index
        index.skills.push(skill.meta.clone());
        index.last_updated = Utc::now().timestamp();
        self.save_index(&index)?;

        Ok(())
    }

    /// Update an existing skill
    ///
    /// # Arguments
    /// * `skill` - The skill with updated content
    ///
    /// # Returns
    /// An error if the skill does not exist
    pub fn update(&self, skill: &Skill) -> Result<()> {
        if skill.content.trim().is_empty() {
            return Err(CoreError::InvalidInput(
                "Skill content cannot be empty".to_string(),
            ));
        }
        let mut index = self.load_index()?;

        // Find and update the skill
        let meta = index
            .skills
            .iter_mut()
            .find(|s| s.id == skill.meta.id)
            .ok_or_else(|| CoreError::SkillNotFound(skill.meta.id.clone()))?;

        // If path changed, move the file
        if meta.path != skill.meta.path {
            let old_path = self.skill_path(&meta.path);
            let new_path = self.skill_path(&skill.meta.path);

            // Ensure new parent directory exists
            if let Some(parent) = new_path.parent() {
                fs::create_dir_all(parent)?;
            }

            // Move file
            if old_path.exists() {
                fs::rename(&old_path, &new_path)?;
            }
        }

        // Write updated content
        let skill_path = self.skill_path(&skill.meta.path);
        fs::write(&skill_path, &skill.content)?;

        // Update metadata in index
        *meta = skill.meta.clone();
        meta.updated_at = Utc::now().timestamp();
        index.last_updated = Utc::now().timestamp();
        self.save_index(&index)?;

        Ok(())
    }

    /// Delete a skill by ID
    ///
    /// # Arguments
    /// * `id` - The skill ID to delete
    ///
    /// # Returns
    /// An error if the skill does not exist
    pub fn delete(&self, id: &str) -> Result<()> {
        let mut index = self.load_index()?;

        // Find the skill
        let pos = index
            .skills
            .iter()
            .position(|s| s.id == id)
            .ok_or_else(|| CoreError::SkillNotFound(id.to_string()))?;

        let meta = &index.skills[pos];
        let skill_path = self.skill_path(&meta.path);

        // Remove file
        if skill_path.exists() {
            fs::remove_file(&skill_path)?;
        }

        // Remove from index
        index.skills.remove(pos);
        index.last_updated = Utc::now().timestamp();
        self.save_index(&index)?;

        Ok(())
    }

    /// Find skills by tags
    ///
    /// # Arguments
    /// * `tags` - Tags to search for (matches if skill has ANY of the tags)
    ///
    /// # Returns
    /// Skills that match at least one of the given tags
    pub fn find_by_tags(&self, tags: &[&str]) -> Result<Vec<SkillMeta>> {
        let index = self.load_index()?;

        if tags.is_empty() {
            return Ok(index.skills);
        }

        let matching: Vec<SkillMeta> = index
            .skills
            .into_iter()
            .filter(|skill| tags.iter().any(|tag| skill.tags.iter().any(|t| t == tag)))
            .collect();

        Ok(matching)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_test_skill(id: &str, path: &str, tags: Vec<&str>) -> Skill {
        Skill {
            meta: SkillMeta {
                id: id.to_string(),
                path: path.to_string(),
                tags: tags.into_iter().map(String::from).collect(),
                learned_from: "test".to_string(),
                updated_at: Utc::now().timestamp(),
            },
            content: format!("# Skill {}\n\nTest content for {}.", id, id),
        }
    }

    #[test]
    fn skill_store_create_should_succeed() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path());
        assert!(store.is_ok());
    }

    #[test]
    fn path_store_create_should_succeed() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::for_paths(temp_dir.path());
        assert!(store.is_ok());
    }

    #[test]
    fn skill_store_create_and_get_skill() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill = create_test_skill("skill-001", "coding/rust-style.md", vec!["rust", "coding"]);

        store.create(&skill).expect("create skill");

        let loaded = store.get("skill-001").expect("get skill");
        assert!(loaded.is_some());

        let loaded = loaded.unwrap();
        assert_eq!(loaded.meta.id, "skill-001");
        assert_eq!(loaded.meta.path, "coding/rust-style.md");
        assert_eq!(loaded.content, skill.content);
    }

    #[test]
    fn skill_store_update_skill() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill = create_test_skill("skill-002", "architecture/module.md", vec!["architecture"]);
        store.create(&skill).expect("create skill");

        let mut updated_skill = skill.clone();
        updated_skill.content = "# Updated Skill\n\nNew content.".to_string();
        updated_skill.meta.tags.push("design".to_string());

        store.update(&updated_skill).expect("update skill");

        let loaded = store.get("skill-002").expect("get skill").unwrap();
        assert_eq!(loaded.content, "# Updated Skill\n\nNew content.");
        assert!(loaded.meta.tags.contains(&"design".to_string()));
    }

    #[test]
    fn skill_store_delete_skill() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill = create_test_skill("skill-003", "domain/user-module.md", vec!["domain"]);
        store.create(&skill).expect("create skill");

        let loaded = store.get("skill-003").expect("get skill");
        assert!(loaded.is_some());

        store.delete("skill-003").expect("delete skill");

        let loaded = store.get("skill-003").expect("get skill");
        assert!(loaded.is_none());
    }

    #[test]
    fn skill_store_list_skills() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill1 = create_test_skill("skill-004", "coding/rust.md", vec!["rust"]);
        let skill2 = create_test_skill("skill-005", "coding/python.md", vec!["python"]);
        let skill3 = create_test_skill("skill-006", "domain/user.md", vec!["domain"]);

        store.create(&skill1).expect("create skill1");
        store.create(&skill2).expect("create skill2");
        store.create(&skill3).expect("create skill3");

        let skills = store.list().expect("list skills");
        assert_eq!(skills.len(), 3);
    }

    #[test]
    fn skill_store_find_by_tags() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill1 = create_test_skill("skill-007", "coding/rust.md", vec!["rust", "coding"]);
        let skill2 = create_test_skill("skill-008", "coding/python.md", vec!["python", "coding"]);
        let skill3 = create_test_skill("skill-009", "domain/user.md", vec!["domain", "model"]);

        store.create(&skill1).expect("create skill1");
        store.create(&skill2).expect("create skill2");
        store.create(&skill3).expect("create skill3");

        // Find by single tag
        let rust_skills = store.find_by_tags(&["rust"]).expect("find by tag");
        assert_eq!(rust_skills.len(), 1);
        assert_eq!(rust_skills[0].id, "skill-007");

        // Find by multiple tags (OR logic)
        let coding_skills = store
            .find_by_tags(&["coding", "domain"])
            .expect("find by tags");
        assert_eq!(coding_skills.len(), 3);

        // Find with no matches
        let no_match = store.find_by_tags(&["nonexistent"]).expect("find by tag");
        assert!(no_match.is_empty());

        // Empty tags returns all
        let all_skills = store.find_by_tags(&[]).expect("find by empty tags");
        assert_eq!(all_skills.len(), 3);
    }

    #[test]
    fn skill_store_index_sync() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill = create_test_skill("skill-010", "test/sync.md", vec!["test"]);
        store.create(&skill).expect("create skill");

        // Check index file exists and contains the skill
        let index = store.load_index().expect("load index");
        assert_eq!(index.skills.len(), 1);
        assert_eq!(index.skills[0].id, "skill-010");

        // Update skill
        let mut updated = skill.clone();
        updated.content = "Updated content".to_string();
        store.update(&updated).expect("update skill");

        // Index should still have one skill
        let index = store.load_index().expect("load index");
        assert_eq!(index.skills.len(), 1);

        // Delete skill
        store.delete("skill-010").expect("delete skill");

        // Index should be empty
        let index = store.load_index().expect("load index");
        assert!(index.skills.is_empty());
    }

    #[test]
    fn skill_store_create_duplicate_should_fail() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill = create_test_skill("skill-011", "test/dup.md", vec!["test"]);
        store.create(&skill).expect("create skill");

        // Creating with same ID should fail
        let result = store.create(&skill);
        assert!(result.is_err());
    }

    #[test]
    fn skill_store_update_nonexistent_should_fail() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill = create_test_skill("skill-012", "test/nonexistent.md", vec!["test"]);
        let result = store.update(&skill);
        assert!(result.is_err());
    }

    #[test]
    fn skill_store_delete_nonexistent_should_fail() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let result = store.delete("nonexistent");
        assert!(result.is_err());
    }

    #[test]
    fn skill_store_path_change_moves_file() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");

        let skill = create_test_skill("skill-013", "old/path.md", vec!["test"]);
        store.create(&skill).expect("create skill");

        // Update with new path
        let mut updated = skill.clone();
        updated.meta.path = "new/location.md".to_string();
        updated.content = "Moved content".to_string();
        store.update(&updated).expect("update skill with new path");

        // Old file should be gone
        assert!(!store.skill_path("old/path.md").exists());

        // New file should exist
        let loaded = store.get("skill-013").expect("get skill").unwrap();
        assert_eq!(loaded.content, "Moved content");
        assert_eq!(loaded.meta.path, "new/location.md");
    }

    #[test]
    fn skill_store_create_rejects_empty_content() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");
        let mut skill = create_test_skill("skill-empty", "coding/empty.md", vec!["coding"]);
        skill.content = " \n\t ".to_string();

        let result = store.create(&skill);
        assert!(matches!(result, Err(CoreError::InvalidInput(_))));
    }

    #[test]
    fn skill_store_update_rejects_empty_content() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");
        let skill = create_test_skill("skill-update-empty", "coding/update-empty.md", vec!["coding"]);
        store.create(&skill).expect("create skill");

        let mut updated = skill.clone();
        updated.content = "\n".to_string();
        let result = store.update(&updated);
        assert!(matches!(result, Err(CoreError::InvalidInput(_))));
    }
}
