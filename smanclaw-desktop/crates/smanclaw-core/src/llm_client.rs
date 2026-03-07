//! LLM Client abstraction layer for SmanClaw
//!
//! This module provides a unified interface for LLM calls, designed to be
//! implemented by wrapping ZeroClaw's Provider trait or mock implementations
//! for testing.

use std::sync::Arc;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};

/// Role of a message in the conversation
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MessageRole {
    System,
    User,
    Assistant,
    Tool,
}

impl Default for MessageRole {
    fn default() -> Self {
        Self::User
    }
}

impl std::fmt::Display for MessageRole {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MessageRole::System => write!(f, "system"),
            MessageRole::User => write!(f, "user"),
            MessageRole::Assistant => write!(f, "assistant"),
            MessageRole::Tool => write!(f, "tool"),
        }
    }
}

/// A single message in the conversation
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    /// Role of the message sender
    pub role: MessageRole,
    /// Content of the message
    pub content: String,
    /// Optional name (for tool messages)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

impl ChatMessage {
    /// Create a new message with the given role and content
    pub fn new(role: MessageRole, content: impl Into<String>) -> Self {
        Self {
            role,
            content: content.into(),
            name: None,
        }
    }

    /// Create a system message
    pub fn system(content: impl Into<String>) -> Self {
        Self::new(MessageRole::System, content)
    }

    /// Create a user message
    pub fn user(content: impl Into<String>) -> Self {
        Self::new(MessageRole::User, content)
    }

    /// Create an assistant message
    pub fn assistant(content: impl Into<String>) -> Self {
        Self::new(MessageRole::Assistant, content)
    }

    /// Add a name to the message (for tool messages)
    pub fn with_name(mut self, name: impl Into<String>) -> Self {
        self.name = Some(name.into());
        self
    }
}

/// Token usage statistics
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Usage {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    pub total_tokens: u32,
}

/// Request for LLM completion
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompletionRequest {
    /// Messages in the conversation
    pub messages: Vec<ChatMessage>,
    /// Model to use (optional, uses default if not specified)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    /// Temperature for sampling (0.0 - 2.0)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f32>,
    /// Maximum tokens to generate
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<u32>,
    /// Stop sequences
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub stop: Vec<String>,
    /// Whether to stream the response
    #[serde(default)]
    pub stream: bool,
}

impl Default for CompletionRequest {
    fn default() -> Self {
        Self {
            messages: Vec::new(),
            model: None,
            temperature: Some(0.7),
            max_tokens: None,
            stop: Vec::new(),
            stream: false,
        }
    }
}

impl CompletionRequest {
    /// Create a new request with a single user message
    pub fn from_user(content: impl Into<String>) -> Self {
        Self {
            messages: vec![ChatMessage::user(content)],
            ..Default::default()
        }
    }

    /// Add a system message
    pub fn with_system(mut self, content: impl Into<String>) -> Self {
        self.messages.insert(0, ChatMessage::system(content));
        self
    }

    /// Add a message
    pub fn with_message(mut self, message: ChatMessage) -> Self {
        self.messages.push(message);
        self
    }

    /// Set the model
    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.model = Some(model.into());
        self
    }

    /// Set the temperature
    pub fn with_temperature(mut self, temperature: f32) -> Self {
        self.temperature = Some(temperature);
        self
    }

    /// Set max tokens
    pub fn with_max_tokens(mut self, max_tokens: u32) -> Self {
        self.max_tokens = Some(max_tokens);
        self
    }

    /// Enable streaming
    pub fn with_stream(mut self, stream: bool) -> Self {
        self.stream = stream;
        self
    }
}

/// Response from LLM completion
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompletionResponse {
    /// Generated content
    pub content: String,
    /// Model used
    pub model: String,
    /// Token usage
    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage: Option<Usage>,
    /// Finish reason (e.g., "stop", "length", "tool_calls")
    #[serde(skip_serializing_if = "Option::is_none")]
    pub finish_reason: Option<String>,
    /// Whether the response was truncated
    #[serde(default)]
    pub truncated: bool,
}

impl CompletionResponse {
    /// Create a new response with the given content
    pub fn new(content: impl Into<String>, model: impl Into<String>) -> Self {
        Self {
            content: content.into(),
            model: model.into(),
            usage: None,
            finish_reason: Some("stop".to_string()),
            truncated: false,
        }
    }

    /// Add usage information
    pub fn with_usage(mut self, usage: Usage) -> Self {
        self.usage = Some(usage);
        self
    }

    /// Set finish reason
    pub fn with_finish_reason(mut self, reason: impl Into<String>) -> Self {
        self.finish_reason = Some(reason.into());
        self
    }
}

/// LLM Client trait for completion requests
///
/// This trait abstracts the LLM call interface, allowing different
/// implementations (ZeroClaw Provider wrapper, mock, etc.)
#[async_trait]
pub trait LLMClient: Send + Sync {
    /// Execute a completion request
    async fn complete(&self, request: CompletionRequest) -> Result<CompletionResponse>;

    /// Execute a simple one-shot chat
    async fn chat(&self, message: &str) -> Result<String> {
        let request = CompletionRequest::from_user(message);
        let response = self.complete(request).await?;
        Ok(response.content)
    }

    /// Execute a chat with system prompt
    async fn chat_with_system(&self, system: &str, message: &str) -> Result<String> {
        let request = CompletionRequest::from_user(message).with_system(system);
        let response = self.complete(request).await?;
        Ok(response.content)
    }

    /// Get the model name (if configured)
    fn model_name(&self) -> Option<&str> {
        None
    }
}

/// Mock LLM Client for testing
pub struct MockLLMClient {
    response: String,
    should_fail: bool,
    model: String,
}

impl MockLLMClient {
    /// Create a mock client that returns the given response
    pub fn new(response: impl Into<String>) -> Self {
        Self {
            response: response.into(),
            should_fail: false,
            model: "mock-model".to_string(),
        }
    }

    /// Create a mock client that fails
    pub fn failing() -> Self {
        Self {
            response: String::new(),
            should_fail: true,
            model: "mock-model".to_string(),
        }
    }

    /// Set the model name
    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.model = model.into();
        self
    }
}

#[async_trait]
impl LLMClient for MockLLMClient {
    async fn complete(&self, _request: CompletionRequest) -> Result<CompletionResponse> {
        if self.should_fail {
            return Err(CoreError::LLMError("Mock LLM client failed".to_string()));
        }
        Ok(CompletionResponse::new(&self.response, &self.model))
    }

    fn model_name(&self) -> Option<&str> {
        Some(&self.model)
    }
}

/// Configuration for creating an LLM client
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LLMClientConfig {
    /// API URL
    pub api_url: String,
    /// API Key
    pub api_key: String,
    /// Default model
    pub default_model: String,
    /// Temperature
    #[serde(default = "default_temperature")]
    pub temperature: f32,
    /// Max tokens
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<u32>,
}

fn default_temperature() -> f32 {
    0.7
}

impl Default for LLMClientConfig {
    fn default() -> Self {
        Self {
            api_url: String::new(),
            api_key: String::new(),
            default_model: "gpt-4".to_string(),
            temperature: 0.7,
            max_tokens: None,
        }
    }
}

impl LLMClientConfig {
    /// Check if the configuration is valid
    pub fn is_configured(&self) -> bool {
        !self.api_url.is_empty() && !self.api_key.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // MessageRole tests
    #[test]
    fn test_message_role_display() {
        assert_eq!(MessageRole::System.to_string(), "system");
        assert_eq!(MessageRole::User.to_string(), "user");
        assert_eq!(MessageRole::Assistant.to_string(), "assistant");
        assert_eq!(MessageRole::Tool.to_string(), "tool");
    }

    #[test]
    fn test_message_role_default() {
        assert_eq!(MessageRole::default(), MessageRole::User);
    }

    // ChatMessage tests
    #[test]
    fn test_chat_message_new() {
        let msg = ChatMessage::new(MessageRole::User, "Hello");
        assert_eq!(msg.role, MessageRole::User);
        assert_eq!(msg.content, "Hello");
        assert!(msg.name.is_none());
    }

    #[test]
    fn test_chat_message_system() {
        let msg = ChatMessage::system("You are helpful");
        assert_eq!(msg.role, MessageRole::System);
        assert_eq!(msg.content, "You are helpful");
    }

    #[test]
    fn test_chat_message_user() {
        let msg = ChatMessage::user("Hi");
        assert_eq!(msg.role, MessageRole::User);
        assert_eq!(msg.content, "Hi");
    }

    #[test]
    fn test_chat_message_assistant() {
        let msg = ChatMessage::assistant("Hello!");
        assert_eq!(msg.role, MessageRole::Assistant);
        assert_eq!(msg.content, "Hello!");
    }

    #[test]
    fn test_chat_message_with_name() {
        let msg = ChatMessage::user("Hi").with_name("tool_name");
        assert_eq!(msg.name, Some("tool_name".to_string()));
    }

    #[test]
    fn test_chat_message_serialization() {
        let msg = ChatMessage::user("Hello");
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"role\":\"user\""));
        assert!(json.contains("\"content\":\"Hello\""));
    }

    // CompletionRequest tests
    #[test]
    fn test_completion_request_default() {
        let req = CompletionRequest::default();
        assert!(req.messages.is_empty());
        assert!(req.model.is_none());
        assert_eq!(req.temperature, Some(0.7));
        assert!(req.max_tokens.is_none());
        assert!(!req.stream);
    }

    #[test]
    fn test_completion_request_from_user() {
        let req = CompletionRequest::from_user("Hello");
        assert_eq!(req.messages.len(), 1);
        assert_eq!(req.messages[0].role, MessageRole::User);
        assert_eq!(req.messages[0].content, "Hello");
    }

    #[test]
    fn test_completion_request_with_system() {
        let req = CompletionRequest::from_user("Hello").with_system("Be helpful");
        assert_eq!(req.messages.len(), 2);
        assert_eq!(req.messages[0].role, MessageRole::System);
        assert_eq!(req.messages[1].role, MessageRole::User);
    }

    #[test]
    fn test_completion_request_with_model() {
        let req = CompletionRequest::from_user("Hello").with_model("gpt-4");
        assert_eq!(req.model, Some("gpt-4".to_string()));
    }

    #[test]
    fn test_completion_request_with_temperature() {
        let req = CompletionRequest::from_user("Hello").with_temperature(0.5);
        assert_eq!(req.temperature, Some(0.5));
    }

    #[test]
    fn test_completion_request_with_max_tokens() {
        let req = CompletionRequest::from_user("Hello").with_max_tokens(1000);
        assert_eq!(req.max_tokens, Some(1000));
    }

    #[test]
    fn test_completion_request_with_stream() {
        let req = CompletionRequest::from_user("Hello").with_stream(true);
        assert!(req.stream);
    }

    // CompletionResponse tests
    #[test]
    fn test_completion_response_new() {
        let resp = CompletionResponse::new("Hello back", "gpt-4");
        assert_eq!(resp.content, "Hello back");
        assert_eq!(resp.model, "gpt-4");
        assert!(resp.usage.is_none());
        assert_eq!(resp.finish_reason, Some("stop".to_string()));
        assert!(!resp.truncated);
    }

    #[test]
    fn test_completion_response_with_usage() {
        let usage = Usage {
            prompt_tokens: 10,
            completion_tokens: 5,
            total_tokens: 15,
        };
        let resp = CompletionResponse::new("Hello", "gpt-4").with_usage(usage.clone());
        assert_eq!(resp.usage, Some(usage));
    }

    #[test]
    fn test_completion_response_with_finish_reason() {
        let resp = CompletionResponse::new("Hello", "gpt-4").with_finish_reason("length");
        assert_eq!(resp.finish_reason, Some("length".to_string()));
    }

    // Usage tests
    #[test]
    fn test_usage_default() {
        let usage = Usage::default();
        assert_eq!(usage.prompt_tokens, 0);
        assert_eq!(usage.completion_tokens, 0);
        assert_eq!(usage.total_tokens, 0);
    }

    // MockLLMClient tests
    #[test]
    fn test_mock_llm_client_new() {
        let client = MockLLMClient::new("Test response");
        assert_eq!(client.response, "Test response");
        assert!(!client.should_fail);
    }

    #[test]
    fn test_mock_llm_client_failing() {
        let client = MockLLMClient::failing();
        assert!(client.should_fail);
    }

    #[test]
    fn test_mock_llm_client_with_model() {
        let client = MockLLMClient::new("Test").with_model("custom-model");
        assert_eq!(client.model, "custom-model");
    }

    #[tokio::test]
    async fn test_mock_llm_client_complete_success() {
        let client = MockLLMClient::new("Test response");
        let request = CompletionRequest::from_user("Hello");
        let response = client.complete(request).await.expect("complete");
        assert_eq!(response.content, "Test response");
        assert_eq!(response.model, "mock-model");
    }

    #[tokio::test]
    async fn test_mock_llm_client_complete_failure() {
        let client = MockLLMClient::failing();
        let request = CompletionRequest::from_user("Hello");
        let result = client.complete(request).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_mock_llm_client_chat() {
        let client = MockLLMClient::new("Response");
        let result = client.chat("Hello").await.expect("chat");
        assert_eq!(result, "Response");
    }

    #[tokio::test]
    async fn test_mock_llm_client_chat_with_system() {
        let client = MockLLMClient::new("Response");
        let result = client
            .chat_with_system("Be helpful", "Hello")
            .await
            .expect("chat");
        assert_eq!(result, "Response");
    }

    #[test]
    fn test_mock_llm_client_model_name() {
        let client = MockLLMClient::new("Test");
        assert_eq!(client.model_name(), Some("mock-model"));
    }

    // LLMClientConfig tests
    #[test]
    fn test_llm_client_config_default() {
        let config = LLMClientConfig::default();
        assert!(config.api_url.is_empty());
        assert!(config.api_key.is_empty());
        assert_eq!(config.default_model, "gpt-4");
        assert_eq!(config.temperature, 0.7);
        assert!(config.max_tokens.is_none());
    }

    #[test]
    fn test_llm_client_config_is_configured() {
        let config = LLMClientConfig::default();
        assert!(!config.is_configured());

        let config = LLMClientConfig {
            api_url: "https://api.example.com".to_string(),
            api_key: "key".to_string(),
            ..Default::default()
        };
        assert!(config.is_configured());
    }
}
