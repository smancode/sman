use async_trait::async_trait;
use smanclaw_core::skill_store::find_claude_skill_run_script;
use smanclaw_core::StepExecutor;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Clone)]
enum ClaudeExecutionBackend {
    SkillScript(PathBuf),
    Command { program: String, args: Vec<String> },
}

#[derive(Clone)]
pub struct ClaudeCodeStepExecutor {
    project_path: PathBuf,
    backend: ClaudeExecutionBackend,
}

impl ClaudeCodeStepExecutor {
    pub fn discover(project_path: &Path) -> Option<Self> {
        Self::discover_skill_script(project_path)
            .map(|backend| Self {
                project_path: project_path.to_path_buf(),
                backend,
            })
            .or_else(|| {
                Self::discover_command_backend().map(|backend| Self {
                    project_path: project_path.to_path_buf(),
                    backend,
                })
            })
    }

    pub fn from_command(
        project_path: &Path,
        program: impl Into<String>,
        args: Vec<String>,
    ) -> Self {
        Self {
            project_path: project_path.to_path_buf(),
            backend: ClaudeExecutionBackend::Command {
                program: program.into(),
                args,
            },
        }
    }

    fn discover_skill_script(project_path: &Path) -> Option<ClaudeExecutionBackend> {
        const CANDIDATE_SKILLS: [&str; 4] = ["code", "coder", "implement", "coding"];
        for skill in CANDIDATE_SKILLS {
            if let Some(script) = find_claude_skill_run_script(project_path, skill) {
                return Some(ClaudeExecutionBackend::SkillScript(script));
            }
        }
        None
    }

    fn discover_command_backend() -> Option<ClaudeExecutionBackend> {
        let program = std::env::var("SMANCLAW_CLAUDE_CODE_CMD")
            .ok()
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());
        let args = std::env::var("SMANCLAW_CLAUDE_CODE_ARGS")
            .ok()
            .map(|value| {
                value
                    .split_whitespace()
                    .map(str::to_string)
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        program.map(|program| ClaudeExecutionBackend::Command { program, args })
    }

    fn run_skill_script(
        &self,
        script: &Path,
        prompt: &str,
    ) -> std::io::Result<std::process::Output> {
        let extension = script
            .extension()
            .and_then(|value| value.to_str())
            .unwrap_or_default()
            .to_ascii_lowercase();
        let run_with_program = |program: &str, use_unbuffered: bool| {
            let mut command = Command::new(program);
            if use_unbuffered {
                command.arg("-u");
            }
            command
                .arg(script)
                .arg(prompt)
                .current_dir(&self.project_path)
                .output()
        };
        if extension == "py" {
            match run_with_program("python3", true) {
                Ok(output) => Ok(output),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    run_with_program("python", true)
                }
                Err(error) => Err(error),
            }
        } else if extension == "sh" {
            run_with_program("bash", false)
        } else {
            Command::new(script)
                .arg(prompt)
                .current_dir(&self.project_path)
                .output()
        }
    }

    fn run_command_backend(
        &self,
        program: &str,
        args: &[String],
        prompt: &str,
    ) -> std::io::Result<std::process::Output> {
        let mut command = Command::new(program);
        for arg in args {
            command.arg(arg);
        }
        command
            .arg(prompt)
            .current_dir(&self.project_path)
            .output()
    }

    fn normalize_output(output: std::process::Output) -> std::result::Result<String, String> {
        let mut merged = String::new();
        if !output.stdout.is_empty() {
            merged.push_str(String::from_utf8_lossy(&output.stdout).trim());
        }
        if !output.stderr.is_empty() {
            if !merged.is_empty() {
                merged.push('\n');
            }
            merged.push_str(String::from_utf8_lossy(&output.stderr).trim());
        }
        if output.status.success() {
            Ok(if merged.is_empty() {
                "ClaudeCode executor finished".to_string()
            } else {
                merged
            })
        } else {
            Err(if merged.is_empty() {
                format!("ClaudeCode executor exited with status {}", output.status)
            } else {
                merged
            })
        }
    }
}

#[async_trait]
impl StepExecutor for ClaudeCodeStepExecutor {
    async fn execute(&self, prompt: &str) -> std::result::Result<String, String> {
        let backend = self.backend.clone();
        let this = self.clone();
        let prompt = prompt.to_string();
        let output = tokio::task::spawn_blocking(move || match backend {
            ClaudeExecutionBackend::SkillScript(script) => {
                this.run_skill_script(&script, &prompt).map_err(|e| e.to_string())
            }
            ClaudeExecutionBackend::Command { program, args } => this
                .run_command_backend(&program, &args, &prompt)
                .map_err(|e| e.to_string()),
        })
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())?;
        Self::normalize_output(output)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    #[tokio::test]
    async fn execute_with_skill_script_works() {
        let temp_dir = TempDir::new().expect("temp dir");
        let skill_dir = temp_dir.path().join(".claude").join("skills").join("code");
        fs::create_dir_all(&skill_dir).expect("create skill dir");
        let script_path = skill_dir.join("run.sh");
        fs::write(
            &script_path,
            r#"#!/usr/bin/env bash
echo "skill:$1"
"#,
        )
        .expect("write script");

        let executor =
            ClaudeCodeStepExecutor::discover(temp_dir.path()).expect("discover claude executor");
        let result = executor.execute("hello").await.expect("execute");
        assert!(result.contains("skill:hello"));
    }

    #[tokio::test]
    async fn execute_with_custom_command_works() {
        let temp_dir = TempDir::new().expect("temp dir");
        let executor = ClaudeCodeStepExecutor::from_command(
            temp_dir.path(),
            "bash",
            vec!["-lc".to_string(), "printf 'cmd:%s' \"$0\"".to_string()],
        );
        let result = executor.execute("prompt").await.expect("execute");
        assert_eq!(result, "cmd:prompt");
    }

    #[tokio::test]
    async fn execute_returns_error_when_command_fails() {
        let temp_dir = TempDir::new().expect("temp dir");
        let executor =
            ClaudeCodeStepExecutor::from_command(temp_dir.path(), "this-command-not-found", vec![]);
        let result = executor.execute("prompt").await;
        assert!(result.is_err());
    }
}
