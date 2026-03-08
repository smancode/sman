//! Tauri commands for frontend communication

pub(crate) mod chat_execution;
pub(crate) mod conversation_commands;
mod history_runtime;
pub(crate) mod orchestration_decompose;
pub(crate) mod project_commands;
pub(crate) mod settings_commands;
pub(crate) mod task_commands;
pub(crate) mod utility_commands;
pub(crate) use crate::error::{TauriError, TauriResult};
pub use crate::orchestration::{
    execute_orchestrated_task, get_orchestration_status, get_task_dag, OrchestratedTaskResult,
    OrchestrationProgress, TaskDagResponse,
};
pub(crate) use crate::state::AppState;
pub(crate) use history_runtime::{open_project_history_store, resolve_conversation_project};
pub use project_commands::{
    get_project_skills,
    add_project, get_project_config, get_projects, remove_project, update_project_config,
};

#[cfg(test)]
mod tests {
    use crate::commands::orchestration_decompose::{
        enforce_subtask_context_independence, extract_json_payload, fallback_decompose_subtasks,
        is_simple_requirement, normalize_requirement_summary, normalize_semantic_subtasks,
        parse_semantic_subtasks, sanitize_task_id, SemanticDecomposeResponse, SemanticSubTask,
    };
    use crate::commands::task_commands::{
        build_remediation_subtasks, subtask_file_stem, subtask_relative_path,
    };
    use crate::commands::utility_commands::{
        persist_path_from_task_experience, persist_user_input_experience, persist_user_memory,
        sanitize_path_fragment, should_generate_path_from_task_experience,
    };
    use smanclaw_core::{
        EvaluationResult, Experience, LearnedItem, SkillStore, SubTask, SubTaskStatus, TaskDag,
    };
    use std::fs;

    #[test]
    fn extracts_json_from_markdown_block() {
        let output =
            "text\n```json\n{\"subtasks\":[{\"id\":\"task-1\",\"description\":\"a\"}]}\n```\nend";
        let payload = extract_json_payload(output).expect("json payload");
        assert!(payload.contains("\"subtasks\""));
    }

    #[test]
    fn sanitizes_and_deduplicates_task_ids() {
        let response = SemanticDecomposeResponse {
            subtasks: vec![
                SemanticSubTask {
                    id: Some("Task_1".to_string()),
                    description: "first".to_string(),
                    depends_on: vec![],
                    test_command: None,
                },
                SemanticSubTask {
                    id: Some("task-1".to_string()),
                    description: "second".to_string(),
                    depends_on: vec!["Task_1".to_string()],
                    test_command: None,
                },
            ],
        };

        let tasks = normalize_semantic_subtasks(response).expect("normalized tasks");
        assert_eq!(tasks[0].id, "task-1");
        assert_eq!(tasks[1].id, "task-1-2");
        assert_eq!(tasks[1].depends_on, vec!["task-1"]);
    }

    #[test]
    fn parses_semantic_subtasks_with_dependencies() {
        let output = r#"{"subtasks":[{"id":"plan","description":"plan work","depends_on":[]},{"id":"impl","description":"implement","depends_on":["plan"],"test_command":"cargo test"}]}"#;
        let tasks = parse_semantic_subtasks(output).expect("parsed subtasks");
        assert_eq!(tasks.len(), 2);
        assert_eq!(tasks[1].depends_on, vec!["plan"]);
        assert_eq!(tasks[1].test_command.as_deref(), Some("cargo test"));
    }

    #[test]
    fn sanitize_id_falls_back_for_numeric_prefix() {
        let id = sanitize_task_id("123abc", 0);
        assert_eq!(id, "task-1");
    }

    #[test]
    fn remediation_subtasks_use_round_specific_ids() {
        let mut failed = SubTask::new("task-1", "failing task");
        failed.status = SubTaskStatus::Failed;
        let dag = TaskDag::from_tasks(vec![failed]).expect("dag");

        let round1 = build_remediation_subtasks(&dag, None, 1);
        let round2 = build_remediation_subtasks(&dag, None, 2);

        assert_eq!(round1.len(), 1);
        assert_eq!(round2.len(), 1);
        assert_eq!(round1[0].id, "remediate-r1-task-1");
        assert_eq!(round2[0].id, "remediate-r2-task-1");
        assert_ne!(round1[0].id, round2[0].id);
    }

    #[test]
    fn remediation_subtasks_fallback_to_general_task() {
        let dag = TaskDag::from_tasks(vec![SubTask::new("task-1", "done task")]).expect("dag");
        let mut evaluation = EvaluationResult::new("main-task".to_string());
        evaluation.recommendations.clear();

        let remediation = build_remediation_subtasks(&dag, Some(&evaluation), 1);

        assert_eq!(remediation.len(), 1);
        assert_eq!(remediation[0].id, "remediate-r1-general");
    }

    #[test]
    fn fallback_decompose_generates_structured_chain_for_generic_requirement() {
        let tasks = fallback_decompose_subtasks(
            "请重构任务编排并增强验收评估稳定性，覆盖失败重试并补齐回归验证",
        );

        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].id, "task-context-serial");
        assert!(tasks[0].description.contains("单一子Claw上下文"));
        assert_eq!(tasks[0].test_command.as_deref(), Some("cargo test"));
    }

    #[test]
    fn fallback_decompose_prefers_existing_rule_based_result() {
        let tasks = fallback_decompose_subtasks("Implement user login feature");
        assert!(!tasks.is_empty());
        assert!(tasks
            .iter()
            .any(|t| t.id.contains("login") || t.id == "task-context-serial"));
    }

    #[test]
    fn fallback_decompose_keeps_single_task_for_simple_requirement() {
        let tasks = fallback_decompose_subtasks("修复按钮颜色");
        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].id, "task-0");
    }

    #[test]
    fn context_independence_merges_cross_context_subtasks() {
        let subtasks = vec![
            SubTask::new("task-1", "完成数据模型设计"),
            SubTask::new("task-2", "基于 task-1 的上下文完成接口实现").depends_on("task-1"),
        ];
        let merged = enforce_subtask_context_independence(subtasks, "实现支付流程");
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].id, "task-context-serial");
    }

    #[test]
    fn context_independence_keeps_parallelizable_subtasks() {
        let subtasks = vec![
            SubTask::new("task-ui", "完成前端页面改造"),
            SubTask::new("task-api", "完成后端接口改造"),
        ];
        let kept = enforce_subtask_context_independence(subtasks, "同步改造前后端");
        assert_eq!(kept.len(), 2);
        assert_eq!(kept[0].id, "task-ui");
        assert_eq!(kept[1].id, "task-api");
    }

    #[test]
    fn simple_requirement_heuristic_distinguishes_complex_input() {
        assert!(is_simple_requirement("修复按钮颜色"));
        assert!(!is_simple_requirement("重构任务编排，并补齐回归验证"));
    }

    #[test]
    fn path_generation_heuristic_respects_reusable_patterns() {
        let mut experience = Experience::new("task-1");
        assert!(!should_generate_path_from_task_experience(&experience));
        experience.add_pattern("固定执行顺序：先校验再发布");
        assert!(should_generate_path_from_task_experience(&experience));
    }

    #[test]
    fn sanitize_path_fragment_outputs_stable_token() {
        assert_eq!(sanitize_path_fragment("Task A_B"), "task-a-b");
        assert_eq!(sanitize_path_fragment("###"), "task");
    }

    #[test]
    fn persist_user_memory_creates_memory_file() {
        let root = std::env::temp_dir().join(format!(
            "smanclaw-memory-test-{}",
            chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()
        ));
        fs::create_dir_all(&root).expect("create temp root");

        persist_user_memory(
            &root,
            "接口返回必须包含trace_id",
            "接口必须返回trace_id",
            &["api".to_string(), "constraint".to_string()],
        );

        let memory_path = root.join(".smanclaw").join("MEMORY.md");
        let content = fs::read_to_string(memory_path).expect("read memory");
        assert!(content.contains("user-input"));
        assert!(content.contains("trace_id"));

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn persist_user_input_experience_creates_skill_memory_and_path() {
        let root = std::env::temp_dir().join(format!(
            "smanclaw-user-exp-test-{}",
            chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()
        ));
        fs::create_dir_all(&root).expect("create temp root");

        persist_user_input_experience(&root, "接口返回必须包含trace_id");

        let memory_path = root.join(".smanclaw").join("MEMORY.md");
        let memory = fs::read_to_string(memory_path).expect("read memory");
        assert!(memory.contains("trace_id"));

        let skill_store = SkillStore::new(&root).expect("skill store");
        let skill_metas = skill_store.list().expect("list skills");
        assert!(!skill_metas.is_empty());

        let path_store = SkillStore::for_paths(&root).expect("path store");
        let path_metas = path_store.list().expect("list paths");
        assert!(!path_metas.is_empty());
        assert!(path_metas
            .iter()
            .any(|meta| meta.tags.contains(&"path".to_string())));

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn persist_task_experience_writes_path_skill() {
        let root = std::env::temp_dir().join(format!(
            "smanclaw-path-test-{}",
            chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()
        ));
        fs::create_dir_all(&root).expect("create temp root");

        let mut experience = Experience::new("task-api-hardening");
        experience.add_learned(LearnedItem::new("workflow", "发布前必须执行回归"));
        experience.add_pattern("先 lint，再 test，最后部署");
        persist_path_from_task_experience(&root, &experience);

        let store = SkillStore::for_paths(&root).expect("path store");
        let metas = store.list().expect("list path skills");
        assert!(!metas.is_empty());
        assert!(metas
            .iter()
            .any(|meta| meta.tags.contains(&"path".to_string())));

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn requirement_summary_is_trimmed_to_stable_length() {
        let long_input = "这是一个非常长的需求描述，需要在多模块中实现并保持可回滚、可观测、可测试，同时要求失败自动补救、再次验收和经验沉淀流程完整闭环".repeat(2);
        let summary = normalize_requirement_summary(&long_input);
        assert!(summary.chars().count() <= 75);
        assert!(summary.ends_with("..."));
    }

    #[test]
    fn subtask_file_stem_preserves_main_task_relation() {
        let stem = subtask_file_stem("main-2603071250-A1B2", 1);
        assert_eq!(stem, "task-main-2603071250-A1B2-001");

        let second = subtask_file_stem("main-2603071250-A1B2", 12);
        assert_eq!(second, "task-main-2603071250-A1B2-012");
    }

    #[test]
    fn subtask_relative_path_uses_named_markdown() {
        let path = subtask_relative_path("task-main-2603071250-A1B2-001");
        assert_eq!(path, ".smanclaw/tasks/task-main-2603071250-A1B2-001.md");
    }
}
