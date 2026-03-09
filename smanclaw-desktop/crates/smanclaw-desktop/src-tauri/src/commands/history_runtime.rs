use smanclaw_core::SqliteHistoryStore;
use smanclaw_types::Conversation;
use std::path::{Path, PathBuf};
use tauri::State;

use crate::error::{TauriError, TauriResult};
use crate::state::AppState;

fn fallback_project_runtime_dir(config_dir: &Path, project_path: &Path) -> PathBuf {
    let mut project_key = String::with_capacity(project_path.to_string_lossy().len() * 2);
    for byte in project_path.to_string_lossy().as_bytes() {
        project_key.push_str(&format!("{byte:02x}"));
    }
    config_dir.join("project-runtime").join(project_key)
}

fn ensure_project_runtime_dir(config_dir: &Path, project_path: &Path) -> TauriResult<PathBuf> {
    let runtime_dir = project_path.join(".sman");
    match std::fs::create_dir_all(&runtime_dir) {
        Ok(()) => {
            let probe_file = runtime_dir.join(".write_probe");
            match std::fs::OpenOptions::new()
                .create(true)
                .write(true)
                .truncate(true)
                .open(&probe_file)
            {
                Ok(_) => {
                    let _ = std::fs::remove_file(&probe_file);
                    Ok(runtime_dir)
                }
                Err(project_error) => {
                    let fallback_dir = fallback_project_runtime_dir(config_dir, project_path);
                    std::fs::create_dir_all(&fallback_dir).map_err(|fallback_error| {
                        tracing::error!(
                            project_path = %project_path.display(),
                            fallback_path = %fallback_dir.display(),
                            project_error = %project_error,
                            fallback_error = %fallback_error,
                            "Failed to write in project runtime directory and create fallback directory"
                        );
                        TauriError::Io(fallback_error)
                    })?;
                    tracing::warn!(
                        project_path = %project_path.display(),
                        fallback_path = %fallback_dir.display(),
                        error = %project_error,
                        "Using fallback runtime directory because project runtime directory is not writable"
                    );
                    Ok(fallback_dir)
                }
            }
        }
        Err(project_error) => {
            let fallback_dir = fallback_project_runtime_dir(config_dir, project_path);
            std::fs::create_dir_all(&fallback_dir).map_err(|fallback_error| {
                tracing::error!(
                    project_path = %project_path.display(),
                    fallback_path = %fallback_dir.display(),
                    project_error = %project_error,
                    fallback_error = %fallback_error,
                    "Failed to create project runtime directory and fallback directory"
                );
                TauriError::Io(fallback_error)
            })?;
            tracing::warn!(
                project_path = %project_path.display(),
                fallback_path = %fallback_dir.display(),
                error = %project_error,
                "Using fallback runtime directory because project path is not writable"
            );
            Ok(fallback_dir)
        }
    }
}

fn copy_sqlite_bundle_if_needed(target_db: &Path, source_db: &Path) -> TauriResult<()> {
    if target_db.exists() || !source_db.exists() {
        return Ok(());
    }
    std::fs::copy(source_db, target_db)?;
    let source_wal = source_db.with_extension("db-wal");
    let source_shm = source_db.with_extension("db-shm");
    let target_wal = target_db.with_extension("db-wal");
    let target_shm = target_db.with_extension("db-shm");
    if source_wal.exists() && !target_wal.exists() {
        let _ = std::fs::copy(&source_wal, &target_wal);
    }
    if source_shm.exists() && !target_shm.exists() {
        let _ = std::fs::copy(&source_shm, &target_shm);
    }
    Ok(())
}

fn merge_history_db_into_active(active_db: &Path, source_db: &Path) -> TauriResult<()> {
    if active_db == source_db || !source_db.exists() {
        return Ok(());
    }
    let active_store = SqliteHistoryStore::new(active_db)?;
    let source_store = SqliteHistoryStore::new(source_db)?;
    let conversations = source_store.list_conversations("")?;
    for conversation in conversations {
        active_store.upsert_conversation(&conversation)?;
        let entries = source_store.load_conversation(&conversation.id)?;
        for entry in entries {
            active_store.upsert_entry(&entry)?;
        }
    }
    Ok(())
}

pub(crate) fn open_project_history_store(
    config_dir: &Path,
    project_path: &Path,
) -> TauriResult<SqliteHistoryStore> {
    let runtime_dir = ensure_project_runtime_dir(config_dir, project_path)?;
    let history_db = runtime_dir.join("history.db");
    let project_db = project_path.join(".sman").join("history.db");
    let legacy_project_db = project_path.join(".smanclaw").join("history.db");
    let fallback_db = fallback_project_runtime_dir(config_dir, project_path).join("history.db");

    if history_db != project_db {
        if let Err(err) = copy_sqlite_bundle_if_needed(&history_db, &project_db) {
            tracing::warn!(
                project_path = %project_path.display(),
                history_db = %history_db.display(),
                source_db = %project_db.display(),
                error = %err,
                "Failed to migrate project history database into active runtime directory"
            );
        }
    }
    if history_db != legacy_project_db {
        if let Err(err) = copy_sqlite_bundle_if_needed(&history_db, &legacy_project_db) {
            tracing::warn!(
                project_path = %project_path.display(),
                history_db = %history_db.display(),
                source_db = %legacy_project_db.display(),
                error = %err,
                "Failed to migrate legacy project history database into active runtime directory"
            );
        }
    }
    if history_db != fallback_db {
        if let Err(err) = copy_sqlite_bundle_if_needed(&history_db, &fallback_db) {
            tracing::warn!(
                project_path = %project_path.display(),
                history_db = %history_db.display(),
                source_db = %fallback_db.display(),
                error = %err,
                "Failed to migrate fallback history database into active runtime directory"
            );
        }
    }
    if history_db != project_db {
        if let Err(err) = merge_history_db_into_active(&history_db, &project_db) {
            tracing::warn!(
                project_path = %project_path.display(),
                history_db = %history_db.display(),
                source_db = %project_db.display(),
                error = %err,
                "Failed to merge project history database into active runtime database"
            );
        }
    }
    if history_db != legacy_project_db {
        if let Err(err) = merge_history_db_into_active(&history_db, &legacy_project_db) {
            tracing::warn!(
                project_path = %project_path.display(),
                history_db = %history_db.display(),
                source_db = %legacy_project_db.display(),
                error = %err,
                "Failed to merge legacy project history database into active runtime database"
            );
        }
    }
    if history_db != fallback_db {
        if let Err(err) = merge_history_db_into_active(&history_db, &fallback_db) {
            tracing::warn!(
                project_path = %project_path.display(),
                history_db = %history_db.display(),
                source_db = %fallback_db.display(),
                error = %err,
                "Failed to merge fallback history database into active runtime database"
            );
        }
    }
    match SqliteHistoryStore::new(&history_db) {
        Ok(store) => Ok(store),
        Err(primary_error) => {
            let fallback_dir = fallback_project_runtime_dir(config_dir, project_path);
            std::fs::create_dir_all(&fallback_dir)?;
            let fallback_db = fallback_dir.join("history.db");
            match SqliteHistoryStore::new(&fallback_db) {
                Ok(store) => {
                    tracing::warn!(
                        project_path = %project_path.display(),
                        history_db = %history_db.display(),
                        fallback_db = %fallback_db.display(),
                        error = %primary_error,
                        "Using fallback history database because project history database is unavailable"
                    );
                    Ok(store)
                }
                Err(fallback_error) => {
                    tracing::error!(
                        project_path = %project_path.display(),
                        history_db = %history_db.display(),
                        fallback_db = %fallback_db.display(),
                        primary_error = %primary_error,
                        fallback_error = %fallback_error,
                        "Failed to open both project history database and fallback history database"
                    );
                    Err(TauriError::from(fallback_error))
                }
            }
        }
    }
}

pub(crate) async fn resolve_conversation_project(
    state: &State<'_, AppState>,
    conversation_id: &str,
) -> TauriResult<Option<(Conversation, PathBuf)>> {
    let projects = {
        let pm = state.project_manager.lock().await;
        pm.list_projects()?
    };
    for project in projects {
        let project_path = PathBuf::from(&project.path);
        let store = match open_project_history_store(&state.config_dir, &project_path) {
            Ok(store) => store,
            Err(err) => {
                tracing::warn!(
                    project_id = %project.id,
                    project_path = %project.path,
                    error = %err,
                    "Failed to open project history store"
                );
                continue;
            }
        };
        if let Some(conversation) = store.get_conversation(conversation_id)? {
            return Ok(Some((conversation, project_path)));
        }
    }
    Ok(None)
}
