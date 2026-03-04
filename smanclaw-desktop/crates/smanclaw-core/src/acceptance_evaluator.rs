//! Acceptance Evaluator for task verification
//!
//! This module provides functionality to evaluate completed tasks against
//! acceptance criteria and generate verification reports.

use std::path::PathBuf;

use chrono::Utc;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};
use crate::main_task::{MainTask, MainTaskStatus};

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

/// Acceptance evaluator
pub struct AcceptanceEvaluator {
    project_path: PathBuf,
}

impl AcceptanceEvaluator {
    pub fn new(project_path: &std::path::Path) -> Self {
        Self {
            project_path: project_path.to_path_buf(),
        }
    }

    /// Extract acceptance criteria from main task
    pub fn extract_criteria(&self, main_task: &MainTask) -> Vec<AcceptanceCriteria> {
        let mut criteria = Vec::new();

        for (i, criterion) in main_task.acceptance_criteria.iter().enumerate() {
            criteria.push(AcceptanceCriteria::Functional {
                id: format!("criteria-{}", i),
                description: criterion.clone(),
                verification_method: VerificationMethod::Manual,
            });
        }

        criteria
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
            result.recommendations.push("请检查未通过的验收标准".to_string());
        }

        result.evaluated_at = Utc::now().timestamp();
        Ok(result)
    }

    fn evaluate_criterion(&self, criterion: &AcceptanceCriteria) -> Result<CriteriaResult> {
        let (id, description) = match criterion {
            AcceptanceCriteria::Functional { id, description, .. } => (id.clone(), description.clone()),
            AcceptanceCriteria::Performance { id, description, .. } => (id.clone(), description.clone()),
            AcceptanceCriteria::CodeQuality { id, description, .. } => (id.clone(), description.clone()),
            AcceptanceCriteria::TestCoverage { id, description, .. } => (id.clone(), description.clone()),
        };

        // Default to pending - actual verification would run tests/commands
        Ok(CriteriaResult {
            criteria_id: id,
            status: CriteriaStatus::Pending,
            evidence: String::new(),
            message: format!("待验证: {}", description),
        })
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
        report.push_str(&format!("- 状态: {} {}\n", status_icon, if result.overall_passed { "通过" } else { "未通过" }));
        report.push_str(&format!("- 完成率: {:.1}%\n", result.completion_percentage()));
        report.push_str(&format!("- 测试: {}/{} 通过\n", result.test_summary.passed, result.test_summary.total));
        report.push_str(&format!("- 代码质量: {} 警告, {} 错误\n\n", result.quality_report.warnings, result.quality_report.errors));

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
            report.push_str(&format!("| {} | {} | {} |\n", cr.criteria_id, status, cr.evidence));
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
        main_task.acceptance_criteria = vec![
            "用户可以登录".to_string(),
            "登录失败有提示".to_string(),
        ];

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

        let satisfaction = evaluator.evaluate_requirement_satisfaction(
            "实现登录",
            3,
            3,
            &result,
        );

        // Need criteria to be passed for fully satisfied
        assert!(matches!(satisfaction, RequirementSatisfaction::PartiallySatisfied(_)));
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
}
