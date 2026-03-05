//! Orchestration Bridge - Exposes ZeroClaw's team orchestration capabilities
//!
//! This module bridges ZeroClaw's team_orchestration module to the FFI layer,
//! enabling multi-agent collaboration patterns in SmanClaw Desktop.

// Re-export all public types from ZeroClaw's team_orchestration
pub use zeroclaw::agent::team_orchestration::{
    allocate_task_budgets,
    analyze_execution_plan,
    build_batch_handoff_messages,
    build_conflict_aware_execution_plan,
    // Public functions
    derive_planner_config,
    estimate_batch_handoff_tokens,
    estimate_handoff_tokens,
    evaluate_all_budget_tiers,
    evaluate_team_topologies,
    orchestrate_task_graph,
    validate_execution_plan,
    A2ALiteMessage,
    // A2A-Lite types
    A2AStatus,
    BudgetTier,
    DegradationPolicy,
    ExecutionBatch,
    ExecutionPlan,
    ExecutionPlanDiagnostics,
    GateOutcome,
    GateThresholds,
    HandoffPolicy,
    // Model and evaluation types
    ModelTier,
    OrchestrationBundle,
    OrchestrationError,
    OrchestrationEvalParams,
    OrchestrationRecommendation,
    OrchestrationReport,
    PlanError,
    PlanValidationError,
    PlannedTaskBudget,
    PlannerConfig,
    // Orchestration types
    ProtocolMode,
    RecommendationMode,
    RecommendationScore,
    RiskLevel,
    // Planning types
    TaskNodeSpec,
    TeamBudgetProfile,
    // Core topology types
    TeamTopology,
    TopologyEvaluation,
    WorkloadProfile,
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_team_topology_accessible() {
        // Verify that TeamTopology is accessible through the bridge
        let topologies = TeamTopology::all();
        assert_eq!(topologies.len(), 4);

        // Verify as_str method works
        assert_eq!(TeamTopology::Single.as_str(), "single");
        assert_eq!(TeamTopology::LeadSubagent.as_str(), "lead_subagent");
        assert_eq!(TeamTopology::StarTeam.as_str(), "star_team");
        assert_eq!(TeamTopology::MeshTeam.as_str(), "mesh_team");
    }

    #[test]
    fn test_budget_tier_accessible() {
        // Verify BudgetTier is accessible
        let _tier = BudgetTier::Medium;
    }

    #[test]
    fn test_team_budget_profile_creation() {
        // Verify TeamBudgetProfile can be created from tier
        let profile = TeamBudgetProfile::from_tier(BudgetTier::Medium);
        assert!(profile.max_workers >= 1);
    }

    #[test]
    fn test_planner_config_creation() {
        // Verify PlannerConfig can be created with default
        let config = PlannerConfig::default();
        assert!(config.max_parallel >= 1);
    }

    #[test]
    fn test_handoff_policy_default() {
        // Verify HandoffPolicy default values
        let policy = HandoffPolicy::default();
        assert!(policy.max_summary_chars >= 16);
        assert!(policy.max_artifacts > 0);
        assert!(policy.max_needs > 0);
    }

    #[test]
    fn test_a2a_lite_message_creation() {
        // Verify A2ALiteMessage can be created with correct fields
        let message = A2ALiteMessage {
            run_id: "run-001".to_string(),
            task_id: "task-001".to_string(),
            sender: "agent-1".to_string(),
            recipient: "agent-2".to_string(),
            status: A2AStatus::Queued,
            confidence: 85,
            risk_level: RiskLevel::Low,
            summary: "This is a test summary with enough characters for validation".to_string(),
            artifacts: vec!["artifact1.txt".to_string()],
            needs: vec!["dependency1".to_string()],
            next_action: "proceed to next step".to_string(),
        };

        assert_eq!(message.run_id, "run-001");
        assert_eq!(message.sender, "agent-1");
        assert_eq!(message.recipient, "agent-2");
        assert_eq!(message.status, A2AStatus::Queued);
    }

    #[test]
    fn test_handoff_policy_validation() {
        // Verify HandoffPolicy validation works
        let policy = HandoffPolicy::default();
        let message = A2ALiteMessage {
            run_id: "run-001".to_string(),
            task_id: "task-001".to_string(),
            sender: "agent-1".to_string(),
            recipient: "agent-2".to_string(),
            status: A2AStatus::Queued,
            confidence: 90,
            risk_level: RiskLevel::Low,
            summary: "Test summary with enough characters for reliable handoff".to_string(),
            artifacts: vec![],
            needs: vec![],
            next_action: "continue execution".to_string(),
        };

        // Validation should pass with valid message
        assert!(message.validate(policy).is_ok());
    }

    #[test]
    fn test_task_node_spec_creation() {
        // Verify TaskNodeSpec can be created
        let spec = TaskNodeSpec {
            id: "task-1".to_string(),
            depends_on: vec![],
            ownership_keys: vec!["file.rs".to_string()],
            estimated_execution_tokens: 1000,
            estimated_coordination_tokens: 100,
        };

        assert_eq!(spec.id, "task-1");
        assert!(spec.depends_on.is_empty());
    }

    #[test]
    fn test_risk_level_variants() {
        // Verify all RiskLevel variants are accessible
        assert!(matches!(RiskLevel::Low, RiskLevel::Low));
        assert!(matches!(RiskLevel::Medium, RiskLevel::Medium));
        assert!(matches!(RiskLevel::High, RiskLevel::High));
        assert!(matches!(RiskLevel::Critical, RiskLevel::Critical));
    }

    #[test]
    fn test_a2a_status_variants() {
        // Verify all A2AStatus variants are accessible
        assert!(matches!(A2AStatus::Queued, A2AStatus::Queued));
        assert!(matches!(A2AStatus::Running, A2AStatus::Running));
        assert!(matches!(A2AStatus::Blocked, A2AStatus::Blocked));
        assert!(matches!(A2AStatus::Done, A2AStatus::Done));
        assert!(matches!(A2AStatus::Failed, A2AStatus::Failed));
    }

    #[test]
    fn test_plan_error_variants() {
        // Verify PlanError variants are accessible
        let _error = PlanError::EmptyTaskId;
        let _error = PlanError::DuplicateTaskId("task-1".to_string());
        let _error = PlanError::SelfDependency("task-1".to_string());
    }

    #[test]
    fn test_build_conflict_aware_execution_plan() {
        // Test building a simple execution plan
        let tasks = vec![
            TaskNodeSpec {
                id: "task-1".to_string(),
                depends_on: vec![],
                ownership_keys: vec!["a.txt".to_string()],
                estimated_execution_tokens: 500,
                estimated_coordination_tokens: 50,
            },
            TaskNodeSpec {
                id: "task-2".to_string(),
                depends_on: vec!["task-1".to_string()],
                ownership_keys: vec!["b.txt".to_string()],
                estimated_execution_tokens: 500,
                estimated_coordination_tokens: 50,
            },
        ];

        let config = PlannerConfig::default();
        let result = build_conflict_aware_execution_plan(&tasks, config);
        assert!(result.is_ok());

        let plan = result.unwrap();
        assert_eq!(plan.topological_order.len(), 2);
        assert_eq!(plan.batches.len(), 2); // Two batches due to dependency
    }

    #[test]
    fn test_validate_execution_plan() {
        // Test execution plan validation
        let tasks = vec![TaskNodeSpec {
            id: "task-1".to_string(),
            depends_on: vec![],
            ownership_keys: vec![],
            estimated_execution_tokens: 100,
            estimated_coordination_tokens: 10,
        }];

        let config = PlannerConfig::default();
        let plan = build_conflict_aware_execution_plan(&tasks, config).unwrap();
        let result = validate_execution_plan(&plan, &tasks);
        assert!(result.is_ok());
    }

    #[test]
    fn test_analyze_execution_plan() {
        // Test execution plan analysis
        let tasks = vec![TaskNodeSpec {
            id: "task-1".to_string(),
            depends_on: vec![],
            ownership_keys: vec![],
            estimated_execution_tokens: 100,
            estimated_coordination_tokens: 10,
        }];

        let config = PlannerConfig::default();
        let plan = build_conflict_aware_execution_plan(&tasks, config).unwrap();
        let diagnostics = analyze_execution_plan(&plan, &tasks);

        assert!(diagnostics.is_ok());
        let diag = diagnostics.unwrap();
        assert_eq!(diag.task_count, 1);
        assert!(diag.max_parallelism >= 1);
    }

    #[test]
    fn test_estimate_handoff_tokens() {
        // Test handoff token estimation
        let message = A2ALiteMessage {
            run_id: "run-001".to_string(),
            task_id: "task-001".to_string(),
            sender: "agent-1".to_string(),
            recipient: "agent-2".to_string(),
            status: A2AStatus::Queued,
            confidence: 90,
            risk_level: RiskLevel::Low,
            summary: "Test summary for token estimation".to_string(),
            artifacts: vec!["file.txt".to_string()],
            needs: vec![],
            next_action: "continue".to_string(),
        };

        let tokens = estimate_handoff_tokens(&message);
        assert!(tokens > 0);
    }

    #[test]
    fn test_full_orchestration_workflow() {
        // Test full orchestration with all parameters
        let budget = TeamBudgetProfile::from_tier(BudgetTier::Medium);
        let params = OrchestrationEvalParams::default();
        let topologies = TeamTopology::all().to_vec();
        let tasks = vec![
            TaskNodeSpec {
                id: "task-1".to_string(),
                depends_on: vec![],
                ownership_keys: vec!["file1.rs".to_string()],
                estimated_execution_tokens: 1000,
                estimated_coordination_tokens: 100,
            },
            TaskNodeSpec {
                id: "task-2".to_string(),
                depends_on: vec!["task-1".to_string()],
                ownership_keys: vec!["file2.rs".to_string()],
                estimated_execution_tokens: 2000,
                estimated_coordination_tokens: 200,
            },
        ];
        let handoff_policy = HandoffPolicy::default();

        let bundle = orchestrate_task_graph(
            "test-run-001",
            budget,
            &params,
            &topologies,
            &tasks,
            handoff_policy,
        );

        assert!(bundle.is_ok());
        let bundle = bundle.unwrap();
        assert!(!bundle.plan.topological_order.is_empty());
        assert_eq!(
            bundle.estimated_handoff_tokens,
            estimate_batch_handoff_tokens(&bundle.handoff_messages)
        );
    }

    #[test]
    fn test_allocate_task_budgets() {
        // Test task budget allocation
        let tasks = vec![
            TaskNodeSpec {
                id: "task-1".to_string(),
                depends_on: vec![],
                ownership_keys: vec![],
                estimated_execution_tokens: 1000,
                estimated_coordination_tokens: 100,
            },
            TaskNodeSpec {
                id: "task-2".to_string(),
                depends_on: vec![],
                ownership_keys: vec![],
                estimated_execution_tokens: 2000,
                estimated_coordination_tokens: 200,
            },
        ];

        let budgets = allocate_task_budgets(&tasks, None, 50);
        assert_eq!(budgets.len(), 2);

        // Verify budget totals are calculated correctly
        for budget in &budgets {
            assert!(budget.total_tokens > 0);
        }
    }
}
