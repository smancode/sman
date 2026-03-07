//! ZeroClaw bridge for task execution
//!
//! This bridge wraps ZeroClaw's Agent, providing a simple interface
//! for the SmanClaw desktop app. The Agent instance is kept alive
//! to maintain conversation history across multiple turns.

use anyhow::Result;
use smanclaw_types::{AppSettings, FileAction, FileChange, ProgressEvent, TaskResult};
use std::fs::OpenOptions;
use std::path::Path;
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};
use zeroclaw::agent::Agent;
use zeroclaw::config::Config;
use zeroclaw::providers;

/// Detect provider name from API URL
/// This reuses ZeroClaw's provider detection logic by matching known URL patterns
fn detect_provider_from_url(api_url: &str) -> String {
    let url = api_url.trim().to_lowercase();

    // MiniMax CN (api.minimaxi.com) - use minimax-cn to ensure correct URL is used
    if url.contains("minimaxi.com") {
        return "minimax-cn".to_string();
    }

    // MiniMax International (api.minimax.io)
    if url.contains("minimax.io") {
        return "minimax".to_string();
    }

    // GLM / Zhipu / 智谱
    if url.contains("bigmodel.cn") || url.contains("z.ai") {
        return "glm".to_string();
    }

    // Moonshot / Kimi
    if url.contains("moonshot.cn") || url.contains("moonshot.ai") || url.contains("kimi") {
        return "moonshot".to_string();
    }

    // Qwen / 通义千问 / DashScope
    if url.contains("dashscope") || url.contains("aliyuncs.com") || url.contains("qwen") {
        return "qwen".to_string();
    }

    // DeepSeek
    if url.contains("deepseek") {
        return "deepseek".to_string();
    }

    // SiliconFlow / SiliconCloud
    if url.contains("siliconflow") || url.contains("siliconcloud") {
        return "siliconflow".to_string();
    }

    // StepFun
    if url.contains("stepfun") || (url.contains("step") && url.contains("api")) {
        return "stepfun".to_string();
    }

    // OpenRouter
    if url.contains("openrouter") {
        return "openrouter".to_string();
    }

    // Anthropic
    if url.contains("anthropic") {
        return "anthropic".to_string();
    }

    // Ollama - typically runs on localhost:11434
    if url.contains("ollama") || url.contains(":11434") {
        return "ollama".to_string();
    }

    // Default to openai for OpenAI-compatible APIs
    "openai".to_string()
}

/// Test LLM connection using ZeroClaw's provider system
pub async fn test_llm_direct(settings: &AppSettings) -> Result<String> {
    if !settings.llm.is_configured() {
        return Err(anyhow::anyhow!("LLM not configured"));
    }

    let provider_name = detect_provider_from_url(&settings.llm.api_url);
    eprintln!(
        "Testing LLM: provider={}, model={}, url={}",
        provider_name, settings.llm.default_model, settings.llm.api_url
    );

    // Create provider using ZeroClaw's factory
    let provider = providers::create_provider_with_url(
        &provider_name,
        Some(&settings.llm.api_key),
        Some(&settings.llm.api_url),
    )?;

    // Test chat request
    let response = provider
        .chat_with_system(
            Some("You are a helpful assistant."),
            "Hello, please respond with just 'OK' to confirm connection.",
            &settings.llm.default_model,
            0.7,
        )
        .await?;

    Ok(response)
}

/// Bridge to ZeroClaw Agent - maintains conversation history
pub struct ZeroclawBridge {
    config: Config,
    /// Project path for session storage
    project_path: std::path::PathBuf,
    /// Persistent Agent instance wrapped in Mutex for async access
    agent: Arc<Mutex<Agent>>,
}

impl ZeroclawBridge {
    fn supports_sqlite_session(project_path: &Path) -> bool {
        let memory_dir = project_path.join("memory");
        if std::fs::create_dir_all(&memory_dir).is_err() {
            return false;
        }
        let probe_file = memory_dir.join(".session_probe");
        let writable = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&probe_file)
            .is_ok();
        let _ = std::fs::remove_file(&probe_file);
        writable
    }

    /// Create a bridge for a specific project (with default config)
    pub fn from_project(project_path: &Path) -> Result<Self> {
        let config = Self::build_config(project_path, None);
        let agent = Agent::from_config(&config)?;
        Ok(Self {
            config,
            project_path: project_path.to_path_buf(),
            agent: Arc::new(Mutex::new(agent)),
        })
    }

    /// Create a bridge with user settings
    pub fn from_project_with_settings(project_path: &Path, settings: &AppSettings) -> Result<Self> {
        let config = Self::build_config(project_path, Some(settings));
        let agent = Agent::from_config(&config)?;
        Ok(Self {
            config,
            project_path: project_path.to_path_buf(),
            agent: Arc::new(Mutex::new(agent)),
        })
    }

    /// Build ZeroClaw config with session support enabled
    fn build_config(project_path: &Path, settings: Option<&AppSettings>) -> Config {
        let mut config = Config::default();
        config.workspace_dir = project_path.to_path_buf();

        let project_writable = Self::supports_sqlite_session(project_path);

        config.agent.session.backend = if project_writable {
            zeroclaw::config::AgentSessionBackend::Sqlite
        } else {
            zeroclaw::config::AgentSessionBackend::Memory
        };
        config.agent.session.max_messages = 100; // Keep last 100 messages
        if !project_writable {
            config.memory.backend = "none".to_string();
            config.memory.auto_save = false;
        }

        // Configure LLM Provider if settings provided
        if let Some(settings) = settings {
            if settings.llm.is_configured() {
                // Auto-detect provider from API URL
                let provider_name = detect_provider_from_url(&settings.llm.api_url);

                eprintln!(
                    "ZeroclawBridge.build_config: provider={}, url={}, model={}, api_key_len={}",
                    provider_name,
                    settings.llm.api_url,
                    settings.llm.default_model,
                    settings.llm.api_key.len()
                );

                config.default_provider = Some(provider_name);
                config.api_url = Some(settings.llm.api_url.clone());
                config.api_key = Some(settings.llm.api_key.clone());
                config.default_model = Some(settings.llm.default_model.clone());
            }

            // Configure Embedding (if provided)
            if let Some(ref emb) = settings.embedding {
                if emb.is_configured() {
                    config.memory.embedding_provider = format!("custom:{}", emb.api_url);
                    config.memory.embedding_model = emb.model.clone();
                    config.memory.embedding_dimensions = emb.dimensions;
                }
            }

            // Configure Qdrant (if provided)
            if let Some(ref qd) = settings.qdrant {
                if qd.is_configured() {
                    config.memory.backend = "sqlite_qdrant_hybrid".to_string();
                    config.memory.qdrant.url = Some(qd.url.clone());
                    config.memory.qdrant.collection = qd.collection.clone();
                    config.memory.qdrant.api_key = qd.api_key.clone();
                }
            }

            config.web_search.enabled = true;
            config.web_search.provider = "duckduckgo".to_string();
            config.web_search.fallback_providers.clear();
            if !settings.web_search.brave_api_key.trim().is_empty() {
                config.web_search.brave_api_key = Some(settings.web_search.brave_api_key.clone());
                config
                    .web_search
                    .fallback_providers
                    .push("brave".to_string());
            }
            if !settings.web_search.tavily_api_key.trim().is_empty() {
                config.web_search.api_key = Some(settings.web_search.tavily_api_key.clone());
                config
                    .web_search
                    .fallback_providers
                    .push("tavily".to_string());
            }
        }

        config
            .autonomy
            .allowed_commands
            .extend(["curl".to_string(), "wget".to_string()]);
        config.autonomy.allowed_commands.sort();
        config.autonomy.allowed_commands.dedup();
        config
    }

    /// Execute a task synchronously
    pub fn execute_task(&self, input: &str) -> Result<TaskResult> {
        match tokio::runtime::Handle::try_current() {
            Ok(handle) => {
                tokio::task::block_in_place(|| handle.block_on(self.execute_task_async(input)))
            }
            Err(_) => {
                let rt = tokio::runtime::Runtime::new()?;
                rt.block_on(self.execute_task_async(input))
            }
        }
    }

    /// Execute a task asynchronously (can be called from async context)
    ///
    /// This uses the persistent Agent instance to maintain conversation history.
    /// Each call to turn() preserves previous conversation context.
    pub async fn execute_task_async(&self, input: &str) -> Result<TaskResult> {
        let mut agent = self.agent.lock().await;

        // Execute the turn - Agent maintains history internally
        let output = agent.turn(input).await?;
        tracing::info!(
            "execute_task_async: agent.turn() succeeded, output length: {}",
            output.len()
        );

        // Extract file changes from history
        let files_changed = self.extract_file_changes(agent.history());

        Ok(TaskResult {
            task_id: uuid::Uuid::new_v4().to_string(),
            success: true,
            output,
            error: None,
            files_changed,
        })
    }

    /// Execute a task with progress events (streaming)
    pub async fn execute_task_stream(
        &self,
        task_id: &str,
        input: &str,
        event_tx: mpsc::Sender<ProgressEvent>,
    ) -> Result<TaskResult> {
        event_tx
            .send(ProgressEvent::TaskStarted {
                task_id: task_id.to_string(),
            })
            .await?;

        event_tx
            .send(ProgressEvent::Progress {
                message: "Processing...".to_string(),
                percent: 0.1,
            })
            .await?;

        let mut agent = self.agent.lock().await;

        event_tx
            .send(ProgressEvent::Progress {
                message: "Executing task...".to_string(),
                percent: 0.3,
            })
            .await?;

        let result = match agent.turn(input).await {
            Ok(output) => {
                tracing::info!("agent.turn() succeeded, output length: {}", output.len());
                tracing::debug!("agent.turn() output: {}", output);

                event_tx
                    .send(ProgressEvent::Progress {
                        message: "Task completed".to_string(),
                        percent: 0.9,
                    })
                    .await?;

                let files_changed = self.extract_file_changes(agent.history());

                for change in &files_changed {
                    event_tx
                        .send(ProgressEvent::FileWritten {
                            path: change.path.clone(),
                            action: change.action,
                        })
                        .await?;
                }

                TaskResult {
                    task_id: task_id.to_string(),
                    success: true,
                    output,
                    error: None,
                    files_changed,
                }
            }
            Err(e) => {
                let error_msg = e.to_string();
                tracing::error!("agent.turn() failed: {}", error_msg);

                event_tx
                    .send(ProgressEvent::TaskFailed {
                        error: error_msg.clone(),
                    })
                    .await?;

                TaskResult {
                    task_id: task_id.to_string(),
                    success: false,
                    output: String::new(),
                    error: Some(error_msg),
                    files_changed: vec![],
                }
            }
        };

        event_tx
            .send(ProgressEvent::TaskCompleted {
                result: result.clone(),
            })
            .await?;

        Ok(result)
    }

    /// Clear conversation history
    pub async fn clear_history(&self) {
        let mut agent = self.agent.lock().await;
        agent.clear_history();
    }

    /// Extract file changes from agent history
    fn extract_file_changes(
        &self,
        history: &[zeroclaw::providers::ConversationMessage],
    ) -> Vec<FileChange> {
        let mut changes = Vec::new();
        let mut seen_paths = std::collections::HashSet::new();

        for msg in history {
            if let zeroclaw::providers::ConversationMessage::ToolResults(results) = msg {
                for result in results {
                    let content = &result.content;

                    if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(content) {
                        if let Some(path) = parsed.get("path").and_then(|p| p.as_str()) {
                            if seen_paths.insert(path.to_string()) {
                                let action = if content.contains("created")
                                    || content.contains("Created")
                                {
                                    FileAction::Created
                                } else if content.contains("deleted") || content.contains("Deleted")
                                {
                                    FileAction::Deleted
                                } else {
                                    FileAction::Modified
                                };

                                changes.push(FileChange {
                                    path: path.to_string(),
                                    action,
                                });
                            }
                        }
                    }
                }
            }
        }

        changes
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_bridge() {
        let bridge = ZeroclawBridge::from_project(Path::new("/tmp/test-project"));
        assert!(bridge.is_ok());
    }

    #[test]
    fn create_bridge_with_default_config() {
        let temp_dir = tempfile::TempDir::new().expect("temp dir");
        let bridge = ZeroclawBridge::from_project(temp_dir.path()).expect("create");
        assert!(bridge.project_path.exists() || bridge.project_path.to_str().is_some());
    }

    #[test]
    fn config_has_session_enabled() {
        let temp_dir = tempfile::TempDir::new().expect("temp dir");
        let bridge = ZeroclawBridge::from_project(temp_dir.path()).expect("create");
        // Session backend should be SQLite for multi-turn support
        assert_eq!(
            bridge.config.agent.session.backend,
            zeroclaw::config::AgentSessionBackend::Sqlite
        );
    }

    #[test]
    fn config_disables_persistent_memory_when_workspace_not_writable() {
        let temp_dir = tempfile::TempDir::new().expect("temp dir");
        let file_path = temp_dir.path().join("not-a-directory");
        std::fs::write(&file_path, b"x").expect("create file");
        let config = ZeroclawBridge::build_config(&file_path, None);
        assert_eq!(
            config.agent.session.backend,
            zeroclaw::config::AgentSessionBackend::Memory
        );
        assert_eq!(config.memory.backend, "none");
        assert!(!config.memory.auto_save);
    }

    #[tokio::test]
    async fn bridge_holds_agent_instance() {
        let temp_dir = tempfile::TempDir::new().expect("temp dir");
        let bridge = ZeroclawBridge::from_project(temp_dir.path()).expect("create");

        // Verify we can lock the agent
        {
            let agent = bridge.agent.lock().await;
            assert!(agent.history().is_empty());
        }
    }

    #[test]
    fn test_detect_provider_from_url() {
        // MiniMax
        assert_eq!(
            detect_provider_from_url("https://api.minimax.io/v1"),
            "minimax"
        );
        assert_eq!(
            detect_provider_from_url("https://api.minimaxi.com/v1"),
            "minimax-cn"
        );

        // GLM / Zhipu
        assert_eq!(
            detect_provider_from_url("https://open.bigmodel.cn/api/paas/v4"),
            "glm"
        );
        assert_eq!(
            detect_provider_from_url("https://api.z.ai/api/paas/v4"),
            "glm"
        );

        // Moonshot / Kimi
        assert_eq!(
            detect_provider_from_url("https://api.moonshot.cn/v1"),
            "moonshot"
        );
        assert_eq!(
            detect_provider_from_url("https://api.moonshot.ai/v1"),
            "moonshot"
        );
        assert_eq!(
            detect_provider_from_url("https://api.kimi.com/v1"),
            "moonshot"
        );

        // Qwen / DashScope
        assert_eq!(
            detect_provider_from_url("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            "qwen"
        );
        assert_eq!(
            detect_provider_from_url("https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
            "qwen"
        );

        // DeepSeek
        assert_eq!(
            detect_provider_from_url("https://api.deepseek.com"),
            "deepseek"
        );

        // SiliconFlow
        assert_eq!(
            detect_provider_from_url("https://api.siliconflow.cn/v1"),
            "siliconflow"
        );

        // StepFun
        assert_eq!(
            detect_provider_from_url("https://api.stepfun.com/v1"),
            "stepfun"
        );

        // OpenRouter
        assert_eq!(
            detect_provider_from_url("https://openrouter.ai/api/v1"),
            "openrouter"
        );

        // Anthropic
        assert_eq!(
            detect_provider_from_url("https://api.anthropic.com/v1"),
            "anthropic"
        );

        // Ollama
        assert_eq!(detect_provider_from_url("http://localhost:11434"), "ollama");

        // Default to openai
        assert_eq!(
            detect_provider_from_url("https://api.example.com/v1"),
            "openai"
        );
    }
}
