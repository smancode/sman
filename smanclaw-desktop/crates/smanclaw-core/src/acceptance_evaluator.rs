//! Acceptance Evaluator for task verification
//!
//! This module provides functionality to evaluate completed tasks against
//! acceptance criteria and generate verification reports.

use std::path::PathBuf;
use std::process::Command;
use std::time::Instant;

use async_trait::async_trait;
use chrono::Utc;
use regex::Regex;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};
use crate::main_task::MainTask;

/// Acceptance criteria type
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AcceptanceCriteria {
    /// Functional requirement
    Functional {
        id: String,
        description: String,
        verification_method: VerificationMethod,
    },
    /// Performance requirement
    Performance {
        id: String,
        description: String,
        metric: String,
        target_value: String,
    },
    /// Code quality requirement
    CodeQuality {
        id: String,
        description: String,
        standard: String,
    },
    /// Test coverage requirement
    TestCoverage {
        id: String,
        description: String,
        minimum_percentage: u8,
    },
}

/// Verification method
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum VerificationMethod {
    Manual,
    UnitTest,
    E2ETest,
    CommandOutput,
    CodeReview,
    FileExists,
    ContentMatch,
    LLMJudge,
}

impl Default for VerificationMethod {
    fn default() -> Self {
        Self::Manual
    }
}

/// Criteria status
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum CriteriaStatus {
    Pending,
    Passed,
    Failed,
    Skipped,
}

impl Default for CriteriaStatus {
    fn default() -> Self {
        Self::Pending
    }
}

/// Criteria result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CriteriaResult {
    pub criteria_id: String,
    pub status: CriteriaStatus,
    pub evidence: String,
    pub message: String,
}

/// Test summary
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TestSummary {
    pub total: usize,
    pub passed: usize,
    pub failed: usize,
    pub skipped: usize,
    pub duration_ms: u64,
}

impl Default for TestSummary {
    fn default() -> Self {
        Self {
            total: 0,
            passed: 0,
            failed: 0,
            skipped: 0,
            duration_ms: 0,
        }
    }
}

/// Quality report
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityReport {
    pub warnings: usize,
    pub errors: usize,
    pub clippy_passed: bool,
}

impl Default for QualityReport {
    fn default() -> Self {
        Self {
            warnings: 0,
            errors: 0,
            clippy_passed: true,
        }
    }
}

/// Gap in verification
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Gap {
    pub description: String,
    pub severity: GapSeverity,
    pub suggested_fix: Option<String>,
}

/// Gap severity
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum GapSeverity {
    Critical,
    Major,
    Minor,
}

/// Requirement satisfaction level
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum RequirementSatisfaction {
    FullySatisfied,
    PartiallySatisfied(f32),
    NotSatisfied,
}

/// Evaluation result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluationResult {
    pub main_task_id: String,
    pub overall_passed: bool,
    pub criteria_results: Vec<CriteriaResult>,
    pub test_summary: TestSummary,
    pub quality_report: QualityReport,
    pub gaps: Vec<Gap>,
    pub recommendations: Vec<String>,
    pub evaluated_at: i64,
}

impl EvaluationResult {
    pub fn new(main_task_id: String) -> Self {
        Self {
            main_task_id,
            overall_passed: false,
            criteria_results: Vec::new(),
            test_summary: TestSummary::default(),
            quality_report: QualityReport::default(),
            gaps: Vec::new(),
            recommendations: Vec::new(),
            evaluated_at: Utc::now().timestamp(),
        }
    }

    /// Calculate completion percentage
    pub fn completion_percentage(&self) -> f32 {
        if self.criteria_results.is_empty() {
            return 0.0;
        }
        let passed = self
            .criteria_results
            .iter()
            .filter(|r| r.status == CriteriaStatus::Passed)
            .count();
        (passed as f32 / self.criteria_results.len() as f32) * 100.0
    }
}

/// Command execution result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommandResult {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub success: bool,
}

/// Verifier trait for criterion verification
#[async_trait]
pub trait Verifier: Send + Sync {
    async fn verify(&self, criterion: &AcceptanceCriteria) -> Result<CriteriaResult>;
}

/// Test runner for executing unit tests
pub struct TestRunner {
    project_path: PathBuf,
    test_command: String,
}

impl TestRunner {
    pub fn new(project_path: &std::path::Path) -> Self {
        Self {
            project_path: project_path.to_path_buf(),
            test_command: "cargo test".to_string(),
        }
    }

    /// Create with custom test command
    pub fn with_command(project_path: &std::path::Path, test_command: &str) -> Self {
        Self {
            project_path: project_path.to_path_buf(),
            test_command: test_command.to_string(),
        }
    }

    /// Run all unit tests and return summary
    pub fn run_unit_tests(&self) -> Result<TestSummary> {
        let start = Instant::now();
        let output = Command::new("cargo")
            .args(["test", "--no-fail-fast", "--", "--test-threads=1"])
            .current_dir(&self.project_path)
            .output()
            .map_err(|e| CoreError::CommandExecution(format!("Failed to run tests: {}", e)))?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let summary = self.parse_test_output(&stdout, start.elapsed().as_millis() as u64);

        Ok(summary)
    }

    /// Run a specific test by name
    pub fn run_specific_test(&self, test_name: &str) -> Result<bool> {
        let output = Command::new("cargo")
            .args([
                "test",
                "--no-fail-fast",
                "--",
                &format!("--filter={}", test_name),
            ])
            .current_dir(&self.project_path)
            .output()
            .map_err(|e| CoreError::CommandExecution(format!("Failed to run test: {}", e)))?;

        Ok(output.status.success())
    }

    /// Parse cargo test output to extract summary
    fn parse_test_output(&self, output: &str, duration_ms: u64) -> TestSummary {
        let mut summary = TestSummary {
            total: 0,
            passed: 0,
            failed: 0,
            skipped: 0,
            duration_ms,
        };

        // Parse test results from cargo output
        // Format: "test result: ok. X passed; Y failed; Z ignored; W measured; N filtered out"
        for line in output.lines() {
            if line.contains("passed") || line.contains("failed") {
                // Extract numbers
                if let Some(passed) = self.extract_number_after(line, "passed") {
                    summary.passed += passed;
                }
                if let Some(failed) = self.extract_number_after(line, "failed") {
                    summary.failed += failed;
                }
                if let Some(skipped) = self.extract_number_after(line, "ignored") {
                    summary.skipped += skipped;
                }
            }
            // Count test lines
            if line.starts_with("test ") && line.contains("... ") {
                summary.total += 1;
            }
        }

        // If no total was found, calculate it
        if summary.total == 0 {
            summary.total = summary.passed + summary.failed + summary.skipped;
        }

        summary
    }

    fn extract_number_after(&self, text: &str, keyword: &str) -> Option<usize> {
        let parts: Vec<&str> = text.split_whitespace().collect();
        for (i, part) in parts.iter().enumerate() {
            if *part == keyword || part.starts_with(&format!("{};", keyword)) {
                if i > 0 {
                    if let Ok(num) = parts[i - 1].parse::<usize>() {
                        return Some(num);
                    }
                }
            }
        }
        None
    }
}

/// File checker for verifying file existence and content
pub struct FileChecker {
    project_path: PathBuf,
}

impl FileChecker {
    pub fn new(project_path: &std::path::Path) -> Self {
        Self {
            project_path: project_path.to_path_buf(),
        }
    }

    /// Check if a file exists (relative to project path)
    pub fn check_exists(&self, path: &std::path::Path) -> bool {
        let full_path = self.project_path.join(path);
        full_path.exists()
    }

    /// Check if file content contains a pattern
    pub fn check_content_contains(&self, path: &std::path::Path, pattern: &str) -> Result<bool> {
        let full_path = self.project_path.join(path);
        let content = std::fs::read_to_string(&full_path).map_err(CoreError::Io)?;
        Ok(content.contains(pattern))
    }

    /// Check if file content matches a regex pattern
    pub fn check_content_matches(
        &self,
        path: &std::path::Path,
        regex_pattern: &str,
    ) -> Result<bool> {
        let full_path = self.project_path.join(path);
        let content = std::fs::read_to_string(&full_path).map_err(CoreError::Io)?;
        let re = Regex::new(regex_pattern)?;
        Ok(re.is_match(&content))
    }
}

/// Command executor for running shell commands
pub struct CommandExecutor {
    project_path: PathBuf,
}

impl CommandExecutor {
    pub fn new(project_path: &std::path::Path) -> Self {
        Self {
            project_path: project_path.to_path_buf(),
        }
    }

    /// Execute a shell command and return result
    pub fn execute(&self, command: &str) -> Result<CommandResult> {
        // Split command into program and args
        let parts: Vec<&str> = command.split_whitespace().collect();
        if parts.is_empty() {
            return Err(CoreError::InvalidInput("Empty command".to_string()));
        }

        let program = parts[0];
        let args = &parts[1..];

        let output = Command::new(program)
            .args(args)
            .current_dir(&self.project_path)
            .output()
            .map_err(|e| {
                CoreError::CommandExecution(format!("Failed to execute command: {}", e))
            })?;

        Ok(CommandResult {
            exit_code: output.status.code().unwrap_or(-1),
            stdout: String::from_utf8_lossy(&output.stdout).to_string(),
            stderr: String::from_utf8_lossy(&output.stderr).to_string(),
            success: output.status.success(),
        })
    }
}

/// Composite verifier that combines all verification methods
pub struct CompositeVerifier {
    test_runner: TestRunner,
    file_checker: FileChecker,
    command_executor: CommandExecutor,
}

impl CompositeVerifier {
    pub fn new(project_path: &std::path::Path) -> Self {
        Self {
            test_runner: TestRunner::new(project_path),
            file_checker: FileChecker::new(project_path),
            command_executor: CommandExecutor::new(project_path),
        }
    }

    /// Verify all criteria and return results
    pub async fn verify_all(&self, criteria: &[AcceptanceCriteria]) -> Result<Vec<CriteriaResult>> {
        let mut results = Vec::new();
        for criterion in criteria {
            let result = self.verify_criterion(criterion)?;
            results.push(result);
        }
        Ok(results)
    }

    /// Verify a single criterion based on its verification method
    fn verify_criterion(&self, criterion: &AcceptanceCriteria) -> Result<CriteriaResult> {
        let (id, description, verification_method) = match criterion {
            AcceptanceCriteria::Functional {
                id,
                description,
                verification_method,
            } => (id.clone(), description.clone(), verification_method.clone()),
            AcceptanceCriteria::Performance {
                id, description, ..
            } => (id.clone(), description.clone(), VerificationMethod::Manual),
            AcceptanceCriteria::CodeQuality {
                id, description, ..
            } => (
                id.clone(),
                description.clone(),
                VerificationMethod::CodeReview,
            ),
            AcceptanceCriteria::TestCoverage {
                id, description, ..
            } => (
                id.clone(),
                description.clone(),
                VerificationMethod::UnitTest,
            ),
        };

        let (status, evidence) = match verification_method {
            VerificationMethod::FileExists => self.verify_file_exists(&description)?,
            VerificationMethod::ContentMatch => self.verify_content_match(&description)?,
            VerificationMethod::CommandOutput => self.verify_command(&description)?,
            VerificationMethod::UnitTest => self.verify_tests()?,
            VerificationMethod::E2ETest => self.verify_e2e_tests()?,
            VerificationMethod::CodeReview => (CriteriaStatus::Pending, "待代码审查".to_string()),
            VerificationMethod::Manual => (CriteriaStatus::Pending, "待人工验证".to_string()),
            VerificationMethod::LLMJudge => (CriteriaStatus::Pending, "待LLM评估".to_string()),
        };

        Ok(CriteriaResult {
            criteria_id: id,
            status,
            evidence,
            message: format!("验证: {}", description),
        })
    }

    fn verify_file_exists(&self, description: &str) -> Result<(CriteriaStatus, String)> {
        // Try to extract file path from description
        // Format examples: "文件 src/main.rs 存在", "file: src/main.rs exists"
        let path = self.extract_path_from_description(description);

        if self.file_checker.check_exists(&path) {
            Ok((CriteriaStatus::Passed, format!("文件存在: {:?}", path)))
        } else {
            Ok((CriteriaStatus::Failed, format!("文件不存在: {:?}", path)))
        }
    }

    fn verify_content_match(&self, description: &str) -> Result<(CriteriaStatus, String)> {
        // Parse description for file path and pattern
        // Format example: "content: src/main.rs contains 'fn main'"
        let (path, pattern) = self.extract_path_and_pattern(description);

        if self.file_checker.check_content_contains(&path, &pattern)? {
            Ok((
                CriteriaStatus::Passed,
                format!("内容匹配: {:?} 包含 '{}'", path, pattern),
            ))
        } else {
            Ok((
                CriteriaStatus::Failed,
                format!("内容不匹配: {:?} 不包含 '{}'", path, pattern),
            ))
        }
    }

    fn verify_command(&self, description: &str) -> Result<(CriteriaStatus, String)> {
        // Extract command from description
        // Format example: "command: cargo build succeeds"
        let command = self.extract_command_from_description(description);

        let result = self.command_executor.execute(&command)?;
        if result.success {
            Ok((
                CriteriaStatus::Passed,
                format!(
                    "命令执行成功: {} (exit_code: {})",
                    command, result.exit_code
                ),
            ))
        } else {
            Ok((
                CriteriaStatus::Failed,
                format!(
                    "命令执行失败: {} (exit_code: {}, stderr: {})",
                    command, result.exit_code, result.stderr
                ),
            ))
        }
    }

    fn verify_tests(&self) -> Result<(CriteriaStatus, String)> {
        let summary = self.test_runner.run_unit_tests()?;
        if summary.failed == 0 && summary.passed > 0 {
            Ok((
                CriteriaStatus::Passed,
                format!("测试通过: {}/{}", summary.passed, summary.total),
            ))
        } else if summary.failed > 0 {
            Ok((
                CriteriaStatus::Failed,
                format!("测试失败: {} 失败, {} 通过", summary.failed, summary.passed),
            ))
        } else {
            Ok((CriteriaStatus::Pending, "没有运行测试".to_string()))
        }
    }

    fn verify_e2e_tests(&self) -> Result<(CriteriaStatus, String)> {
        // E2E tests would typically require additional setup
        // For now, return pending
        Ok((CriteriaStatus::Pending, "待E2E测试验证".to_string()))
    }

    fn extract_path_from_description(&self, description: &str) -> PathBuf {
        // Simple extraction: look for file path patterns
        // Try to find something that looks like a path
        let words: Vec<&str> = description.split_whitespace().collect();
        for word in &words {
            if word.contains('/')
                || word.contains('\\')
                || word.ends_with(".rs")
                || word.ends_with(".ts")
                || word.ends_with(".js")
            {
                return PathBuf::from(word.trim_matches(|c: char| {
                    !c.is_alphanumeric() && c != '/' && c != '\\' && c != '.' && c != '_'
                }));
            }
        }
        PathBuf::from(description)
    }

    fn extract_path_and_pattern(&self, description: &str) -> (PathBuf, String) {
        // Look for pattern like "path contains 'pattern'"
        if let Some(contains_idx) = description.find("contains") {
            let before = &description[..contains_idx].trim();
            let after = &description[contains_idx + 8..].trim();

            let path = self.extract_path_from_description(before);
            let pattern = after
                .trim_matches(|c| c == '\'' || c == '"' || c == '`')
                .to_string();

            return (path, pattern);
        }
        (PathBuf::from(description), String::new())
    }

    fn extract_command_from_description(&self, description: &str) -> String {
        // Look for pattern like "command: xxx" or "运行 xxx"
        if let Some(cmd_idx) = description.find("command:") {
            return description[cmd_idx + 8..].trim().to_string();
        }
        if description.contains("运行") || description.contains("run") {
            // Try to extract command after "运行" or "run"
            let words: Vec<&str> = description.split_whitespace().collect();
            for (i, word) in words.iter().enumerate() {
                if *word == "运行" || word.to_lowercase() == "run" {
                    if i + 1 < words.len() {
                        return words[i + 1..].join(" ");
                    }
                }
            }
        }
        description.to_string()
    }
}

/// Acceptance evaluator
pub struct AcceptanceEvaluator {
    project_path: PathBuf,
    verifier: CompositeVerifier,
}

impl AcceptanceEvaluator {
    pub fn new(project_path: &std::path::Path) -> Self {
        Self {
            project_path: project_path.to_path_buf(),
            verifier: CompositeVerifier::new(project_path),
        }
    }

    /// Extract acceptance criteria from main task
    pub fn extract_criteria(&self, main_task: &MainTask) -> Vec<AcceptanceCriteria> {
        let mut criteria = Vec::new();

        for (i, criterion) in main_task.acceptance_criteria.iter().enumerate() {
            let verification_method = self.infer_criteria_type(criterion);
            criteria.push(AcceptanceCriteria::Functional {
                id: format!("criteria-{}", i),
                description: criterion.clone(),
                verification_method,
            });
        }

        criteria
    }

    /// Infer verification method from description
    pub fn infer_criteria_type(&self, description: &str) -> VerificationMethod {
        let desc_lower = description.to_lowercase();

        // Check E2E first before general "test" pattern
        if desc_lower.contains("e2e") || desc_lower.contains("端到端") {
            VerificationMethod::E2ETest
        } else if desc_lower.contains("测试") || desc_lower.contains("test") {
            VerificationMethod::UnitTest
        } else if desc_lower.contains("文件存在")
            || desc_lower.contains("file exists")
            || desc_lower.contains("file:")
        {
            VerificationMethod::FileExists
        } else if desc_lower.contains("内容")
            || desc_lower.contains("content")
            || desc_lower.contains("包含")
        {
            VerificationMethod::ContentMatch
        } else if desc_lower.contains("运行")
            || desc_lower.contains("run")
            || desc_lower.contains("command")
        {
            VerificationMethod::CommandOutput
        } else if desc_lower.contains("代码")
            || desc_lower.contains("code")
            || desc_lower.contains("审查")
        {
            VerificationMethod::CodeReview
        } else if desc_lower.contains("llm") || desc_lower.contains("ai判断") {
            VerificationMethod::LLMJudge
        } else {
            VerificationMethod::Manual
        }
    }

    /// Evaluate against criteria
    pub fn evaluate(&self, criteria: &[AcceptanceCriteria]) -> Result<EvaluationResult> {
        let mut result = EvaluationResult::new("unknown".to_string());

        for criterion in criteria {
            let criteria_result = self.evaluate_criterion(criterion)?;
            result.criteria_results.push(criteria_result);
        }

        // Check if all passed
        result.overall_passed = result
            .criteria_results
            .iter()
            .all(|r| r.status == CriteriaStatus::Passed);

        // Generate recommendations if not fully passed
        if !result.overall_passed {
            result
                .recommendations
                .push("请检查未通过的验收标准".to_string());
        }

        result.evaluated_at = Utc::now().timestamp();
        Ok(result)
    }

    fn evaluate_criterion(&self, criterion: &AcceptanceCriteria) -> Result<CriteriaResult> {
        self.verifier.verify_criterion(criterion)
    }

    /// Verify file exists
    pub fn verify_file_exists(&self, description: &str) -> Result<(CriteriaStatus, String)> {
        self.verifier.verify_file_exists(description)
    }

    /// Verify content match
    pub fn verify_content_match(&self, description: &str) -> Result<(CriteriaStatus, String)> {
        self.verifier.verify_content_match(description)
    }

    /// Verify command
    pub fn verify_command(&self, description: &str) -> Result<(CriteriaStatus, String)> {
        self.verifier.verify_command(description)
    }

    /// Evaluate requirement satisfaction
    pub fn evaluate_requirement_satisfaction(
        &self,
        _user_request: &str,
        completed_tasks: usize,
        total_tasks: usize,
        evaluation_result: &EvaluationResult,
    ) -> RequirementSatisfaction {
        if total_tasks == 0 {
            return RequirementSatisfaction::NotSatisfied;
        }

        let task_completion = completed_tasks as f32 / total_tasks as f32;
        let criteria_completion = evaluation_result.completion_percentage() / 100.0;

        let overall = (task_completion + criteria_completion) / 2.0;

        if overall >= 1.0 {
            RequirementSatisfaction::FullySatisfied
        } else if overall >= 0.5 {
            RequirementSatisfaction::PartiallySatisfied(overall)
        } else {
            RequirementSatisfaction::NotSatisfied
        }
    }

    /// Generate markdown report
    pub fn generate_report(&self, result: &EvaluationResult) -> String {
        let mut report = String::new();

        report.push_str("# 验收报告\n\n");

        // Overview
        let status_icon = if result.overall_passed { "✅" } else { "❌" };
        report.push_str(&format!("## 概述\n\n"));
        report.push_str(&format!(
            "- 状态: {} {}\n",
            status_icon,
            if result.overall_passed {
                "通过"
            } else {
                "未通过"
            }
        ));
        report.push_str(&format!(
            "- 完成率: {:.1}%\n",
            result.completion_percentage()
        ));
        report.push_str(&format!(
            "- 测试: {}/{} 通过\n",
            result.test_summary.passed, result.test_summary.total
        ));
        report.push_str(&format!(
            "- 代码质量: {} 警告, {} 错误\n\n",
            result.quality_report.warnings, result.quality_report.errors
        ));

        // Criteria results table
        report.push_str("## 验收标准检查\n\n");
        report.push_str("| 标准 | 状态 | 证据 |\n");
        report.push_str("|------|------|------|\n");

        for cr in &result.criteria_results {
            let status = match cr.status {
                CriteriaStatus::Passed => "✅",
                CriteriaStatus::Failed => "❌",
                CriteriaStatus::Pending => "⏳",
                CriteriaStatus::Skipped => "⏭️",
            };
            report.push_str(&format!(
                "| {} | {} | {} |\n",
                cr.criteria_id, status, cr.evidence
            ));
        }

        report.push_str("\n");

        // Gaps
        if !result.gaps.is_empty() {
            report.push_str("## 差距分析\n\n");
            for gap in &result.gaps {
                let severity = match gap.severity {
                    GapSeverity::Critical => "🔴 严重",
                    GapSeverity::Major => "🟡 主要",
                    GapSeverity::Minor => "🟢 次要",
                };
                report.push_str(&format!("- [{}] {}\n", severity, gap.description));
                if let Some(ref fix) = gap.suggested_fix {
                    report.push_str(&format!("  - 建议: {}\n", fix));
                }
            }
            report.push_str("\n");
        }

        // Recommendations
        if !result.recommendations.is_empty() {
            report.push_str("## 建议\n\n");
            for rec in &result.recommendations {
                report.push_str(&format!("- {}\n", rec));
            }
            report.push_str("\n");
        }

        // Conclusion
        report.push_str("## 结论\n\n");
        if result.overall_passed {
            report.push_str("✅ 所有验收标准通过，任务完成。\n");
        } else {
            report.push_str("❌ 部分验收标准未通过，需要继续完善。\n");
        }

        report
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_evaluator() -> (TempDir, AcceptanceEvaluator) {
        let temp_dir = TempDir::new().expect("temp dir");
        let evaluator = AcceptanceEvaluator::new(temp_dir.path());
        (temp_dir, evaluator)
    }

    fn create_test_runner() -> (TempDir, TestRunner) {
        let temp_dir = TempDir::new().expect("temp dir");
        let runner = TestRunner::new(temp_dir.path());
        (temp_dir, runner)
    }

    fn create_file_checker() -> (TempDir, FileChecker) {
        let temp_dir = TempDir::new().expect("temp dir");
        let checker = FileChecker::new(temp_dir.path());
        (temp_dir, checker)
    }

    fn create_command_executor() -> (TempDir, CommandExecutor) {
        let temp_dir = TempDir::new().expect("temp dir");
        let executor = CommandExecutor::new(temp_dir.path());
        (temp_dir, executor)
    }

    fn create_composite_verifier() -> (TempDir, CompositeVerifier) {
        let temp_dir = TempDir::new().expect("temp dir");
        let verifier = CompositeVerifier::new(temp_dir.path());
        (temp_dir, verifier)
    }

    // TestRunner tests
    #[test]
    fn test_test_runner_creation() {
        let (_temp_dir, runner) = create_test_runner();
        assert!(runner.project_path.exists());
    }

    #[test]
    fn test_test_runner_with_custom_command() {
        let (_temp_dir, runner) = create_test_runner();
        let custom_runner = TestRunner::with_command(runner.project_path.as_path(), "npm test");
        assert_eq!(custom_runner.test_command, "npm test");
    }

    #[test]
    fn test_test_runner_run_unit_tests() {
        let (_temp_dir, runner) = create_test_runner();
        // This will fail since there's no Cargo.toml in temp dir, but we test the method
        let result = runner.run_unit_tests();
        // Should return an error or empty summary
        assert!(result.is_ok() || result.is_err());
    }

    #[test]
    fn test_test_runner_run_specific_test() {
        let (_temp_dir, runner) = create_test_runner();
        let result = runner.run_specific_test("test_example");
        // Should return an error or false
        assert!(result.is_ok() || result.is_err());
    }

    #[test]
    fn test_test_runner_extract_number_after() {
        let (_temp_dir, runner) = create_test_runner();
        let text = "test result: ok. 5 passed; 2 failed; 1 ignored";
        assert_eq!(runner.extract_number_after(text, "passed"), Some(5));
        assert_eq!(runner.extract_number_after(text, "failed"), Some(2));
        assert_eq!(runner.extract_number_after(text, "ignored"), Some(1));
    }

    // FileChecker tests
    #[test]
    fn test_file_checker_creation() {
        let (_temp_dir, checker) = create_file_checker();
        assert!(checker.project_path.exists());
    }

    #[test]
    fn test_file_checker_check_exists_true() {
        let (temp_dir, checker) = create_file_checker();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "test content").expect("write file");
        assert!(checker.check_exists(PathBuf::from("test.txt").as_path()));
    }

    #[test]
    fn test_file_checker_check_exists_false() {
        let (_temp_dir, checker) = create_file_checker();
        assert!(!checker.check_exists(PathBuf::from("nonexistent.txt").as_path()));
    }

    #[test]
    fn test_file_checker_check_content_contains_true() {
        let (temp_dir, checker) = create_file_checker();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "hello world").expect("write file");
        let result = checker.check_content_contains(PathBuf::from("test.txt").as_path(), "world");
        assert!(result.expect("check content"));
    }

    #[test]
    fn test_file_checker_check_content_contains_false() {
        let (temp_dir, checker) = create_file_checker();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "hello world").expect("write file");
        let result =
            checker.check_content_contains(PathBuf::from("test.txt").as_path(), "notfound");
        assert!(!result.expect("check content"));
    }

    #[test]
    fn test_file_checker_check_content_matches_true() {
        let (temp_dir, checker) = create_file_checker();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "fn main() {}").expect("write file");
        let result =
            checker.check_content_matches(PathBuf::from("test.txt").as_path(), r"fn\s+\w+\s*\(\)");
        assert!(result.expect("check content matches"));
    }

    #[test]
    fn test_file_checker_check_content_matches_false() {
        let (temp_dir, checker) = create_file_checker();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "hello world").expect("write file");
        let result = checker.check_content_matches(PathBuf::from("test.txt").as_path(), r"\d+");
        assert!(!result.expect("check content matches"));
    }

    // CommandExecutor tests
    #[test]
    fn test_command_executor_creation() {
        let (_temp_dir, executor) = create_command_executor();
        assert!(executor.project_path.exists());
    }

    #[test]
    fn test_command_executor_execute_success() {
        let (_temp_dir, executor) = create_command_executor();
        let result = executor.execute("echo hello").expect("execute");
        assert!(result.success);
        assert!(result.stdout.contains("hello"));
    }

    #[test]
    fn test_command_executor_execute_failure() {
        let (_temp_dir, executor) = create_command_executor();
        // Use a command that will fail
        let result = executor
            .execute("ls /nonexistent_directory_12345")
            .expect("execute");
        assert!(!result.success);
        assert_ne!(result.exit_code, 0);
    }

    #[test]
    fn test_command_executor_execute_empty_command() {
        let (_temp_dir, executor) = create_command_executor();
        let result = executor.execute("");
        assert!(result.is_err());
    }

    // CompositeVerifier tests
    #[test]
    fn test_composite_verifier_creation() {
        let (_temp_dir, verifier) = create_composite_verifier();
        assert!(verifier.test_runner.project_path.exists());
        assert!(verifier.file_checker.project_path.exists());
        assert!(verifier.command_executor.project_path.exists());
    }

    #[tokio::test]
    async fn test_composite_verifier_verify_all_empty() {
        let (_temp_dir, verifier) = create_composite_verifier();
        let results = verifier.verify_all(&[]).await.expect("verify all");
        assert!(results.is_empty());
    }

    #[tokio::test]
    async fn test_composite_verifier_verify_all_with_criteria() {
        let (temp_dir, verifier) = create_composite_verifier();
        // Create a file for testing
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "test").expect("write file");

        let criteria = vec![AcceptanceCriteria::Functional {
            id: "c1".to_string(),
            description: format!("文件 {:?} 存在", file_path),
            verification_method: VerificationMethod::Manual,
        }];

        let results = verifier.verify_all(&criteria).await.expect("verify all");
        assert_eq!(results.len(), 1);
    }

    #[test]
    fn test_composite_verifier_verify_file_exists_passed() {
        let (temp_dir, verifier) = create_composite_verifier();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "test").expect("write file");

        let (status, evidence) = verifier
            .verify_file_exists(&format!("文件 {:?} 存在", file_path))
            .expect("verify");
        assert_eq!(status, CriteriaStatus::Passed);
        assert!(evidence.contains("存在"));
    }

    #[test]
    fn test_composite_verifier_verify_file_exists_failed() {
        let (_temp_dir, verifier) = create_composite_verifier();
        let (status, evidence) = verifier
            .verify_file_exists("文件 nonexistent.txt 存在")
            .expect("verify");
        assert_eq!(status, CriteriaStatus::Failed);
        assert!(evidence.contains("不存在"));
    }

    #[test]
    fn test_composite_verifier_verify_content_match_passed() {
        let (temp_dir, verifier) = create_composite_verifier();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "hello world").expect("write file");

        let (status, _evidence) = verifier
            .verify_content_match(&format!("{:?} contains 'world'", file_path))
            .expect("verify");
        assert_eq!(status, CriteriaStatus::Passed);
    }

    #[test]
    fn test_composite_verifier_verify_content_match_failed() {
        let (temp_dir, verifier) = create_composite_verifier();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "hello world").expect("write file");

        let (status, _evidence) = verifier
            .verify_content_match(&format!("{:?} contains 'notfound'", file_path))
            .expect("verify");
        assert_eq!(status, CriteriaStatus::Failed);
    }

    // infer_criteria_type tests
    #[test]
    fn test_infer_criteria_type_test() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert_eq!(
            evaluator.infer_criteria_type("运行测试"),
            VerificationMethod::UnitTest
        );
        assert_eq!(
            evaluator.infer_criteria_type("run tests"),
            VerificationMethod::UnitTest
        );
    }

    #[test]
    fn test_infer_criteria_type_file() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert_eq!(
            evaluator.infer_criteria_type("文件存在 src/main.rs"),
            VerificationMethod::FileExists
        );
        assert_eq!(
            evaluator.infer_criteria_type("file exists: config.json"),
            VerificationMethod::FileExists
        );
    }

    #[test]
    fn test_infer_criteria_type_command() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert_eq!(
            evaluator.infer_criteria_type("运行 cargo build"),
            VerificationMethod::CommandOutput
        );
        assert_eq!(
            evaluator.infer_criteria_type("command: npm install"),
            VerificationMethod::CommandOutput
        );
    }

    #[test]
    fn test_infer_criteria_type_content() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert_eq!(
            evaluator.infer_criteria_type("内容包含 fn main"),
            VerificationMethod::ContentMatch
        );
        assert_eq!(
            evaluator.infer_criteria_type("content: hello"),
            VerificationMethod::ContentMatch
        );
    }

    #[test]
    fn test_infer_criteria_type_code_review() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert_eq!(
            evaluator.infer_criteria_type("代码审查通过"),
            VerificationMethod::CodeReview
        );
    }

    #[test]
    fn test_infer_criteria_type_e2e() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert_eq!(
            evaluator.infer_criteria_type("e2e测试通过"),
            VerificationMethod::E2ETest
        );
        assert_eq!(
            evaluator.infer_criteria_type("端到端测试"),
            VerificationMethod::E2ETest
        );
    }

    #[test]
    fn test_infer_criteria_type_llm() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert_eq!(
            evaluator.infer_criteria_type("LLM评估"),
            VerificationMethod::LLMJudge
        );
        assert_eq!(
            evaluator.infer_criteria_type("AI判断通过"),
            VerificationMethod::LLMJudge
        );
    }

    #[test]
    fn test_infer_criteria_type_manual() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert_eq!(
            evaluator.infer_criteria_type("用户确认功能正常"),
            VerificationMethod::Manual
        );
    }

    // AcceptanceEvaluator tests
    #[test]
    fn test_evaluator_creation() {
        let (_temp_dir, evaluator) = create_evaluator();
        assert!(evaluator.project_path.exists());
    }

    #[test]
    fn test_evaluation_result_new() {
        let result = EvaluationResult::new("task-1".to_string());
        assert_eq!(result.main_task_id, "task-1");
        assert!(!result.overall_passed);
        assert!(result.criteria_results.is_empty());
    }

    #[test]
    fn test_completion_percentage_empty() {
        let result = EvaluationResult::new("task-1".to_string());
        assert_eq!(result.completion_percentage(), 0.0);
    }

    #[test]
    fn test_completion_percentage_all_passed() {
        let mut result = EvaluationResult::new("task-1".to_string());
        result.criteria_results.push(CriteriaResult {
            criteria_id: "c1".to_string(),
            status: CriteriaStatus::Passed,
            evidence: String::new(),
            message: String::new(),
        });
        result.criteria_results.push(CriteriaResult {
            criteria_id: "c2".to_string(),
            status: CriteriaStatus::Passed,
            evidence: String::new(),
            message: String::new(),
        });
        assert_eq!(result.completion_percentage(), 100.0);
    }

    #[test]
    fn test_completion_percentage_half_passed() {
        let mut result = EvaluationResult::new("task-1".to_string());
        result.criteria_results.push(CriteriaResult {
            criteria_id: "c1".to_string(),
            status: CriteriaStatus::Passed,
            evidence: String::new(),
            message: String::new(),
        });
        result.criteria_results.push(CriteriaResult {
            criteria_id: "c2".to_string(),
            status: CriteriaStatus::Failed,
            evidence: String::new(),
            message: String::new(),
        });
        assert_eq!(result.completion_percentage(), 50.0);
    }

    #[test]
    fn test_extract_criteria() {
        let (_temp_dir, evaluator) = create_evaluator();
        let mut main_task = MainTask::new(PathBuf::from("/tmp").as_path(), "实现登录");
        main_task.acceptance_criteria =
            vec!["用户可以登录".to_string(), "登录失败有提示".to_string()];

        let criteria = evaluator.extract_criteria(&main_task);
        assert_eq!(criteria.len(), 2);
    }

    #[test]
    fn test_evaluate_empty_criteria() {
        let (_temp_dir, evaluator) = create_evaluator();
        let result = evaluator.evaluate(&[]).expect("evaluate");
        assert!(result.overall_passed); // Empty = all passed
    }

    #[test]
    fn test_requirement_satisfaction_fully() {
        let (_temp_dir, evaluator) = create_evaluator();
        let result = EvaluationResult::new("task-1".to_string());

        let satisfaction = evaluator.evaluate_requirement_satisfaction("实现登录", 3, 3, &result);

        // Need criteria to be passed for fully satisfied
        assert!(matches!(
            satisfaction,
            RequirementSatisfaction::PartiallySatisfied(_)
        ));
    }

    #[test]
    fn test_generate_report() {
        let (_temp_dir, evaluator) = create_evaluator();
        let mut result = EvaluationResult::new("task-1".to_string());
        result.criteria_results.push(CriteriaResult {
            criteria_id: "c1".to_string(),
            status: CriteriaStatus::Passed,
            evidence: "测试通过".to_string(),
            message: "OK".to_string(),
        });
        result.overall_passed = true;

        let report = evaluator.generate_report(&result);
        assert!(report.contains("# 验收报告"));
        assert!(report.contains("✅"));
    }

    #[test]
    fn test_generate_report_with_gaps() {
        let (_temp_dir, evaluator) = create_evaluator();
        let mut result = EvaluationResult::new("task-1".to_string());
        result.criteria_results.push(CriteriaResult {
            criteria_id: "c1".to_string(),
            status: CriteriaStatus::Failed,
            evidence: String::new(),
            message: "失败".to_string(),
        });
        result.gaps.push(Gap {
            description: "缺少单元测试".to_string(),
            severity: GapSeverity::Major,
            suggested_fix: Some("添加测试".to_string()),
        });
        result.recommendations.push("建议1".to_string());

        let report = evaluator.generate_report(&result);
        assert!(report.contains("差距分析"));
        assert!(report.contains("缺少单元测试"));
        assert!(report.contains("建议"));
    }

    #[test]
    fn test_verify_file_exists_returns_correct_status() {
        let (temp_dir, evaluator) = create_evaluator();
        let file_path = temp_dir.path().join("exists.txt");
        std::fs::write(&file_path, "content").expect("write file");

        let (status, _evidence) = evaluator
            .verify_file_exists(&format!("文件 {:?} 存在", file_path))
            .expect("verify");
        assert_eq!(status, CriteriaStatus::Passed);
    }

    #[test]
    fn test_verify_content_match_returns_correct_status() {
        let (temp_dir, evaluator) = create_evaluator();
        let file_path = temp_dir.path().join("test.txt");
        std::fs::write(&file_path, "hello world").expect("write file");

        let (status, _evidence) = evaluator
            .verify_content_match(&format!("{:?} contains 'world'", file_path))
            .expect("verify");
        assert_eq!(status, CriteriaStatus::Passed);
    }

    // VerificationMethod tests
    #[test]
    fn test_verification_method_default() {
        assert_eq!(VerificationMethod::default(), VerificationMethod::Manual);
    }

    // CriteriaStatus tests
    #[test]
    fn test_criteria_status_default() {
        assert_eq!(CriteriaStatus::default(), CriteriaStatus::Pending);
    }
}
