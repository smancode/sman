//! Settings persistence store for SmanClaw Desktop

use anyhow::Result;
use smanclaw_types::{AppSettings, EmbeddingSettings, LlmSettings, QdrantSettings};
use std::fs;
use std::path::PathBuf;

/// Store for application settings with persistence support
pub struct SettingsStore {
    /// Configuration directory path
    config_dir: PathBuf,
    /// Settings file path
    settings_file: PathBuf,
}

impl SettingsStore {
    /// Service name for keyring storage
    const KEYRING_SERVICE: &'static str = "smanclaw-desktop";

    /// Create a new settings store
    pub fn new(config_dir: PathBuf) -> Result<Self> {
        fs::create_dir_all(&config_dir)?;
        let settings_file = config_dir.join("settings.json");
        Ok(Self {
            config_dir,
            settings_file,
        })
    }

    /// Get the default config directory
    pub fn default_config_dir() -> PathBuf {
        dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("smanclaw")
    }

    /// Load settings from disk
    pub fn load(&self) -> Result<AppSettings> {
        if !self.settings_file.exists() {
            return Ok(AppSettings::default());
        }

        // Load settings directly from file (contains full API keys)
        let content = fs::read_to_string(&self.settings_file)?;
        let settings: AppSettings = serde_json::from_str(&content)?;

        Ok(settings)
    }

    /// Save settings to disk
    pub fn save(&self, settings: &AppSettings) -> Result<()> {
        // Save API keys to secure storage (optional, for backwards compatibility)
        // Now we also save the full key to settings.json for direct access
        if !settings.llm.api_key.is_empty() {
            let _ = self.save_secure_key("llm", &settings.llm.api_key);
        }

        if let Some(ref emb) = settings.embedding {
            if !emb.api_key.is_empty() {
                let _ = self.save_secure_key("embedding", &emb.api_key);
            }
        }

        if let Some(ref qd) = settings.qdrant {
            if let Some(ref key) = qd.api_key {
                if !key.is_empty() {
                    let _ = self.save_secure_key("qdrant", key);
                }
            }
        }

        // Save settings to file (with full API keys, not masked)
        let content = serde_json::to_string_pretty(settings)?;
        fs::write(&self.settings_file, content)?;

        Ok(())
    }

    /// Load a key from secure storage (keyring)
    fn load_secure_key(&self, service_suffix: &str) -> Result<String> {
        let service = format!("{}:{}", Self::KEYRING_SERVICE, service_suffix);

        // Try keyring first, fall back to env var for development
        #[cfg(feature = "keyring")]
        {
            use keyring::Entry;
            let entry = Entry::new(&service, "api_key")?;
            return entry.get_password().map_err(Into::into);
        }

        #[cfg(not(feature = "keyring"))]
        {
            // Fallback: read from env var or file
            let env_key = format!("SMANCLAW_{}_KEY", service_suffix.to_uppercase());
            if let Ok(key) = std::env::var(&env_key) {
                return Ok(key);
            }

            // Try to read from secure file
            let key_file = self.config_dir.join(format!(".{}_key", service_suffix));
            if key_file.exists() {
                return Ok(fs::read_to_string(key_file)?.trim().to_string());
            }

            anyhow::bail!("No secure key found for {}", service_suffix)
        }
    }

    /// Save a key to secure storage (keyring)
    fn save_secure_key(&self, service_suffix: &str, key: &str) -> Result<()> {
        let service = format!("{}:{}", Self::KEYRING_SERVICE, service_suffix);

        #[cfg(feature = "keyring")]
        {
            use keyring::Entry;
            let entry = Entry::new(&service, "api_key")?;
            entry.set_password(key)?;
            return Ok(());
        }

        #[cfg(not(feature = "keyring"))]
        {
            // Fallback: save to secure file (restricted permissions)
            let key_file = self.config_dir.join(format!(".{}_key", service_suffix));
            fs::write(&key_file, key)?;

            // Set restrictive permissions on Unix
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&key_file, fs::Permissions::from_mode(0o600))?;
            }

            Ok(())
        }
    }

    /// Check if settings exist
    pub fn exists(&self) -> bool {
        self.settings_file.exists()
    }

    /// Get the settings file path
    pub fn settings_path(&self) -> &std::path::Path {
        &self.settings_file
    }

    /// Delete all stored settings (including secure keys)
    pub fn clear(&self) -> Result<()> {
        if self.settings_file.exists() {
            fs::remove_file(&self.settings_file)?;
        }

        // Clear secure keys
        for suffix in ["llm", "embedding", "qdrant"] {
            let _ = self.delete_secure_key(suffix);
        }

        Ok(())
    }

    /// Delete a key from secure storage
    fn delete_secure_key(&self, service_suffix: &str) -> Result<()> {
        #[cfg(feature = "keyring")]
        {
            use keyring::Entry;
            let service = format!("{}:{}", Self::KEYRING_SERVICE, service_suffix);
            let entry = Entry::new(&service, "api_key")?;
            let _ = entry.delete_credential();
        }

        #[cfg(not(feature = "keyring"))]
        {
            let key_file = self.config_dir.join(format!(".{}_key", service_suffix));
            if key_file.exists() {
                fs::remove_file(key_file)?;
            }
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn settings_store_create() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SettingsStore::new(temp_dir.path().to_path_buf());
        assert!(store.is_ok());
    }

    #[test]
    fn settings_store_load_default() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SettingsStore::new(temp_dir.path().to_path_buf()).expect("create store");

        let settings = store.load().expect("load settings");
        assert!(!settings.llm.is_configured());
    }

    #[test]
    fn settings_store_save_and_load() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SettingsStore::new(temp_dir.path().to_path_buf()).expect("create store");

        let mut settings = AppSettings {
            llm: LlmSettings {
                api_url: "https://api.example.com/v1".to_string(),
                api_key: "test-api-key-12345".to_string(),
                default_model: "gpt-4".to_string(),
            },
            embedding: None,
            qdrant: None,
        };

        store.save(&settings).expect("save settings");

        // Load should restore the API key from secure storage
        let loaded = store.load().expect("load settings");
        assert_eq!(loaded.llm.api_url, settings.llm.api_url);
        // API key should be restored from secure storage
        assert_eq!(loaded.llm.api_key, "test-api-key-12345");
    }

    #[test]
    fn settings_store_exists() {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SettingsStore::new(temp_dir.path().to_path_buf()).expect("create store");

        assert!(!store.exists());

        let settings = AppSettings::default();
        store.save(&settings).expect("save");

        assert!(store.exists());
    }
}
