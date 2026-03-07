use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use smanclaw_core::TaskDag;

pub(crate) mod api;
mod runtime;
mod remediation;
pub(crate) mod status;

pub(crate) static ORCHESTRATION_DAGS: once_cell::sync::Lazy<Arc<RwLock<HashMap<String, TaskDag>>>> =
    once_cell::sync::Lazy::new(|| Arc::new(RwLock::new(HashMap::new())));
pub use api::execute_orchestrated_task;
pub use status::{
    get_orchestration_status, get_task_dag, OrchestrationProgress, OrchestratedTaskResult,
    TaskDagResponse,
};
