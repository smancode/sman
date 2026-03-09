//! Experience Sink for sub-claw experience consolidation
//!
//! This module provides functionality to extract experiences from completed tasks
//! and update skills accordingly.

use std::path::Path;

use chrono::Utc;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};
use crate::skill_store::{Skill, SkillMeta, SkillStore};

/// Represents a learned item from task execution
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LearnedItem {
    /// Category of the learned item (coding, architecture, domain)
    pub category: String,
    /// Content of what was learned
    pub content: String,
    /// Tags for categorization and search
    pub tags: Vec<String>,
}

impl LearnedItem {
    /// Create a new learned item
    pub fn new(category: impl Into<String>, content: impl Into<String>) -> Self {
        Self {
            category: category.into(),
            content: content.into(),
            tags: Vec::new(),
        }
    }

    /// Add a tag to the learned item
    pub fn with_tag(mut self, tag: impl Into<String>) -> Self {
        self.tags.push(tag.into());
        self
    }
}

/// Represents a problem and its solution from task execution
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProblemSolution {
    /// Description of the problem encountered
    pub problem: String,
    /// Solution that was applied
    pub solution: String,
}

impl ProblemSolution {
    /// Create a new problem-solution pair
    pub fn new(problem: impl Into<String>, solution: impl Into<String>) -> Self {
        Self {
            problem: problem.into(),
            solution: solution.into(),
        }
    }
}

/// Experience extracted from a completed task
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Experience {
    /// Source task identifier
    pub source_task: String,
    /// Items learned during task execution
    pub learned: Vec<LearnedItem>,
    /// Problems encountered and solutions applied
    pub problems_solved: Vec<ProblemSolution>,
    /// Reusable patterns identified
    pub reusable_patterns: Vec<String>,
    /// Skills that should be updated based on this experience
    pub skills_to_update: Vec<String>,
}

impl Experience {
    /// Create a new experience for a task
    pub fn new(source_task: impl Into<String>) -> Self {
        Self {
            source_task: source_task.into(),
            ..Default::default()
        }
    }

    /// Add a learned item
    pub fn add_learned(&mut self, item: LearnedItem) {
        self.learned.push(item);
    }

    /// Add a problem-solution pair
    pub fn add_problem_solution(&mut self, ps: ProblemSolution) {
        self.problems_solved.push(ps);
    }

    /// Add a reusable pattern
    pub fn add_pattern(&mut self, pattern: impl Into<String>) {
        self.reusable_patterns.push(pattern.into());
    }

    /// Mark a skill for update
    pub fn mark_skill_for_update(&mut self, skill_name: impl Into<String>) {
        let skill_name = skill_name.into();
        if !self.skills_to_update.contains(&skill_name) {
            self.skills_to_update.push(skill_name);
        }
    }

    /// Check if this experience has any valuable content
    pub fn is_valuable(&self) -> bool {
        !self.learned.is_empty()
            || !self.problems_solved.is_empty()
            || !self.reusable_patterns.is_empty()
    }
}

/// Result of a completed task for experience extraction
#[derive(Debug, Clone)]
pub struct TaskResultForExperience {
    /// Task ID
    pub task_id: String,
    /// Task description/input
    pub description: String,
    /// Output from task execution
    pub output: String,
    /// Whether the task succeeded
    pub success: bool,
    /// Files changed during execution
    pub files_changed: Vec<String>,
    /// Raw task.md content (contains experience reflection section)
    pub task_md_content: Option<String>,
}

impl TaskResultForExperience {
    /// Create a new task result for experience extraction
    pub fn new(
        task_id: impl Into<String>,
        description: impl Into<String>,
        output: impl Into<String>,
        success: bool,
    ) -> Self {
        Self {
            task_id: task_id.into(),
            description: description.into(),
            output: output.into(),
            success,
            files_changed: Vec::new(),
            task_md_content: None,
        }
    }

    /// Add files changed
    pub fn with_files(mut self, files: Vec<String>) -> Self {
        self.files_changed = files;
        self
    }

    /// Add task.md content
    pub fn with_task_md(mut self, content: impl Into<String>) -> Self {
        self.task_md_content = Some(content.into());
        self
    }
}

/// Experience Sink for extracting and consolidating experiences
pub struct ExperienceSink {
    skill_store: SkillStore,
}

impl ExperienceSink {
    /// Create a new experience sink with a skill store
    pub fn new(skill_store: SkillStore) -> Self {
        Self { skill_store }
    }

    /// Extract experience from a completed task
    ///
    /// This method parses the task result and extracts valuable experience
    /// including learned items, problem solutions, and reusable patterns.
    pub fn extract_experience(&self, task_result: &TaskResultForExperience) -> Result<Experience> {
        if !task_result.success {
            // Failed tasks may still have valuable lessons
            return self.extract_from_failed_task(task_result);
        }

        let mut experience = Experience::new(&task_result.task_id);

        // Parse task.md content if available
        if let Some(ref content) = task_result.task_md_content {
            self.parse_experience_section(content, &mut experience)?;
        }

        // Extract patterns from output
        self.extract_patterns_from_output(&task_result.output, &mut experience);

        // Identify skills to update based on task description
        self.identify_skills_for_update(&task_result.description, &mut experience);

        Ok(experience)
    }

    /// Extract experience from a failed task
    fn extract_from_failed_task(
        &self,
        task_result: &TaskResultForExperience,
    ) -> Result<Experience> {
        let mut experience = Experience::new(&task_result.task_id);

        // Even failed tasks have lessons
        experience.add_problem_solution(ProblemSolution::new(
            format!("Task failed: {}", task_result.description),
            format!("Error output: {}", task_result.output),
        ));

        // Categorize as a pitfall to avoid
        experience.add_learned(
            LearnedItem::new("pitfall", format!("Avoid: {}", task_result.description))
                .with_tag("failure-analysis"),
        );

        Ok(experience)
    }

    /// Parse the experience section from task.md content
    ///
    /// Expected format:
    /// ```markdown
    /// ## 经验沉淀
    /// ### 学到的内容
    /// - [category] content #tag1 #tag2
    ///
    /// ### 问题与解决方案
    /// - 问题: xxx
    ///   解决: xxx
    ///
    /// ### 可复用模式
    /// - pattern description
    /// ```
    fn parse_experience_section(&self, content: &str, experience: &mut Experience) -> Result<()> {
        let in_experience_section = self
            .find_section(content, "经验沉淀")
            .or_else(|| self.find_section(content, "Experience"));

        let section_content = match in_experience_section {
            Some(s) => s,
            None => return Ok(()), // No experience section is fine
        };

        // Parse learned items
        if let Some(learned_section) = self
            .find_subsection(&section_content, "学到的内容")
            .or_else(|| self.find_subsection(&section_content, "Learned"))
        {
            self.parse_learned_items(&learned_section, experience);
        }

        // Parse problem solutions
        if let Some(ps_section) = self
            .find_subsection(&section_content, "问题与解决方案")
            .or_else(|| self.find_subsection(&section_content, "Problems"))
        {
            self.parse_problem_solutions(&ps_section, experience);
        }

        // Parse reusable patterns
        if let Some(pattern_section) = self
            .find_subsection(&section_content, "可复用模式")
            .or_else(|| self.find_subsection(&section_content, "Patterns"))
        {
            self.parse_patterns(&pattern_section, experience);
        }

        Ok(())
    }

    /// Find a section by heading
    fn find_section<'a>(&self, content: &'a str, heading: &str) -> Option<String> {
        let section_start = content
            .lines()
            .position(|line| line.trim().starts_with("## ") && line.contains(heading))?;

        let remaining_lines: Vec<&str> = content.lines().skip(section_start + 1).collect();
        let section_end = remaining_lines
            .iter()
            .position(|line| line.trim().starts_with("## "))
            .unwrap_or(remaining_lines.len());

        Some(remaining_lines[..section_end].join("\n"))
    }

    /// Find a subsection by heading
    fn find_subsection<'a>(&self, content: &'a str, heading: &str) -> Option<String> {
        let section_start = content
            .lines()
            .position(|line| line.trim().starts_with("### ") && line.contains(heading))?;

        let remaining_lines: Vec<&str> = content.lines().skip(section_start + 1).collect();
        let section_end = remaining_lines
            .iter()
            .position(|line| line.trim().starts_with("### "))
            .unwrap_or(remaining_lines.len());

        Some(remaining_lines[..section_end].join("\n"))
    }

    /// Parse learned items from section
    fn parse_learned_items(&self, section: &str, experience: &mut Experience) {
        for line in section.lines() {
            let line = line.trim();
            if !line.starts_with("- ") && !line.starts_with("* ") {
                continue;
            }

            let content = line.trim_start_matches('-').trim_start_matches('*').trim();

            // Parse category: [category] content #tag1 #tag2
            let (category, rest) = if content.starts_with('[') {
                if let Some(end) = content.find(']') {
                    (
                        content[1..end].to_string(),
                        content[end + 1..].trim().to_string(),
                    )
                } else {
                    ("general".to_string(), content.to_string())
                }
            } else {
                ("general".to_string(), content.to_string())
            };

            // Extract tags
            let mut tags = Vec::new();
            let content_without_tags: String = rest
                .split_whitespace()
                .filter(|word| {
                    if word.starts_with('#') {
                        tags.push(word[1..].to_string());
                        false
                    } else {
                        true
                    }
                })
                .collect::<Vec<_>>()
                .join(" ");

            if !content_without_tags.is_empty() {
                let mut item = LearnedItem::new(category, content_without_tags);
                item.tags = tags;
                experience.add_learned(item);
            }
        }
    }

    /// Parse problem solutions from section
    fn parse_problem_solutions(&self, section: &str, experience: &mut Experience) {
        let mut current_problem: Option<String> = None;

        for line in section.lines() {
            let line = line.trim();

            if line.starts_with("- ") || line.starts_with("* ") {
                let content = line[2..].trim();
                if content.starts_with("问题") || content.starts_with("Problem") {
                    // Extract problem
                    if let Some(colon_pos) = content.find(':') {
                        current_problem = Some(content[colon_pos + 1..].trim().to_string());
                    }
                }
            } else if line.starts_with("解决") || line.starts_with("Solution") {
                if let Some(colon_pos) = line.find(':') {
                    let solution = line[colon_pos + 1..].trim().to_string();
                    if let Some(problem) = current_problem.take() {
                        experience.add_problem_solution(ProblemSolution::new(problem, solution));
                    }
                }
            }
        }
    }

    /// Parse patterns from section
    fn parse_patterns(&self, section: &str, experience: &mut Experience) {
        for line in section.lines() {
            let line = line.trim();
            if line.starts_with("- ") || line.starts_with("* ") {
                let pattern = line[2..].trim();
                if !pattern.is_empty() {
                    experience.add_pattern(pattern);
                }
            }
        }
    }

    /// Extract patterns from task output
    fn extract_patterns_from_output(&self, output: &str, experience: &mut Experience) {
        // Simple rule-based pattern extraction
        // Look for common patterns in output

        // Pattern: "Created file: xxx" or "创建文件: xxx"
        for line in output.lines() {
            let line = line.to_lowercase();
            if line.contains("created") || line.contains("创建") || line.contains("added") {
                if !experience
                    .reusable_patterns
                    .iter()
                    .any(|p| p.contains("file creation"))
                {
                    experience.add_pattern("File creation pattern detected");
                }
            }
        }
    }

    /// Identify skills that should be updated based on task description
    fn identify_skills_for_update(&self, description: &str, experience: &mut Experience) {
        let desc_lower = description.to_lowercase();

        // Rule-based skill identification
        if desc_lower.contains("登录") || desc_lower.contains("login") {
            experience.mark_skill_for_update("authentication");
        }
        if desc_lower.contains("注册")
            || desc_lower.contains("register")
            || desc_lower.contains("registration")
            || desc_lower.contains("signup")
        {
            experience.mark_skill_for_update("user-management");
        }
        if desc_lower.contains("数据库")
            || desc_lower.contains("database")
            || desc_lower.contains("sql")
        {
            experience.mark_skill_for_update("database");
        }
        if desc_lower.contains("api") || desc_lower.contains("接口") {
            experience.mark_skill_for_update("api-design");
        }
        if desc_lower.contains("测试") || desc_lower.contains("test") {
            experience.mark_skill_for_update("testing");
        }
    }

    /// Determine if a skill should be created or updated based on experience
    pub fn should_update_skill(&self, experience: &Experience) -> bool {
        // Update skill if experience has valuable content and skills marked for update
        experience.is_valuable() && !experience.skills_to_update.is_empty()
    }

    /// Create or update a skill based on experience
    pub fn update_skill(&self, experience: &Experience) -> Result<SkillMeta> {
        if experience.skills_to_update.is_empty() {
            return Err(CoreError::InvalidInput(
                "No skills marked for update in experience".to_string(),
            ));
        }

        // For now, update the first skill in the list
        // In a more sophisticated implementation, this would handle multiple skills
        let skill_id = &experience.skills_to_update[0];
        let skill_path = format!("auto/{}.md", skill_id);

        // Try to get existing skill
        let existing_skill = self.skill_store.get(skill_id)?;

        // Build skill content from experience
        let content = self.build_skill_content(experience, existing_skill.as_ref());

        // Collect tags from experience
        let mut tags: Vec<String> = experience
            .learned
            .iter()
            .flat_map(|item| item.tags.clone())
            .collect();
        tags.push("auto-generated".to_string());
        tags.sort();
        tags.dedup();

        let meta = SkillMeta {
            id: skill_id.clone(),
            path: skill_path,
            tags,
            learned_from: experience.source_task.clone(),
            updated_at: Utc::now().timestamp(),
        };

        let skill = Skill {
            meta: meta.clone(),
            content,
        };

        // Create or update
        if existing_skill.is_some() {
            self.skill_store.update(&skill)?;
        } else {
            self.skill_store.create(&skill)?;
        }

        Ok(meta)
    }

    /// Build skill content from experience
    fn build_skill_content(&self, experience: &Experience, existing: Option<&Skill>) -> String {
        let mut content = String::new();

        // Add header
        content.push_str(&format!("# Skill: {}\n\n", experience.skills_to_update[0]));

        // Add source info
        content.push_str(&format!("**Learned from:** {}\n\n", experience.source_task));

        // Add learned items
        if !experience.learned.is_empty() {
            content.push_str("## Learned\n\n");
            for item in &experience.learned {
                content.push_str(&format!("- [{}] {}", item.category, item.content));
                if !item.tags.is_empty() {
                    content.push_str(&format!(" #{}", item.tags.join(" #")));
                }
                content.push('\n');
            }
            content.push('\n');
        }

        // Add problem solutions
        if !experience.problems_solved.is_empty() {
            content.push_str("## Problem Solutions\n\n");
            for ps in &experience.problems_solved {
                content.push_str(&format!("### Problem\n{}\n\n", ps.problem));
                content.push_str(&format!("### Solution\n{}\n\n", ps.solution));
            }
        }

        // Add patterns
        if !experience.reusable_patterns.is_empty() {
            content.push_str("## Reusable Patterns\n\n");
            for pattern in &experience.reusable_patterns {
                content.push_str(&format!("- {}\n", pattern));
            }
            content.push('\n');
        }

        // Append existing content if available
        if let Some(existing) = existing {
            content.push_str("\n---\n\n");
            content.push_str("## Previous Content\n\n");
            content.push_str(&existing.content);
        }

        content
    }

    /// Update MEMORY.md file with experience
    ///
    /// This updates the main Claw's memory file with the new experience
    pub fn update_memory(&self, project_path: &Path, experience: &Experience) -> Result<()> {
        let runtime_dir = project_path.join(".sman");
        std::fs::create_dir_all(&runtime_dir)?;
        let memory_path = runtime_dir.join("MEMORY.md");

        // Read existing content or create new
        let existing_content = if memory_path.exists() {
            std::fs::read_to_string(&memory_path)?
        } else {
            String::new()
        };

        // Append experience section
        let new_section = self.format_memory_section(experience);

        let updated_content = if existing_content.is_empty() {
            format!(
                "# Project Memory\n\nThis file tracks experiences and learnings from task executions.\n\n{}\n",
                new_section
            )
        } else {
            // Insert before the last section or append
            if existing_content.contains("## Experience Log") {
                // Insert into existing log
                existing_content.replace(
                    "## Experience Log",
                    &format!("## Experience Log\n\n{}", new_section),
                )
            } else {
                format!(
                    "{}\n\n## Experience Log\n\n{}\n",
                    existing_content, new_section
                )
            }
        };

        std::fs::write(&memory_path, updated_content)?;

        Ok(())
    }

    /// Format experience as a memory section
    fn format_memory_section(&self, experience: &Experience) -> String {
        let mut section = format!("### Task: {}\n\n", experience.source_task);

        if !experience.learned.is_empty() {
            section.push_str("**Learned:**\n");
            for item in &experience.learned {
                section.push_str(&format!("- [{}] {} ", item.category, item.content));
                if !item.tags.is_empty() {
                    section.push_str(&format!("#{}", item.tags.join(" #")));
                }
                section.push('\n');
            }
            section.push('\n');
        }

        if !experience.problems_solved.is_empty() {
            section.push_str("**Problems Solved:**\n");
            for ps in &experience.problems_solved {
                section.push_str(&format!(
                    "- Problem: {}\n  Solution: {}\n",
                    ps.problem, ps.solution
                ));
            }
            section.push('\n');
        }

        if !experience.reusable_patterns.is_empty() {
            section.push_str("**Patterns:**\n");
            for pattern in &experience.reusable_patterns {
                section.push_str(&format!("- {}\n", pattern));
            }
        }

        section
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_sink() -> ExperienceSink {
        let temp_dir = TempDir::new().expect("create temp dir");
        let store = SkillStore::new(temp_dir.path()).expect("create skill store");
        ExperienceSink::new(store)
    }

    #[test]
    fn learned_item_creation() {
        let item = LearnedItem::new("coding", "Use pattern matching for error handling")
            .with_tag("rust")
            .with_tag("best-practice");

        assert_eq!(item.category, "coding");
        assert_eq!(item.content, "Use pattern matching for error handling");
        assert_eq!(item.tags, vec!["rust", "best-practice"]);
    }

    #[test]
    fn problem_solution_creation() {
        let ps = ProblemSolution::new("Database connection timeout", "Use connection pooling");

        assert_eq!(ps.problem, "Database connection timeout");
        assert_eq!(ps.solution, "Use connection pooling");
    }

    #[test]
    fn experience_creation() {
        let mut exp = Experience::new("task-123");
        exp.add_learned(LearnedItem::new("coding", "Test content"));
        exp.add_problem_solution(ProblemSolution::new("P1", "S1"));
        exp.add_pattern("Pattern 1");
        exp.mark_skill_for_update("testing");

        assert_eq!(exp.source_task, "task-123");
        assert_eq!(exp.learned.len(), 1);
        assert_eq!(exp.problems_solved.len(), 1);
        assert_eq!(exp.reusable_patterns.len(), 1);
        assert_eq!(exp.skills_to_update, vec!["testing"]);
    }

    #[test]
    fn experience_is_valuable() {
        let mut exp1 = Experience::new("task-1");
        assert!(!exp1.is_valuable());

        exp1.add_learned(LearnedItem::new("coding", "Something"));
        assert!(exp1.is_valuable());
    }

    #[test]
    fn experience_duplicate_skill_prevention() {
        let mut exp = Experience::new("task-1");
        exp.mark_skill_for_update("auth");
        exp.mark_skill_for_update("auth");
        exp.mark_skill_for_update("db");

        assert_eq!(exp.skills_to_update.len(), 2);
    }

    #[test]
    fn extract_experience_from_successful_task() {
        let sink = create_sink();

        let task_md = r#"
# Task: Implement Login

## 经验沉淀

### 学到的内容
- [coding] Use JWT for stateless authentication #security #jwt
- [architecture] Separate auth logic from business logic

### 问题与解决方案
- 问题: Password hashing was slow
  解决: Use bcrypt with cost factor 10

### 可复用模式
- Repository pattern for user data access
"#;

        let task_result = TaskResultForExperience::new(
            "task-123",
            "Implement login feature",
            "Successfully implemented",
            true,
        )
        .with_task_md(task_md);

        let experience = sink
            .extract_experience(&task_result)
            .expect("extract experience");

        assert_eq!(experience.source_task, "task-123");
        assert_eq!(experience.learned.len(), 2);
        assert_eq!(experience.learned[0].category, "coding");
        assert_eq!(experience.learned[0].tags, vec!["security", "jwt"]);
        assert_eq!(experience.problems_solved.len(), 1);
        assert_eq!(experience.reusable_patterns.len(), 1);
        assert!(experience
            .skills_to_update
            .contains(&"authentication".to_string()));
    }

    #[test]
    fn extract_experience_from_failed_task() {
        let sink = create_sink();

        let task_result = TaskResultForExperience::new(
            "task-fail",
            "Complex refactoring",
            "Error: Circular dependency detected",
            false,
        );

        let experience = sink
            .extract_experience(&task_result)
            .expect("extract experience");

        assert!(experience
            .problems_solved
            .iter()
            .any(|ps| ps.problem.contains("failed") && ps.solution.contains("Error")));
        assert!(!experience.learned.is_empty());
    }

    #[test]
    fn extract_experience_without_task_md() {
        let sink = create_sink();

        let task_result =
            TaskResultForExperience::new("task-123", "Implement login feature", "Done", true);

        let experience = sink
            .extract_experience(&task_result)
            .expect("extract experience");

        // Should still identify skills from description
        assert!(experience
            .skills_to_update
            .contains(&"authentication".to_string()));
    }

    #[test]
    fn should_update_skill_returns_true_for_valuable_experience() {
        let sink = create_sink();

        let mut experience = Experience::new("task-1");
        experience.add_learned(LearnedItem::new("coding", "Test"));
        experience.mark_skill_for_update("testing");

        assert!(sink.should_update_skill(&experience));
    }

    #[test]
    fn should_update_skill_returns_false_for_empty_experience() {
        let sink = create_sink();

        let experience = Experience::new("task-1");
        assert!(!sink.should_update_skill(&experience));
    }

    #[test]
    fn should_update_skill_returns_false_without_skills_marked() {
        let sink = create_sink();

        let mut experience = Experience::new("task-1");
        experience.add_learned(LearnedItem::new("coding", "Test"));
        // No skill marked

        assert!(!sink.should_update_skill(&experience));
    }

    #[test]
    fn update_skill_creates_new_skill() {
        let sink = create_sink();

        let mut experience = Experience::new("task-1");
        experience.mark_skill_for_update("authentication");
        experience.add_learned(LearnedItem::new("security", "Use HTTPS").with_tag("security"));

        let meta = sink.update_skill(&experience).expect("update skill");

        assert_eq!(meta.id, "authentication");
        assert!(meta.tags.contains(&"security".to_string()));
        assert!(meta.tags.contains(&"auto-generated".to_string()));
    }

    #[test]
    fn update_skill_fails_without_marked_skills() {
        let sink = create_sink();

        let experience = Experience::new("task-1");

        let result = sink.update_skill(&experience);
        assert!(result.is_err());
    }

    #[test]
    fn update_memory_creates_new_file() {
        let sink = create_sink();
        let temp_dir = TempDir::new().expect("create temp dir");

        let mut experience = Experience::new("task-1");
        experience.add_learned(LearnedItem::new("coding", "Test learning"));

        sink.update_memory(temp_dir.path(), &experience)
            .expect("update memory");

        let memory_path = temp_dir.path().join(".sman").join("MEMORY.md");
        assert!(memory_path.exists());

        let content = std::fs::read_to_string(&memory_path).expect("read memory");
        assert!(content.contains("# Project Memory"));
        assert!(content.contains("task-1"));
        assert!(content.contains("Test learning"));
    }

    #[test]
    fn update_memory_appends_to_existing_file() {
        let sink = create_sink();
        let temp_dir = TempDir::new().expect("create temp dir");
        let runtime_dir = temp_dir.path().join(".sman");
        std::fs::create_dir_all(&runtime_dir).expect("create runtime dir");
        let memory_path = runtime_dir.join("MEMORY.md");

        // Create existing file
        std::fs::write(&memory_path, "# Project Memory\n\nExisting content\n").expect("write");

        let mut experience = Experience::new("task-2");
        experience.add_learned(LearnedItem::new("coding", "New learning"));

        sink.update_memory(temp_dir.path(), &experience)
            .expect("update memory");

        let content = std::fs::read_to_string(&memory_path).expect("read memory");
        assert!(content.contains("Existing content"));
        assert!(content.contains("task-2"));
        assert!(content.contains("New learning"));
    }

    #[test]
    fn parse_english_experience_section() {
        let sink = create_sink();

        let task_md = r#"
# Task: Implement Feature

## Experience

### Learned
- [coding] Use dependency injection #pattern
- [testing] Write tests first #tdd

### Problems
- Problem: Slow query
  Solution: Add index

### Patterns
- Factory pattern for object creation
"#;

        let task_result =
            TaskResultForExperience::new("task-123", "Feature implementation", "Done", true)
                .with_task_md(task_md);

        let experience = sink
            .extract_experience(&task_result)
            .expect("extract experience");

        assert_eq!(experience.learned.len(), 2);
        assert_eq!(experience.problems_solved.len(), 1);
        assert_eq!(experience.reusable_patterns.len(), 1);
    }

    #[test]
    fn skill_identification_from_description() {
        let sink = create_sink();

        let descriptions_and_skills = vec![
            ("Implement user login", vec!["authentication"]),
            ("Create registration form", vec!["user-management"]),
            ("Add database migration", vec!["database"]),
            ("Build REST API", vec!["api-design"]),
            ("Write unit tests", vec!["testing"]),
        ];

        for (desc, expected_skills) in descriptions_and_skills {
            let task_result = TaskResultForExperience::new("task-1", desc, "Done", true);
            let experience = sink.extract_experience(&task_result).expect("extract");

            for expected in expected_skills {
                assert!(
                    experience.skills_to_update.contains(&expected.to_string()),
                    "Expected skill '{}' for description '{}', got {:?}",
                    expected,
                    desc,
                    experience.skills_to_update
                );
            }
        }
    }

    #[test]
    fn build_skill_content_includes_all_sections() {
        let sink = create_sink();

        let mut experience = Experience::new("task-123");
        experience.mark_skill_for_update("test-skill");
        experience.add_learned(LearnedItem::new("coding", "Learn item 1").with_tag("rust"));
        experience.add_problem_solution(ProblemSolution::new("Problem A", "Solution A"));
        experience.add_pattern("Pattern X");

        let content = sink.build_skill_content(&experience, None);

        assert!(content.contains("# Skill: test-skill"));
        assert!(content.contains("**Learned from:** task-123"));
        assert!(content.contains("## Learned"));
        assert!(content.contains("Learn item 1"));
        assert!(content.contains("## Problem Solutions"));
        assert!(content.contains("Problem A"));
        assert!(content.contains("Solution A"));
        assert!(content.contains("## Reusable Patterns"));
        assert!(content.contains("Pattern X"));
    }
}
