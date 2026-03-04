//! User experience extraction and storage module
//!
//! This module identifies and stores user-provided experiences during conversations,
//! such as constraints, conventions, preferences, knowledge, and warnings.

use chrono::Utc;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::Result;
use crate::skill_store::{Skill, SkillMeta, SkillStore};

/// Keywords for identifying constraint experiences
const CONSTRAINT_KEYWORDS: &[&str] = &[
    "必须", "一定要", "要求", "规定", "规范要求", "不允许", "禁止", "不能", "不可以",
];

/// Keywords for identifying convention experiences
const CONVENTION_KEYWORDS: &[&str] = &[
    "我们用", "我们项目", "约定", "习惯", "风格", "命名", "格式", "写法",
];

/// Keywords for identifying preference experiences
const PREFERENCE_KEYWORDS: &[&str] = &["我喜欢", "我偏好", "建议", "最好", "推荐"];

/// Keywords for identifying knowledge experiences
const KNOWLEDGE_KEYWORDS: &[&str] = &[
    "这个模块",
    "原理是",
    "机制是",
    "底层是",
    "需要注意",
    "坑是",
    "注意点",
];

/// Keywords for identifying warning experiences
const WARNING_KEYWORDS: &[&str] = &[
    "不要用", "有 bug", "有问题", "不建议", "避免", "踩坑", "血的教训",
];

/// Experience type enumeration
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExperienceType {
    /// Constraint: "All APIs must have audit logs"
    Constraint,
    /// Convention: "We use snake_case"
    Convention,
    /// Preference: "I prefer Result over exceptions"
    Preference,
    /// Knowledge: "This module uses event sourcing"
    Knowledge,
    /// Warning: "Don't use that library, it has bugs"
    Warning,
}

impl ExperienceType {
    /// Get the Chinese display name for this experience type
    pub fn display_name(&self) -> &'static str {
        match self {
            ExperienceType::Constraint => "约束",
            ExperienceType::Convention => "规范",
            ExperienceType::Preference => "偏好",
            ExperienceType::Knowledge => "知识",
            ExperienceType::Warning => "警告",
        }
    }

    /// Get the default category path for this experience type
    pub fn default_category(&self) -> &'static str {
        match self {
            ExperienceType::Constraint => "coding/constraints",
            ExperienceType::Convention => "coding/conventions",
            ExperienceType::Preference => "coding/preferences",
            ExperienceType::Knowledge => "domain/knowledge",
            ExperienceType::Warning => "coding/warnings",
        }
    }
}

/// User experience extracted from conversation
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserExperience {
    /// Original user input text
    pub raw_text: String,
    /// Type of experience identified
    pub experience_type: ExperienceType,
    /// Category for organization (e.g., "coding", "architecture", "domain")
    pub category: String,
    /// Extracted experience content
    pub content: String,
    /// Tags for categorization and search
    pub tags: Vec<String>,
    /// Confidence score of the identification (0.0 - 1.0)
    pub confidence: f32,
    /// Unix timestamp when the experience was captured
    pub timestamp: i64,
}

impl UserExperience {
    /// Create a new user experience
    pub fn new(
        raw_text: String,
        experience_type: ExperienceType,
        content: String,
        confidence: f32,
    ) -> Self {
        let category = experience_type.default_category().to_string();
        let timestamp = Utc::now().timestamp();

        Self {
            raw_text,
            experience_type,
            category,
            content,
            tags: Vec::new(),
            confidence,
            timestamp,
        }
    }

    /// Add a tag to the experience
    pub fn with_tag(mut self, tag: impl Into<String>) -> Self {
        self.tags.push(tag.into());
        self
    }

    /// Set the category
    pub fn with_category(mut self, category: impl Into<String>) -> Self {
        self.category = category.into();
        self
    }
}

/// Extractor for identifying and storing user experiences
pub struct UserExperienceExtractor {
    skill_store: SkillStore,
}

impl UserExperienceExtractor {
    /// Create a new UserExperienceExtractor
    ///
    /// # Arguments
    /// * `skill_store` - The skill store to use for persisting experiences
    pub fn new(skill_store: SkillStore) -> Self {
        Self { skill_store }
    }

    /// Analyze user input to identify if it contains an experience
    ///
    /// # Arguments
    /// * `user_input` - The user's input text
    ///
    /// # Returns
    /// Some(UserExperience) if an experience is identified, None otherwise
    pub fn analyze(&self, user_input: &str) -> Option<UserExperience> {
        let input = user_input.trim();

        // Input must be meaningful (at least 5 characters)
        if input.len() < 5 {
            return None;
        }

        // Try to match each experience type
        if let Some((experience_type, confidence)) = self.detect_experience_type(input) {
            let content = self.extract_content(input, experience_type);
            let tags = self.extract_tags(input, experience_type);

            let experience = UserExperience::new(
                input.to_string(),
                experience_type,
                content,
                confidence,
            )
            .with_category(experience_type.default_category());

            let mut experience = experience;
            experience.tags = tags;

            return Some(experience);
        }

        None
    }

    /// Check if user input contains extractable experience
    ///
    /// # Arguments
    /// * `user_input` - The user's input text
    ///
    /// # Returns
    /// true if the input appears to contain an experience
    pub fn contains_experience(&self, user_input: &str) -> bool {
        self.analyze(user_input).is_some()
    }

    /// Store an experience as a Skill
    ///
    /// # Arguments
    /// * `experience` - The experience to store
    ///
    /// # Returns
    /// The metadata of the created skill
    pub fn store_as_skill(&self, experience: &UserExperience) -> Result<SkillMeta> {
        let id = format!("exp-{}", Uuid::new_v4());
        let path = format!(
            "{}/{}.md",
            experience.category,
            id.replace("exp-", "experience-")
        );

        let content = self.generate_skill_content(experience);

        let meta = SkillMeta {
            id: id.clone(),
            path: path.clone(),
            tags: experience.tags.clone(),
            learned_from: "user-input".to_string(),
            updated_at: experience.timestamp,
        };

        let skill = Skill {
            meta: meta.clone(),
            content,
        };

        self.skill_store.create(&skill)?;

        Ok(meta)
    }

    /// Generate a friendly confirmation message for the user
    ///
    /// # Arguments
    /// * `experience` - The identified experience
    ///
    /// # Returns
    /// A Chinese confirmation message
    pub fn generate_confirmation(&self, experience: &UserExperience) -> String {
        let type_name = experience.experience_type.display_name();
        let path = format!(
            ".skills/{}/experience-*.md",
            experience.category
        );

        format!(
            "好的，我记住了：\n\
             \u{2022} {}：{}\n\
             \u{2022} 已保存到 {}\n\
             后续开发我会自动遵守这个规范。",
            type_name,
            experience.content,
            path
        )
    }

    /// Detect the experience type from input text
    fn detect_experience_type(&self, input: &str) -> Option<(ExperienceType, f32)> {
        // Check each type in order of priority
        // Warning first (most critical)
        if let Some(confidence) = self.match_keywords(input, WARNING_KEYWORDS) {
            return Some((ExperienceType::Warning, confidence));
        }

        // Constraint (mandatory rules)
        if let Some(confidence) = self.match_keywords(input, CONSTRAINT_KEYWORDS) {
            return Some((ExperienceType::Constraint, confidence));
        }

        // Convention (team standards)
        if let Some(confidence) = self.match_keywords(input, CONVENTION_KEYWORDS) {
            return Some((ExperienceType::Convention, confidence));
        }

        // Knowledge (technical understanding)
        if let Some(confidence) = self.match_keywords(input, KNOWLEDGE_KEYWORDS) {
            return Some((ExperienceType::Knowledge, confidence));
        }

        // Preference (personal choices)
        if let Some(confidence) = self.match_keywords(input, PREFERENCE_KEYWORDS) {
            return Some((ExperienceType::Preference, confidence));
        }

        None
    }

    /// Match input against a set of keywords
    fn match_keywords(&self, input: &str, keywords: &[&str]) -> Option<f32> {
        let input_lower = input.to_lowercase();

        for keyword in keywords {
            if input_lower.contains(keyword) {
                // Base confidence on keyword length relative to input
                let keyword_weight = keyword.len() as f32 / input.len().max(1) as f32;
                let confidence = (0.7 + keyword_weight.min(0.3)).min(1.0);
                return Some(confidence);
            }
        }

        None
    }

    /// Extract the core content from the input
    fn extract_content(&self, input: &str, _experience_type: ExperienceType) -> String {
        // For now, return the trimmed input as the content
        // Future enhancement: use LLM to extract the core message
        input.trim().to_string()
    }

    /// Extract tags from the input based on experience type
    fn extract_tags(&self, input: &str, experience_type: ExperienceType) -> Vec<String> {
        let mut tags = vec![experience_type.display_name().to_string()];

        // Add category-based tags
        match experience_type {
            ExperienceType::Constraint => {
                tags.push("mandatory".to_string());
            }
            ExperienceType::Convention => {
                tags.push("standard".to_string());
            }
            ExperienceType::Preference => {
                tags.push("personal".to_string());
            }
            ExperienceType::Knowledge => {
                tags.push("technical".to_string());
            }
            ExperienceType::Warning => {
                tags.push("caution".to_string());
            }
        }

        // Try to detect domain-specific tags
        let input_lower = input.to_lowercase();
        if input_lower.contains("api") {
            tags.push("api".to_string());
        }
        if input_lower.contains("日志") || input_lower.contains("log") {
            tags.push("logging".to_string());
        }
        if input_lower.contains("测试") || input_lower.contains("test") {
            tags.push("testing".to_string());
        }
        if input_lower.contains("安全") || input_lower.contains("security") {
            tags.push("security".to_string());
        }

        tags
    }

    /// Generate markdown content for the skill file
    fn generate_skill_content(&self, experience: &UserExperience) -> String {
        let type_name = experience.experience_type.display_name();

        format!(
            "# {} - 用户经验\n\n\
             ## 原始输入\n\n\
             > {}\n\n\
             ## 经验内容\n\n\
             {}\n\n\
             ## 元信息\n\n\
             - **类型**: {}\n\
             - **分类**: {}\n\
             - **标签**: {}\n\
             - **置信度**: {:.0}%\n\
             - **记录时间**: {}\n",
            type_name,
            experience.raw_text,
            experience.content,
            type_name,
            experience.category,
            experience.tags.join(", "),
            experience.confidence * 100.0,
            Utc::now().format("%Y-%m-%d %H:%M:%S UTC")
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_test_store() -> (TempDir, SkillStore) {
        let temp_dir = TempDir::new().expect("temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create store");
        (temp_dir, store)
    }

    #[test]
    fn recognize_constraint_experience() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let input = "我们的项目要求所有 API 都要加审计日志";
        let result = extractor.analyze(input);

        assert!(result.is_some());
        let experience = result.unwrap();
        assert_eq!(experience.experience_type, ExperienceType::Constraint);
        assert!(experience.confidence > 0.0);
        assert!(experience.content.contains("API"));
    }

    #[test]
    fn recognize_convention_experience() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let input = "我们项目约定用 snake_case 命名";
        let result = extractor.analyze(input);

        assert!(result.is_some());
        let experience = result.unwrap();
        assert_eq!(experience.experience_type, ExperienceType::Convention);
        assert!(experience.confidence > 0.0);
    }

    #[test]
    fn recognize_preference_experience() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let input = "我喜欢用 Result 而不是异常处理";
        let result = extractor.analyze(input);

        assert!(result.is_some());
        let experience = result.unwrap();
        assert_eq!(experience.experience_type, ExperienceType::Preference);
        assert!(experience.confidence > 0.0);
    }

    #[test]
    fn recognize_knowledge_experience() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let input = "这个模块用到了事件溯源机制";
        let result = extractor.analyze(input);

        assert!(result.is_some());
        let experience = result.unwrap();
        assert_eq!(experience.experience_type, ExperienceType::Knowledge);
        assert!(experience.confidence > 0.0);
    }

    #[test]
    fn recognize_warning_experience() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let input = "不要用那个库，有 bug";
        let result = extractor.analyze(input);

        assert!(result.is_some());
        let experience = result.unwrap();
        assert_eq!(experience.experience_type, ExperienceType::Warning);
        assert!(experience.confidence > 0.0);
    }

    #[test]
    fn no_experience_for_normal_input() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        // Normal question - no experience keywords
        let input = "请帮我写一个 Hello World 程序";
        let result = extractor.analyze(input);
        assert!(result.is_none());

        // Too short input
        let short_input = "好的";
        let result = extractor.analyze(short_input);
        assert!(result.is_none());

        // Empty input
        let empty_input = "";
        let result = extractor.analyze(empty_input);
        assert!(result.is_none());
    }

    #[test]
    fn store_experience_as_skill() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let experience = UserExperience::new(
            "项目要求所有 API 必须加审计日志".to_string(),
            ExperienceType::Constraint,
            "所有 API 必须加审计日志".to_string(),
            0.85,
        )
        .with_tag("api")
        .with_tag("logging");

        let result = extractor.store_as_skill(&experience);
        assert!(result.is_ok());

        let meta = result.unwrap();
        assert!(meta.id.starts_with("exp-"));
        assert!(meta.path.ends_with(".md"));
        assert!(meta.tags.contains(&"api".to_string()));
    }

    #[test]
    fn generate_friendly_confirmation() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let experience = UserExperience::new(
            "项目要求所有 API 必须加审计日志".to_string(),
            ExperienceType::Constraint,
            "所有 API 必须加审计日志".to_string(),
            0.85,
        );

        let confirmation = extractor.generate_confirmation(&experience);

        assert!(confirmation.contains("好的，我记住了"));
        assert!(confirmation.contains("约束"));
        assert!(confirmation.contains(".skills/"));
    }

    #[test]
    fn contains_experience_helper() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        // Should return true for experience-containing input
        assert!(extractor.contains_experience("项目要求所有 API 必须加审计日志"));

        // Should return false for normal input
        assert!(!extractor.contains_experience("请帮我写一个函数"));
    }

    #[test]
    fn experience_type_default_category() {
        assert_eq!(
            ExperienceType::Constraint.default_category(),
            "coding/constraints"
        );
        assert_eq!(
            ExperienceType::Convention.default_category(),
            "coding/conventions"
        );
        assert_eq!(
            ExperienceType::Preference.default_category(),
            "coding/preferences"
        );
        assert_eq!(
            ExperienceType::Knowledge.default_category(),
            "domain/knowledge"
        );
        assert_eq!(ExperienceType::Warning.default_category(), "coding/warnings");
    }

    #[test]
    fn experience_type_display_name() {
        assert_eq!(ExperienceType::Constraint.display_name(), "约束");
        assert_eq!(ExperienceType::Convention.display_name(), "规范");
        assert_eq!(ExperienceType::Preference.display_name(), "偏好");
        assert_eq!(ExperienceType::Knowledge.display_name(), "知识");
        assert_eq!(ExperienceType::Warning.display_name(), "警告");
    }

    #[test]
    fn extract_domain_specific_tags() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let input = "所有 API 必须加安全审计日志";
        let result = extractor.analyze(input);

        assert!(result.is_some());
        let experience = result.unwrap();
        assert!(experience.tags.contains(&"api".to_string()));
        assert!(experience.tags.contains(&"security".to_string()));
        assert!(experience.tags.contains(&"logging".to_string()));
    }

    #[test]
    fn generate_skill_content_format() {
        let (_temp_dir, store) = create_test_store();
        let extractor = UserExperienceExtractor::new(store);

        let experience = UserExperience::new(
            "项目要求所有 API 必须加审计日志".to_string(),
            ExperienceType::Constraint,
            "所有 API 必须加审计日志".to_string(),
            0.85,
        )
        .with_tag("api");

        let content = extractor.generate_skill_content(&experience);

        assert!(content.contains("# 约束 - 用户经验"));
        assert!(content.contains("## 原始输入"));
        assert!(content.contains("## 经验内容"));
        assert!(content.contains("## 元信息"));
        assert!(content.contains("api"));
        assert!(content.contains("85%"));
    }
}
