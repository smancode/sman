//! Application settings types for SmanClaw Desktop

use serde::{Deserialize, Serialize};

/// Application global settings (persisted)
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AppSettings {
    /// LLM configuration
    pub llm: LlmSettings,
    /// Embedding configuration (optional)
    pub embedding: Option<EmbeddingSettings>,
    /// Qdrant vector store configuration (optional)
    pub qdrant: Option<QdrantSettings>,
}

/// LLM provider settings (OpenAI Compatible)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LlmSettings {
    /// API Base URL (OpenAI Compatible endpoint)
    pub api_url: String,
    /// API Key for authentication
    pub api_key: String,
    /// Default model to use
    pub default_model: String,
}

impl Default for LlmSettings {
    fn default() -> Self {
        Self {
            api_url: String::new(),
            api_key: String::new(),
            default_model: String::new(),
        }
    }
}

impl LlmSettings {
    /// Check if LLM settings are configured
    pub fn is_configured(&self) -> bool {
        !self.api_url.is_empty() && !self.api_key.is_empty() && !self.default_model.is_empty()
    }

    /// Get masked API key for display (show first 4 and last 4 chars)
    pub fn masked_api_key(&self) -> String {
        if self.api_key.len() <= 8 {
            return "*".repeat(self.api_key.len());
        }
        let first: String = self.api_key.chars().take(4).collect();
        let last: String = self.api_key.chars().rev().take(4).collect();
        format!("{}****{}", first, last.chars().rev().collect::<String>())
    }
}

/// Embedding provider settings
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingSettings {
    /// Embedding API URL
    pub api_url: String,
    /// Embedding API Key
    pub api_key: String,
    /// Embedding model name
    pub model: String,
    /// Vector dimensions
    pub dimensions: usize,
}

impl Default for EmbeddingSettings {
    fn default() -> Self {
        Self {
            api_url: String::new(),
            api_key: String::new(),
            model: "text-embedding-3-small".to_string(),
            dimensions: 1536,
        }
    }
}

impl EmbeddingSettings {
    /// Check if embedding settings are configured
    pub fn is_configured(&self) -> bool {
        !self.api_url.is_empty() && !self.api_key.is_empty() && !self.model.is_empty()
    }

    /// Get masked API key for display
    pub fn masked_api_key(&self) -> String {
        if self.api_key.len() <= 8 {
            return "*".repeat(self.api_key.len());
        }
        let first: String = self.api_key.chars().take(4).collect();
        let last: String = self.api_key.chars().rev().take(4).collect();
        format!("{}****{}", first, last.chars().rev().collect::<String>())
    }
}

/// Qdrant vector store settings
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QdrantSettings {
    /// Qdrant server URL
    pub url: String,
    /// Collection name
    pub collection: String,
    /// API Key (optional, for Qdrant Cloud or secured instances)
    pub api_key: Option<String>,
}

impl Default for QdrantSettings {
    fn default() -> Self {
        Self {
            url: String::new(),
            collection: "smanclaw_memories".to_string(),
            api_key: None,
        }
    }
}

impl QdrantSettings {
    /// Check if Qdrant settings are configured
    pub fn is_configured(&self) -> bool {
        !self.url.is_empty()
    }
}

/// Connection test result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionTestResult {
    /// Whether the connection was successful
    pub success: bool,
    /// Error message if failed
    pub error: Option<String>,
    /// Latency in milliseconds
    pub latency_ms: Option<u64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn llm_settings_is_configured() {
        let settings = LlmSettings {
            api_url: "https://api.example.com/v1".to_string(),
            api_key: "sk-test-key".to_string(),
            default_model: "gpt-4".to_string(),
        };
        assert!(settings.is_configured());

        let empty = LlmSettings::default();
        assert!(!empty.is_configured());
    }

    #[test]
    fn llm_settings_masked_api_key() {
        let settings = LlmSettings {
            api_url: "https://api.example.com/v1".to_string(),
            api_key: "sk-1234567890abcdefghijklmnop".to_string(),
            default_model: "gpt-4".to_string(),
        };
        assert_eq!(settings.masked_api_key(), "sk-1****mnop");

        let short = LlmSettings {
            api_url: String::new(),
            api_key: "short".to_string(),
            default_model: String::new(),
        };
        assert_eq!(short.masked_api_key(), "*****");
    }

    #[test]
    fn embedding_settings_default() {
        let settings = EmbeddingSettings::default();
        assert_eq!(settings.model, "text-embedding-3-small");
        assert_eq!(settings.dimensions, 1536);
    }

    #[test]
    fn qdrant_settings_default() {
        let settings = QdrantSettings::default();
        assert_eq!(settings.collection, "smanclaw_memories");
        assert!(settings.api_key.is_none());
    }

    #[test]
    fn app_settings_serialization() {
        let settings = AppSettings {
            llm: LlmSettings {
                api_url: "https://api.example.com/v1".to_string(),
                api_key: "test-key".to_string(),
                default_model: "gpt-4".to_string(),
            },
            embedding: Some(EmbeddingSettings {
                api_url: "https://embedding.example.com/v1".to_string(),
                api_key: "emb-key".to_string(),
                model: "text-embedding-3-small".to_string(),
                dimensions: 1536,
            }),
            qdrant: None,
        };

        let json = serde_json::to_string(&settings).expect("serialize");
        let deserialized: AppSettings = serde_json::from_str(&json).expect("deserialize");

        assert_eq!(settings.llm.api_url, deserialized.llm.api_url);
        assert!(deserialized.embedding.is_some());
        assert!(deserialized.qdrant.is_none());
    }
}
