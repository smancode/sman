//! Project explorer for scanning and analyzing codebase structure
//!
//! This module provides functionality to explore a project directory,
//! identify its type (Rust, Node.js, Python, etc.), extract build commands,
//! and gather other relevant information for AGENTS.md generation.

use std::collections::HashMap;
use std::fs;
use std::path::Path;

use smanclaw_types::{BuildConfig, CodeStyle, ProjectKnowledge, ProjectType};

use crate::error::{CoreError, Result};

/// Directories to exclude from directory tree scanning
const EXCLUDED_DIRS: &[&str] = &[
    "node_modules",
    "target",
    ".git",
    "__pycache__",
    ".venv",
    "venv",
    "dist",
    "build",
    ".idea",
    ".vscode",
    "coverage",
    ".next",
    ".nuxt",
    "vendor",
    "pkg",
    "bin",
    "obj",
    ".gradle",
    "out",
];

/// Rule files to look for in the project root
const RULE_FILES: &[&str] = &[
    "CLAUDE.md",
    ".cursorrules",
    ".claude",
    ".github/copilot-instructions.md",
    "AGENTS.md",
    ".agent",
    "INSTRUCTIONS.md",
];

/// Project explorer for scanning and analyzing codebases
pub struct ProjectExplorer;

impl ProjectExplorer {
    /// Create a new ProjectExplorer instance
    pub fn new() -> Self {
        Self
    }

    /// Explore a project directory and extract project knowledge
    ///
    /// # Arguments
    /// * `path` - Path to the project root directory
    ///
    /// # Returns
    /// * `ProjectKnowledge` containing all discovered project information
    ///
    /// # Errors
    /// Returns an error if the path doesn't exist or isn't a directory
    pub fn explore(&self, path: &Path) -> Result<ProjectKnowledge> {
        if !path.exists() {
            return Err(CoreError::InvalidInput(format!(
                "Path does not exist: {}",
                path.display()
            )));
        }

        if !path.is_dir() {
            return Err(CoreError::InvalidInput(format!(
                "Path is not a directory: {}",
                path.display()
            )));
        }

        let project_name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "unknown".to_string());

        let project_type = Self::detect_project_type(path);
        let directory_tree = Self::build_directory_tree(path, 0, 3)?;
        let build_config = Self::extract_build_commands(path, &project_type);
        let dependencies = Self::extract_dependencies(path, &project_type)?;
        let existing_rules = Self::find_existing_rules(path)?;
        let code_style = Self::infer_code_style(&project_type);
        let entry_points = Self::find_entry_points(path, &project_type)?;

        Ok(ProjectKnowledge {
            name: project_name,
            description: None,
            project_type,
            directory_structure: directory_tree,
            dependencies,
            build_config,
            code_style,
            modules: vec![],
            existing_rules,
            entry_points,
        })
    }

    /// Detect the project type based on files in the root directory
    fn detect_project_type(path: &Path) -> ProjectType {
        // Check for Rust project
        if path.join("Cargo.toml").exists() {
            return ProjectType::Rust;
        }

        // Check for Node.js project
        if path.join("package.json").exists() {
            return ProjectType::NodeJs;
        }

        // Check for Python project
        if path.join("pyproject.toml").exists()
            || path.join("setup.py").exists()
            || path.join("requirements.txt").exists()
        {
            return ProjectType::Python;
        }

        // Check for Go project
        if path.join("go.mod").exists() {
            return ProjectType::Go;
        }

        // Check for Java project
        if path.join("pom.xml").exists() || path.join("build.gradle").exists() {
            return ProjectType::Java;
        }

        ProjectType::Unknown
    }

    /// Build a directory tree representation
    fn build_directory_tree(path: &Path, depth: usize, max_depth: usize) -> Result<Vec<String>> {
        if depth > max_depth {
            return Ok(vec![]);
        }

        let mut tree = Vec::new();
        let entries = fs::read_dir(path)?;

        let mut dirs: Vec<_> = entries
            .filter_map(|e| e.ok())
            .filter(|e| !e.file_name().to_string_lossy().starts_with('.'))
            .collect();

        // Sort: directories first, then files, both alphabetically
        dirs.sort_by(|a, b| {
            let a_is_dir = a.file_type().map(|t| t.is_dir()).unwrap_or(false);
            let b_is_dir = b.file_type().map(|t| t.is_dir()).unwrap_or(false);

            match (a_is_dir, b_is_dir) {
                (true, false) => std::cmp::Ordering::Less,
                (false, true) => std::cmp::Ordering::Greater,
                _ => a.file_name().cmp(&b.file_name()),
            }
        });

        let prefix = if depth == 0 {
            String::new()
        } else {
            "│   ".repeat(depth - 1)
        };

        for (i, entry) in dirs.iter().enumerate() {
            let name = entry.file_name().to_string_lossy().to_string();
            let is_last = i == dirs.len() - 1;
            let connector = if is_last { "└── " } else { "├── " };

            if let Ok(file_type) = entry.file_type() {
                if file_type.is_dir() {
                    // Skip excluded directories
                    if EXCLUDED_DIRS.contains(&name.as_str()) {
                        continue;
                    }

                    if depth == 0 {
                        tree.push(format!(
                            "{}{}",
                            name,
                            if name.ends_with('/') { "" } else { "/" }
                        ));
                    } else {
                        tree.push(format!("{}{}{}/", prefix, connector, name));
                    }

                    // Recursively add subdirectory contents
                    let sub_tree = Self::build_directory_tree(&entry.path(), depth + 1, max_depth)?;
                    tree.extend(sub_tree);
                } else if file_type.is_file() && depth > 0 {
                    tree.push(format!("{}{}{}", prefix, connector, name));
                }
            }
        }

        // Add root level files
        if depth == 0 {
            for entry in dirs.iter() {
                if let Ok(file_type) = entry.file_type() {
                    if file_type.is_file() {
                        let name = entry.file_name().to_string_lossy().to_string();
                        tree.push(name);
                    }
                }
            }
        }

        Ok(tree)
    }

    /// Extract build commands based on project type and configuration files
    fn extract_build_commands(path: &Path, project_type: &ProjectType) -> BuildConfig {
        match project_type {
            ProjectType::Rust => {
                let mut config = BuildConfig {
                    build_cmd: Some("cargo build".to_string()),
                    test_cmd: Some("cargo test".to_string()),
                    single_test_cmd: Some("cargo test <test_name>".to_string()),
                    lint_cmd: Some("cargo clippy".to_string()),
                    format_cmd: Some("cargo fmt".to_string()),
                };

                // Check for Makefile.toml (cargo-make)
                if path.join("Makefile.toml").exists() {
                    config.build_cmd = Some("cargo make".to_string());
                }

                config
            }
            ProjectType::NodeJs => {
                let mut config = BuildConfig {
                    build_cmd: Some("npm run build".to_string()),
                    test_cmd: Some("npm test".to_string()),
                    single_test_cmd: Some("npm test -- <test_name>".to_string()),
                    lint_cmd: Some("npm run lint".to_string()),
                    format_cmd: Some("npm run format".to_string()),
                };

                // Check for package.json scripts
                if let Ok(content) = fs::read_to_string(path.join("package.json")) {
                    if let Ok(pkg) = serde_json::from_str::<serde_json::Value>(&content) {
                        if let Some(scripts) = pkg.get("scripts") {
                            if scripts.get("lint").is_none() {
                                // Try common linters
                                if path.join(".eslintrc.js").exists()
                                    || path.join(".eslintrc.json").exists()
                                {
                                    config.lint_cmd = Some("npx eslint .".to_string());
                                }
                            }
                            if scripts.get("format").is_none() {
                                if path.join(".prettierrc").exists()
                                    || path.join(".prettierrc.json").exists()
                                {
                                    config.format_cmd = Some("npx prettier --write .".to_string());
                                }
                            }
                        }
                    }
                }

                // Check for pnpm or yarn
                if path.join("pnpm-lock.yaml").exists() {
                    config.build_cmd = Some("pnpm build".to_string());
                    config.test_cmd = Some("pnpm test".to_string());
                    config.lint_cmd = Some("pnpm lint".to_string());
                } else if path.join("yarn.lock").exists() {
                    config.build_cmd = Some("yarn build".to_string());
                    config.test_cmd = Some("yarn test".to_string());
                    config.lint_cmd = Some("yarn lint".to_string());
                }

                config
            }
            ProjectType::Python => {
                let mut config = BuildConfig {
                    build_cmd: None,
                    test_cmd: Some("pytest".to_string()),
                    single_test_cmd: Some("pytest <test_file>::<test_name>".to_string()),
                    lint_cmd: Some("ruff check .".to_string()),
                    format_cmd: Some("ruff format .".to_string()),
                };

                // Check for pyproject.toml
                if path.join("pyproject.toml").exists() {
                    config.build_cmd = Some("pip install -e .".to_string());
                }

                // Check for requirements.txt
                if path.join("requirements.txt").exists() {
                    config.build_cmd = Some("pip install -r requirements.txt".to_string());
                }

                config
            }
            ProjectType::Go => BuildConfig {
                build_cmd: Some("go build ./...".to_string()),
                test_cmd: Some("go test ./...".to_string()),
                single_test_cmd: Some("go test -run <test_name> ./...".to_string()),
                lint_cmd: Some("golangci-lint run".to_string()),
                format_cmd: Some("gofmt -s -w .".to_string()),
            },
            ProjectType::Java => {
                let mut config = BuildConfig::default();

                if path.join("pom.xml").exists() {
                    config.build_cmd = Some("mvn compile".to_string());
                    config.test_cmd = Some("mvn test".to_string());
                    config.lint_cmd = Some("mvn checkstyle:check".to_string());
                } else if path.join("build.gradle").exists()
                    || path.join("build.gradle.kts").exists()
                {
                    config.build_cmd = Some("gradle build".to_string());
                    config.test_cmd = Some("gradle test".to_string());
                    config.lint_cmd = Some("gradle spotlessCheck".to_string());
                }

                config
            }
            ProjectType::Unknown => BuildConfig::default(),
        }
    }

    /// Extract dependencies from project configuration files
    fn extract_dependencies(
        path: &Path,
        project_type: &ProjectType,
    ) -> Result<HashMap<String, String>> {
        let mut deps = HashMap::new();

        match project_type {
            ProjectType::Rust => {
                if let Ok(content) = fs::read_to_string(path.join("Cargo.toml")) {
                    // Simple parsing for [dependencies] section
                    let in_deps = content.lines().any(|line| {
                        line.starts_with("[dependencies]") || line.starts_with("[dev-dependencies]")
                    });

                    if in_deps {
                        for line in content.lines() {
                            if line.starts_with('[') && !line.contains("dependencies") {
                                continue;
                            }
                            if let Some((name, version)) = line.split_once('=') {
                                let name = name.trim().to_string();
                                let version = version.trim().trim_matches('"').to_string();
                                if !name.is_empty() && !version.is_empty() {
                                    deps.insert(name, version);
                                }
                            }
                        }
                    }
                }
            }
            ProjectType::NodeJs => {
                if let Ok(content) = fs::read_to_string(path.join("package.json")) {
                    if let Ok(pkg) = serde_json::from_str::<serde_json::Value>(&content) {
                        fn extract_deps(
                            obj: &serde_json::Value,
                            deps: &mut HashMap<String, String>,
                        ) {
                            if let Some(deps_obj) = obj.as_object() {
                                for (name, version) in deps_obj {
                                    if let Some(v) = version.as_str() {
                                        deps.insert(name.clone(), v.to_string());
                                    }
                                }
                            }
                        }

                        if let Some(dependencies) = pkg.get("dependencies") {
                            extract_deps(dependencies, &mut deps);
                        }
                        if let Some(dev_deps) = pkg.get("devDependencies") {
                            extract_deps(dev_deps, &mut deps);
                        }
                    }
                }
            }
            ProjectType::Python => {
                if let Ok(content) = fs::read_to_string(path.join("requirements.txt")) {
                    for line in content.lines() {
                        let line = line.trim();
                        if line.is_empty() || line.starts_with('#') {
                            continue;
                        }
                        // Handle various formats: pkg==1.0, pkg>=1.0, pkg~=1.0, pkg
                        let name = line
                            .split(&['=', '>', '<', '~', ';', ' '][..])
                            .next()
                            .unwrap_or(line)
                            .to_string();
                        if !name.is_empty() {
                            deps.insert(name, "latest".to_string());
                        }
                    }
                }
            }
            ProjectType::Go => {
                if let Ok(content) = fs::read_to_string(path.join("go.mod")) {
                    let in_require = content.lines().any(|line| line.trim() == "require (");

                    for line in content.lines() {
                        let line = line.trim();
                        if in_require && line == ")" {
                            continue;
                        }
                        if line.starts_with("require (") {
                            continue;
                        }
                        if line.contains(" v") {
                            let parts: Vec<&str> = line.split_whitespace().collect();
                            if parts.len() >= 2 {
                                deps.insert(parts[0].to_string(), parts[1].to_string());
                            }
                        }
                    }
                }
            }
            ProjectType::Java => {
                // Maven dependencies
                if let Ok(content) = fs::read_to_string(path.join("pom.xml")) {
                    for line in content.lines() {
                        let line = line.trim();
                        if line.contains("<groupId>") && line.contains("</groupId>") {
                            let group = line
                                .split("<groupId>")
                                .nth(1)
                                .and_then(|s| s.split("</groupId>").next())
                                .unwrap_or("")
                                .to_string();
                            if !group.is_empty() {
                                deps.insert(group, "maven".to_string());
                            }
                        }
                    }
                }
            }
            ProjectType::Unknown => {}
        }

        Ok(deps)
    }

    /// Find existing rule files in the project
    fn find_existing_rules(path: &Path) -> Result<Vec<String>> {
        let mut rules = Vec::new();

        for rule_file in RULE_FILES {
            let rule_path = path.join(rule_file);
            if rule_path.exists() {
                if let Ok(content) = fs::read_to_string(&rule_path) {
                    if !content.trim().is_empty() {
                        rules.push(format!("From {}:\n{}", rule_file, content));
                    }
                }
            }
        }

        // Check .cursor/rules/ directory
        let cursor_rules_dir = path.join(".cursor/rules");
        if cursor_rules_dir.exists() && cursor_rules_dir.is_dir() {
            if let Ok(entries) = fs::read_dir(&cursor_rules_dir) {
                for entry in entries.filter_map(|e| e.ok()) {
                    let path = entry.path();
                    if path.extension().map(|e| e == "md").unwrap_or(false) {
                        if let Ok(content) = fs::read_to_string(&path) {
                            let name = path.file_name().unwrap().to_string_lossy();
                            rules.push(format!("From .cursor/rules/{}:\n{}", name, content));
                        }
                    }
                }
            }
        }

        Ok(rules)
    }

    /// Infer code style based on project type
    fn infer_code_style(project_type: &ProjectType) -> CodeStyle {
        match project_type {
            ProjectType::Rust => CodeStyle {
                naming_conventions: vec![
                    "Types: PascalCase (e.g., MyStruct)".to_string(),
                    "Functions/variables: snake_case (e.g., my_function)".to_string(),
                    "Constants: SCREAMING_SNAKE_CASE (e.g., MAX_RETRIES)".to_string(),
                    "Modules: snake_case (e.g., my_module)".to_string(),
                ],
                error_handling: Some(
                    "Use Result<T, E> and thiserror for custom errors".to_string(),
                ),
                import_style: Some(
                    "Group imports: std -> external -> crate (use rustfmt)".to_string(),
                ),
                formatting_rules: vec!["Use rustfmt for consistent formatting".to_string()],
            },
            ProjectType::NodeJs => CodeStyle {
                naming_conventions: vec![
                    "Types/Interfaces: PascalCase (e.g., MyInterface)".to_string(),
                    "Functions/variables: camelCase (e.g., myFunction)".to_string(),
                    "Constants: SCREAMING_SNAKE_CASE (e.g., MAX_RETRIES)".to_string(),
                    "Files: kebab-case (e.g., my-component.tsx)".to_string(),
                ],
                error_handling: Some("Use try/catch and custom error classes".to_string()),
                import_style: Some("Use ES modules with explicit file extensions".to_string()),
                formatting_rules: vec!["Use Prettier for consistent formatting".to_string()],
            },
            ProjectType::Python => CodeStyle {
                naming_conventions: vec![
                    "Classes: PascalCase (e.g., MyClass)".to_string(),
                    "Functions/variables: snake_case (e.g., my_function)".to_string(),
                    "Constants: SCREAMING_SNAKE_CASE (e.g., MAX_RETRIES)".to_string(),
                ],
                error_handling: Some("Use custom exception classes".to_string()),
                import_style: Some("Group imports: stdlib -> third-party -> local".to_string()),
                formatting_rules: vec!["Follow PEP 8".to_string()],
            },
            ProjectType::Go => CodeStyle {
                naming_conventions: vec![
                    "Exported names: PascalCase".to_string(),
                    "Unexported names: camelCase".to_string(),
                    "Acronyms: same case (e.g., URLParser, not UrlParser)".to_string(),
                ],
                error_handling: Some("Return errors as last return value".to_string()),
                import_style: Some("Group imports: stdlib -> third-party -> local".to_string()),
                formatting_rules: vec!["Use gofmt for formatting".to_string()],
            },
            ProjectType::Java => CodeStyle {
                naming_conventions: vec![
                    "Classes: PascalCase (e.g., MyClass)".to_string(),
                    "Methods/variables: camelCase (e.g., myMethod)".to_string(),
                    "Constants: SCREAMING_SNAKE_CASE".to_string(),
                    "Packages: lowercase (e.g., com.example.app)".to_string(),
                ],
                error_handling: Some("Use checked exceptions for recoverable errors".to_string()),
                import_style: Some("Import specific classes, avoid wildcard imports".to_string()),
                formatting_rules: vec!["Follow Google Java Style Guide".to_string()],
            },
            ProjectType::Unknown => CodeStyle::default(),
        }
    }

    /// Find entry points based on project type
    fn find_entry_points(path: &Path, project_type: &ProjectType) -> Result<Vec<String>> {
        let mut entry_points = Vec::new();

        match project_type {
            ProjectType::Rust => {
                if path.join("src/main.rs").exists() {
                    entry_points.push("src/main.rs".to_string());
                }
                if path.join("src/lib.rs").exists() {
                    entry_points.push("src/lib.rs".to_string());
                }
            }
            ProjectType::NodeJs => {
                // Check package.json for main entry
                if let Ok(content) = fs::read_to_string(path.join("package.json")) {
                    if let Ok(pkg) = serde_json::from_str::<serde_json::Value>(&content) {
                        if let Some(main) = pkg.get("main").and_then(|m| m.as_str()) {
                            entry_points.push(main.to_string());
                        }
                    }
                }

                // Common entry points
                for entry in &["src/index.ts", "src/index.js", "index.ts", "index.js"] {
                    if path.join(entry).exists() && !entry_points.contains(&entry.to_string()) {
                        entry_points.push(entry.to_string());
                    }
                }
            }
            ProjectType::Python => {
                for entry in &["main.py", "app.py", "__main__.py", "src/__init__.py"] {
                    if path.join(entry).exists() {
                        entry_points.push(entry.to_string());
                    }
                }
            }
            ProjectType::Go => {
                if path.join("main.go").exists() {
                    entry_points.push("main.go".to_string());
                }
                if path.join("cmd").is_dir() {
                    if let Ok(entries) = fs::read_dir(path.join("cmd")) {
                        for entry in entries.filter_map(|e| e.ok()) {
                            if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                                let main_path =
                                    format!("cmd/{}/main.go", entry.file_name().to_string_lossy());
                                if path.join(&main_path).exists() {
                                    entry_points.push(main_path);
                                }
                            }
                        }
                    }
                }
            }
            ProjectType::Java => {
                if path.join("src/main/java").is_dir() {
                    if let Ok(entries) = fs::read_dir(path.join("src/main/java")) {
                        for entry in entries.filter_map(|e| e.ok()) {
                            let file_path = entry.path();
                            if file_path.extension().map(|e| e == "java").unwrap_or(false) {
                                let name = entry.file_name().to_string_lossy().to_string();
                                if name.starts_with("Main") || name.starts_with("Application") {
                                    entry_points.push(format!("src/main/java/{}", name));
                                }
                            }
                        }
                    }
                }
            }
            ProjectType::Unknown => {}
        }

        Ok(entry_points)
    }
}

impl Default for ProjectExplorer {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    fn create_rust_project() -> TempDir {
        let dir = TempDir::new().expect("create temp dir");
        fs::write(
            dir.path().join("Cargo.toml"),
            r#"
[package]
name = "test-project"
version = "0.1.0"

[dependencies]
serde = "1.0"
tokio = "1.0"
"#,
        )
        .expect("write Cargo.toml");
        fs::create_dir(dir.path().join("src")).expect("create src dir");
        fs::write(dir.path().join("src/main.rs"), "fn main() {}").expect("write main.rs");
        fs::write(dir.path().join("src/lib.rs"), "pub fn lib() {}").expect("write lib.rs");
        dir
    }

    fn create_nodejs_project() -> TempDir {
        let dir = TempDir::new().expect("create temp dir");
        fs::write(
            dir.path().join("package.json"),
            r#"
{
    "name": "test-project",
    "version": "1.0.0",
    "main": "src/index.js",
    "scripts": {
        "build": "tsc",
        "test": "jest"
    },
    "dependencies": {
        "express": "^4.18.0"
    },
    "devDependencies": {
        "typescript": "^5.0.0"
    }
}
"#,
        )
        .expect("write package.json");
        fs::create_dir(dir.path().join("src")).expect("create src dir");
        fs::write(dir.path().join("src/index.js"), "console.log('hello')").expect("write index.js");
        dir
    }

    fn create_python_project() -> TempDir {
        let dir = TempDir::new().expect("create temp dir");
        fs::write(
            dir.path().join("pyproject.toml"),
            r#"
[project]
name = "test-project"
version = "0.1.0"
"#,
        )
        .expect("write pyproject.toml");
        fs::write(
            dir.path().join("requirements.txt"),
            "requests>=2.28.0\nflask==2.0.0",
        )
        .expect("write requirements.txt");
        fs::write(dir.path().join("main.py"), "print('hello')").expect("write main.py");
        dir
    }

    fn create_go_project() -> TempDir {
        let dir = TempDir::new().expect("create temp dir");
        fs::write(
            dir.path().join("go.mod"),
            r#"
module test-project

go 1.21

require (
    github.com/gin-gonic/gin v1.9.0
)
"#,
        )
        .expect("write go.mod");
        fs::write(dir.path().join("main.go"), "package main\n\nfunc main() {}")
            .expect("write main.go");
        dir
    }

    fn create_java_project() -> TempDir {
        let dir = TempDir::new().expect("create temp dir");
        fs::write(
            dir.path().join("pom.xml"),
            r#"
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>test-project</artifactId>
    <version>1.0.0</version>
    <dependencies>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-core</artifactId>
            <version>5.0.0</version>
        </dependency>
    </dependencies>
</project>
"#,
        )
        .expect("write pom.xml");
        dir
    }

    #[test]
    fn explore_nonexistent_path_returns_error() {
        let explorer = ProjectExplorer::new();
        let result = explorer.explore(Path::new("/nonexistent/path/12345"));
        assert!(result.is_err());
        if let Err(CoreError::InvalidInput(msg)) = result {
            assert!(msg.contains("does not exist"));
        } else {
            panic!("Expected InvalidInput error");
        }
    }

    #[test]
    fn explore_file_path_returns_error() {
        let dir = TempDir::new().expect("create temp dir");
        let file_path = dir.path().join("test.txt");
        fs::write(&file_path, "test").expect("write file");

        let explorer = ProjectExplorer::new();
        let result = explorer.explore(&file_path);
        assert!(result.is_err());
        if let Err(CoreError::InvalidInput(msg)) = result {
            assert!(msg.contains("not a directory"));
        } else {
            panic!("Expected InvalidInput error");
        }
    }

    #[test]
    fn detect_rust_project() {
        let dir = create_rust_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore rust project");

        assert_eq!(knowledge.project_type, ProjectType::Rust);
        assert!(knowledge.name.contains("test-project") || !knowledge.name.is_empty());
    }

    #[test]
    fn detect_nodejs_project() {
        let dir = create_nodejs_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore nodejs project");

        assert_eq!(knowledge.project_type, ProjectType::NodeJs);
    }

    #[test]
    fn detect_python_project() {
        let dir = create_python_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore python project");

        assert_eq!(knowledge.project_type, ProjectType::Python);
    }

    #[test]
    fn detect_go_project() {
        let dir = create_go_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore go project");

        assert_eq!(knowledge.project_type, ProjectType::Go);
    }

    #[test]
    fn detect_java_project() {
        let dir = create_java_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore java project");

        assert_eq!(knowledge.project_type, ProjectType::Java);
    }

    #[test]
    fn extract_rust_build_commands() {
        let dir = create_rust_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore rust project");

        assert_eq!(
            knowledge.build_config.build_cmd,
            Some("cargo build".to_string())
        );
        assert_eq!(
            knowledge.build_config.test_cmd,
            Some("cargo test".to_string())
        );
        assert_eq!(
            knowledge.build_config.lint_cmd,
            Some("cargo clippy".to_string())
        );
        assert_eq!(
            knowledge.build_config.format_cmd,
            Some("cargo fmt".to_string())
        );
    }

    #[test]
    fn extract_nodejs_build_commands() {
        let dir = create_nodejs_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore nodejs project");

        assert_eq!(
            knowledge.build_config.build_cmd,
            Some("npm run build".to_string())
        );
        assert_eq!(
            knowledge.build_config.test_cmd,
            Some("npm test".to_string())
        );
    }

    #[test]
    fn extract_python_build_commands() {
        let dir = create_python_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore python project");

        assert!(knowledge.build_config.test_cmd.is_some());
        assert!(knowledge.build_config.lint_cmd.is_some());
    }

    #[test]
    fn extract_nodejs_dependencies() {
        let dir = create_nodejs_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore nodejs project");

        assert!(knowledge.dependencies.contains_key("express"));
        assert!(knowledge.dependencies.contains_key("typescript"));
    }

    #[test]
    fn extract_python_dependencies() {
        let dir = create_python_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore python project");

        assert!(knowledge.dependencies.contains_key("requests"));
        assert!(knowledge.dependencies.contains_key("flask"));
    }

    #[test]
    fn find_rust_entry_points() {
        let dir = create_rust_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore rust project");

        assert!(knowledge.entry_points.contains(&"src/main.rs".to_string()));
        assert!(knowledge.entry_points.contains(&"src/lib.rs".to_string()));
    }

    #[test]
    fn find_nodejs_entry_points() {
        let dir = create_nodejs_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore nodejs project");

        assert!(knowledge.entry_points.contains(&"src/index.js".to_string()));
    }

    #[test]
    fn find_existing_rules() {
        let dir = create_rust_project();
        fs::write(
            dir.path().join("CLAUDE.md"),
            "# Project Rules\n\nAlways write tests.",
        )
        .expect("write CLAUDE.md");

        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore project with rules");

        assert!(!knowledge.existing_rules.is_empty());
        assert!(knowledge.existing_rules[0].contains("CLAUDE.md"));
        assert!(knowledge.existing_rules[0].contains("Always write tests"));
    }

    #[test]
    fn exclude_node_modules_from_tree() {
        let dir = create_nodejs_project();
        fs::create_dir(dir.path().join("node_modules")).expect("create node_modules");
        fs::create_dir_all(dir.path().join("node_modules/lodash")).expect("create lodash");

        let explorer = ProjectExplorer::new();
        let knowledge = explorer
            .explore(dir.path())
            .expect("explore nodejs project");

        // Check that node_modules is not in the directory tree
        for line in &knowledge.directory_structure {
            assert!(!line.contains("node_modules"));
        }
    }

    #[test]
    fn exclude_target_from_tree() {
        let dir = create_rust_project();
        fs::create_dir(dir.path().join("target")).expect("create target");
        fs::create_dir_all(dir.path().join("target/debug")).expect("create debug");

        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore rust project");

        // Check that target is not in the directory tree
        for line in &knowledge.directory_structure {
            assert!(!line.contains("target"));
        }
    }

    #[test]
    fn unknown_project_type_for_empty_directory() {
        let dir = TempDir::new().expect("create temp dir");

        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore empty project");

        assert_eq!(knowledge.project_type, ProjectType::Unknown);
    }

    #[test]
    fn directory_tree_has_content() {
        let dir = create_rust_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore rust project");

        assert!(!knowledge.directory_structure.is_empty());
    }

    #[test]
    fn code_style_rust_naming_conventions() {
        let dir = create_rust_project();
        let explorer = ProjectExplorer::new();
        let knowledge = explorer.explore(dir.path()).expect("explore rust project");

        assert!(!knowledge.code_style.naming_conventions.is_empty());
        assert!(knowledge
            .code_style
            .naming_conventions
            .iter()
            .any(|c| c.contains("PascalCase")));
        assert!(knowledge
            .code_style
            .naming_conventions
            .iter()
            .any(|c| c.contains("snake_case")));
    }
}
