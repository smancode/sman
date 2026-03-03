//! Project-related types

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// Unique project identifier
pub type ProjectId = String;

/// A project represents a codebase that ZeroClaw can work on
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Project {
    /// Unique project identifier
    pub id: ProjectId,
    /// Project name
    pub name: String,
    /// Absolute path to project directory
    pub path: String,
    /// Creation timestamp
    pub created_at: DateTime<Utc>,
    /// Last accessed timestamp
    pub last_accessed: DateTime<Utc>,
}

/// Project configuration
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProjectConfig {
    /// Project ID
    pub project_id: ProjectId,
    /// Default provider for this project
    pub default_provider: Option<String>,
    /// Default model for this project
    pub default_model: Option<String>,
}

impl Default for ProjectConfig {
    fn default() -> Self {
        Self {
            project_id: String::new(),
            default_provider: None,
            default_model: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn project_serialization() {
        let project = Project {
            id: "proj-123".to_string(),
            name: "my-app".to_string(),
            path: "/home/user/projects/my-app".to_string(),
            created_at: Utc::now(),
            last_accessed: Utc::now(),
        };

        let json = serde_json::to_string(&project).expect("serialize");
        let deserialized: Project = serde_json::from_str(&json).expect("deserialize");

        assert_eq!(project, deserialized);
    }

    #[test]
    fn project_config_default() {
        let config = ProjectConfig::default();
        assert!(config.default_provider.is_none());
        assert!(config.default_model.is_none());
    }
}
