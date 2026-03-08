//! Project knowledge types for AGENTS.md generation

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Skill metadata for skill picker
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct SkillMeta {
    /// Unique identifier
    pub id: String,
    /// Relative path from skills directory (e.g., "caijing")
    pub path: String,
    /// Tags for categorization and search
    pub tags: Vec<String>,
    /// Source of the skill (e.g., "project" or "global")
    pub learned_from: String,
    /// Last update timestamp (Unix timestamp in seconds)
    pub updated_at: i64,
}

/// Project type enumeration
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum ProjectType {
    /// Rust project
    Rust,
    /// Node.js/TypeScript project
    NodeJs,
    /// Python project
    Python,
    /// Go project
    Go,
    /// Java/Kotlin project
    Java,
    /// Mixed or unknown project type
    #[default]
    Unknown,
}

/// Build configuration
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct BuildConfig {
    /// Build command
    pub build_cmd: Option<String>,
    /// Test command
    pub test_cmd: Option<String>,
    /// Single test command template (use {} as placeholder for test name)
    pub single_test_cmd: Option<String>,
    /// Lint command
    pub lint_cmd: Option<String>,
    /// Format command
    pub format_cmd: Option<String>,
}

/// Code style guidelines
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct CodeStyle {
    /// Naming conventions
    pub naming_conventions: Vec<String>,
    /// Error handling approach
    pub error_handling: Option<String>,
    /// Import style
    pub import_style: Option<String>,
    /// Formatting rules
    pub formatting_rules: Vec<String>,
}

/// Module information
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ModuleInfo {
    /// Module name/path
    pub name: String,
    /// Module description
    pub description: String,
    /// Module responsibilities
    pub responsibilities: Vec<String>,
}

/// Project knowledge for AGENTS.md generation
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectKnowledge {
    /// Project name
    pub name: String,
    /// Project description
    pub description: Option<String>,
    /// Project type
    pub project_type: ProjectType,
    /// Directory structure (path -> description)
    pub directory_structure: Vec<String>,
    /// Dependencies (name -> version)
    pub dependencies: HashMap<String, String>,
    /// Build configuration
    pub build_config: BuildConfig,
    /// Code style guidelines
    pub code_style: CodeStyle,
    /// Module information
    pub modules: Vec<ModuleInfo>,
    /// Existing rules (from .cursorrules, .cursor/rules/, etc.)
    pub existing_rules: Vec<String>,
    /// Entry points
    pub entry_points: Vec<String>,
}

impl ProjectKnowledge {
    /// Create a new ProjectKnowledge with minimal information
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            ..Default::default()
        }
    }

    /// Create a Rust project knowledge with sensible defaults
    pub fn rust_project(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            project_type: ProjectType::Rust,
            build_config: BuildConfig {
                build_cmd: Some("cargo build".to_string()),
                test_cmd: Some("cargo test".to_string()),
                single_test_cmd: Some("cargo test <test_name>".to_string()),
                lint_cmd: Some("cargo clippy".to_string()),
                format_cmd: Some("cargo fmt".to_string()),
            },
            code_style: CodeStyle {
                naming_conventions: vec![
                    "Types: PascalCase (e.g., MyStruct)".to_string(),
                    "Functions/variables: snake_case (e.g., my_function)".to_string(),
                    "Constants: SCREAMING_SNAKE_CASE (e.g., MAX_RETRIES)".to_string(),
                    "Modules: snake_case (e.g., my_module)".to_string(),
                ],
                error_handling: Some(
                    "Use Result<T, E> and thiserror for custom errors".to_string(),
                ),
                import_style: Some(
                    "Group imports: std -> external -> crate (use rustfmt)".to_string(),
                ),
                formatting_rules: vec!["Use rustfmt for consistent formatting".to_string()],
            },
            ..Default::default()
        }
    }

    /// Create a Node.js project knowledge with sensible defaults
    pub fn nodejs_project(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            project_type: ProjectType::NodeJs,
            build_config: BuildConfig {
                build_cmd: Some("npm run build".to_string()),
                test_cmd: Some("npm test".to_string()),
                single_test_cmd: Some("npm test -- <test_name>".to_string()),
                lint_cmd: Some("npm run lint".to_string()),
                format_cmd: Some("npm run format".to_string()),
            },
            code_style: CodeStyle {
                naming_conventions: vec![
                    "Types/Interfaces: PascalCase (e.g., MyInterface)".to_string(),
                    "Functions/variables: camelCase (e.g., myFunction)".to_string(),
                    "Constants: SCREAMING_SNAKE_CASE (e.g., MAX_RETRIES)".to_string(),
                    "Files: kebab-case (e.g., my-component.tsx)".to_string(),
                ],
                error_handling: Some("Use try/catch and custom error classes".to_string()),
                import_style: Some("Use ES modules with explicit file extensions".to_string()),
                formatting_rules: vec!["Use Prettier for consistent formatting".to_string()],
            },
            ..Default::default()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn project_knowledge_default() {
        let knowledge = ProjectKnowledge::default();
        assert!(knowledge.name.is_empty());
        assert_eq!(knowledge.project_type, ProjectType::Unknown);
    }

    #[test]
    fn rust_project_has_defaults() {
        let knowledge = ProjectKnowledge::rust_project("my-rust-app");
        assert_eq!(knowledge.name, "my-rust-app");
        assert_eq!(knowledge.project_type, ProjectType::Rust);
        assert!(knowledge.build_config.build_cmd.is_some());
        assert!(knowledge.build_config.test_cmd.is_some());
    }

    #[test]
    fn nodejs_project_has_defaults() {
        let knowledge = ProjectKnowledge::nodejs_project("my-node-app");
        assert_eq!(knowledge.name, "my-node-app");
        assert_eq!(knowledge.project_type, ProjectType::NodeJs);
        assert!(knowledge.build_config.build_cmd.is_some());
    }
}
