//! AGENTS.md generator for providing context to AI coding agents

use smanclaw_types::{CodeStyle, ModuleInfo, ProjectKnowledge, ProjectType};

/// Generator for AGENTS.md files
pub struct AgentsGenerator;

impl AgentsGenerator {
    /// Generate AGENTS.md content based on ProjectKnowledge
    pub fn generate(knowledge: &ProjectKnowledge) -> String {
        let mut content = String::new();

        // Header
        content.push_str(&format!("# {}\n\n", knowledge.name));

        // Project description if available
        if let Some(desc) = &knowledge.description {
            content.push_str(&format!("{}\n\n", desc));
        }

        // Project structure
        content.push_str("## Project Structure\n\n");
        if knowledge.directory_structure.is_empty() {
            content.push_str("```\n(project root)\n```\n\n");
        } else {
            content.push_str("```\n");
            for line in &knowledge.directory_structure {
                content.push_str(&format!("{}\n", line));
            }
            content.push_str("```\n\n");
        }

        // Build commands
        content.push_str("## Build Commands\n\n");
        Self::add_build_commands(&mut content, &knowledge.build_config);

        // Code style
        content.push_str("## Code Style\n\n");
        Self::add_code_style(&mut content, &knowledge.code_style, &knowledge.project_type);

        // Module responsibilities
        if !knowledge.modules.is_empty() {
            content.push_str("## Module Responsibilities\n\n");
            Self::add_modules(&mut content, &knowledge.modules);
        }

        // Entry points
        if !knowledge.entry_points.is_empty() {
            content.push_str("## Entry Points\n\n");
            for entry in &knowledge.entry_points {
                content.push_str(&format!("- `{}`\n", entry));
            }
            content.push('\n');
        }

        // Existing rules
        if !knowledge.existing_rules.is_empty() {
            content.push_str("## Additional Rules\n\n");
            for rule in &knowledge.existing_rules {
                content.push_str(&format!("{}\n\n", rule));
            }
        }

        // Dependencies context
        if !knowledge.dependencies.is_empty() {
            content.push_str("## Key Dependencies\n\n");
            let mut deps: Vec<_> = knowledge.dependencies.iter().collect();
            deps.sort_by_key(|(k, _)| *k);
            for (name, version) in deps {
                content.push_str(&format!("- **{}**: {}\n", name, version));
            }
            content.push('\n');
        }

        content.trim_end().to_string() + "\n"
    }

    /// Improve existing AGENTS.md with new knowledge
    pub fn improve(existing: &str, knowledge: &ProjectKnowledge) -> String {
        // If existing content is empty or very short, just generate fresh
        if existing.trim().is_empty() || existing.lines().count() < 5 {
            return Self::generate(knowledge);
        }

        // Merge strategy: keep existing structure but update/add sections
        let mut content = existing.to_string();

        // Ensure header matches project name
        if let Some(first_line) = content.lines().next() {
            if !first_line.starts_with(&format!("# {}", knowledge.name)) {
                content = format!("# {}\n\n{}", knowledge.name, content);
            }
        }

        // Add missing build commands if not present
        if !content.contains("## Build Commands") && !content.contains("## 构建命令") {
            let build_section = Self::generate_build_section(&knowledge.build_config);
            content.push_str("\n\n");
            content.push_str(&build_section);
        }

        // Add missing code style if not present
        if !content.contains("## Code Style") && !content.contains("## 代码规范") {
            let style_section =
                Self::generate_code_style_section(&knowledge.code_style, &knowledge.project_type);
            content.push_str("\n\n");
            content.push_str(&style_section);
        }

        content.trim_end().to_string() + "\n"
    }

    fn add_build_commands(content: &mut String, build_config: &smanclaw_types::BuildConfig) {
        if let Some(cmd) = &build_config.build_cmd {
            content.push_str(&format!("- **Build**: `{}`\n", cmd));
        }
        if let Some(cmd) = &build_config.test_cmd {
            content.push_str(&format!("- **Test (all)**: `{}`\n", cmd));
        }
        if let Some(cmd) = &build_config.single_test_cmd {
            content.push_str(&format!("- **Test (single)**: `{}`\n", cmd));
        }
        if let Some(cmd) = &build_config.lint_cmd {
            content.push_str(&format!("- **Lint**: `{}`\n", cmd));
        }
        if let Some(cmd) = &build_config.format_cmd {
            content.push_str(&format!("- **Format**: `{}`\n", cmd));
        }
        content.push('\n');
    }

    fn add_code_style(content: &mut String, style: &CodeStyle, project_type: &ProjectType) {
        // Naming conventions
        if !style.naming_conventions.is_empty() {
            content.push_str("### Naming Conventions\n\n");
            for convention in &style.naming_conventions {
                content.push_str(&format!("- {}\n", convention));
            }
            content.push('\n');
        }

        // Error handling
        if let Some(error_handling) = &style.error_handling {
            content.push_str("### Error Handling\n\n");
            content.push_str(&format!("{}\n\n", error_handling));
        }

        // Import style
        if let Some(import_style) = &style.import_style {
            content.push_str("### Import Style\n\n");
            content.push_str(&format!("{}\n\n", import_style));
        }

        // Formatting rules
        if !style.formatting_rules.is_empty() {
            content.push_str("### Formatting\n\n");
            for rule in &style.formatting_rules {
                content.push_str(&format!("- {}\n", rule));
            }
            content.push('\n');
        }

        // Project type specific guidance
        match project_type {
            ProjectType::Rust => {
                content.push_str("### Rust-Specific Guidelines\n\n");
                content.push_str("- Use `Result<T, E>` for fallible operations\n");
                content.push_str(
                    "- Prefer `?` operator over `unwrap()`/`expect()` in production code\n",
                );
                content.push_str("- Document public APIs with `///` doc comments\n");
                content.push_str("- Use `clippy` for linting\n\n");
            }
            ProjectType::NodeJs => {
                content.push_str("### TypeScript/JavaScript Guidelines\n\n");
                content.push_str("- Use strict TypeScript configuration\n");
                content.push_str("- Prefer `async/await` over raw promises\n");
                content.push_str("- Use ES modules syntax\n");
                content.push_str("- Handle errors with try/catch blocks\n\n");
            }
            ProjectType::Python => {
                content.push_str("### Python Guidelines\n\n");
                content.push_str("- Follow PEP 8 style guide\n");
                content.push_str("- Use type hints for function signatures\n");
                content.push_str("- Use virtual environment for dependencies\n\n");
            }
            ProjectType::Go => {
                content.push_str("### Go Guidelines\n\n");
                content.push_str("- Follow effective Go guidelines\n");
                content.push_str("- Use `gofmt` for formatting\n");
                content.push_str("- Handle errors explicitly\n\n");
            }
            ProjectType::Java => {
                content.push_str("### Java Guidelines\n\n");
                content.push_str("- Use camelCase for methods and variables\n");
                content.push_str("- Use PascalCase for classes\n");
                content.push_str("- Prefer composition over inheritance\n\n");
            }
            ProjectType::Unknown => {}
        }
    }

    fn add_modules(content: &mut String, modules: &[ModuleInfo]) {
        for module in modules {
            content.push_str(&format!("### `{}`\n\n", module.name));
            if !module.description.is_empty() {
                content.push_str(&format!("{}\n\n", module.description));
            }
            if !module.responsibilities.is_empty() {
                content.push_str("**Responsibilities:**\n");
                for resp in &module.responsibilities {
                    content.push_str(&format!("- {}\n", resp));
                }
                content.push('\n');
            }
        }
    }

    fn generate_build_section(build_config: &smanclaw_types::BuildConfig) -> String {
        let mut section = "## Build Commands\n\n".to_string();
        Self::add_build_commands(&mut section, build_config);
        section
    }

    fn generate_code_style_section(style: &CodeStyle, project_type: &ProjectType) -> String {
        let mut section = "## Code Style\n\n".to_string();
        Self::add_code_style(&mut section, style, project_type);
        section
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn create_rust_knowledge() -> ProjectKnowledge {
        let mut knowledge = ProjectKnowledge::rust_project("my-rust-app");
        knowledge.description = Some("A sample Rust application".to_string());
        knowledge.directory_structure = vec![
            "my-rust-app/".to_string(),
            "├── Cargo.toml".to_string(),
            "├── src/".to_string(),
            "│   ├── main.rs".to_string(),
            "│   └── lib.rs".to_string(),
            "└── tests/".to_string(),
        ];
        knowledge.modules = vec![ModuleInfo {
            name: "src/lib.rs".to_string(),
            description: "Main library entry point".to_string(),
            responsibilities: vec![
                "Export public API".to_string(),
                "Core business logic".to_string(),
            ],
        }];
        knowledge.entry_points = vec!["src/main.rs".to_string()];
        knowledge.dependencies = {
            let mut deps = HashMap::new();
            deps.insert("serde".to_string(), "1.0".to_string());
            deps.insert("tokio".to_string(), "1.0".to_string());
            deps
        };
        knowledge
    }

    fn create_nodejs_knowledge() -> ProjectKnowledge {
        let mut knowledge = ProjectKnowledge::nodejs_project("my-node-app");
        knowledge.description = Some("A sample Node.js application".to_string());
        knowledge.directory_structure = vec![
            "my-node-app/".to_string(),
            "├── package.json".to_string(),
            "├── src/".to_string(),
            "│   └── index.ts".to_string(),
            "└── tests/".to_string(),
        ];
        knowledge
    }

    #[test]
    fn generate_rust_project() {
        let knowledge = create_rust_knowledge();
        let content = AgentsGenerator::generate(&knowledge);

        // Check header
        assert!(content.starts_with("# my-rust-app"));
        assert!(content.contains("A sample Rust application"));

        // Check structure
        assert!(content.contains("## Project Structure"));
        assert!(content.contains("├── Cargo.toml"));

        // Check build commands
        assert!(content.contains("## Build Commands"));
        assert!(content.contains("`cargo build`"));
        assert!(content.contains("`cargo test`"));
        assert!(content.contains("`cargo test <test_name>`"));
        assert!(content.contains("`cargo clippy`"));

        // Check code style
        assert!(content.contains("## Code Style"));
        assert!(content.contains("snake_case"));
        assert!(content.contains("PascalCase"));

        // Check modules
        assert!(content.contains("## Module Responsibilities"));
        assert!(content.contains("src/lib.rs"));
        assert!(content.contains("Main library entry point"));

        // Check entry points
        assert!(content.contains("## Entry Points"));
        assert!(content.contains("src/main.rs"));

        // Check dependencies
        assert!(content.contains("## Key Dependencies"));
        assert!(content.contains("serde"));
        assert!(content.contains("tokio"));
    }

    #[test]
    fn generate_nodejs_project() {
        let knowledge = create_nodejs_knowledge();
        let content = AgentsGenerator::generate(&knowledge);

        // Check header
        assert!(content.starts_with("# my-node-app"));
        assert!(content.contains("A sample Node.js application"));

        // Check build commands
        assert!(content.contains("`npm run build`"));
        assert!(content.contains("`npm test`"));

        // Check code style
        assert!(content.contains("camelCase"));
        assert!(content.contains("kebab-case"));
    }

    #[test]
    fn generate_minimal_project() {
        let knowledge = ProjectKnowledge::new("minimal-app");
        let content = AgentsGenerator::generate(&knowledge);

        assert!(content.starts_with("# minimal-app"));
        assert!(content.contains("## Project Structure"));
        assert!(content.contains("## Build Commands"));
        assert!(content.contains("## Code Style"));
    }

    #[test]
    fn improve_empty_existing() {
        let knowledge = create_rust_knowledge();
        let improved = AgentsGenerator::improve("", &knowledge);

        // Should generate fresh content
        assert!(improved.starts_with("# my-rust-app"));
        assert!(improved.contains("## Build Commands"));
    }

    #[test]
    fn improve_short_existing() {
        let knowledge = create_rust_knowledge();
        let existing = "# my-rust-app\n\nSome basic info.\n";
        let improved = AgentsGenerator::improve(existing, &knowledge);

        // Should generate fresh content
        assert!(improved.contains("## Build Commands"));
    }

    #[test]
    fn improve_keeps_existing_content() {
        let knowledge = create_rust_knowledge();
        let existing = r#"# my-rust-app

A sample Rust application

## Project Structure

```
my-rust-app/
├── Cargo.toml
└── src/
```

## Build Commands

- **Build**: `cargo build`
"#;
        let improved = AgentsGenerator::improve(existing, &knowledge);

        // Should keep existing content
        assert!(improved.contains("A sample Rust application"));
        assert!(improved.contains("`cargo build`"));

        // Should add missing sections
        assert!(improved.contains("## Code Style"));
    }

    #[test]
    fn improve_updates_header_if_needed() {
        let knowledge = create_rust_knowledge();
        let existing = "# old-name\n\nSome content.\n\n## Code Style\n\n- Test\n";
        let improved = AgentsGenerator::improve(existing, &knowledge);

        // Should update header to match knowledge name
        assert!(improved.starts_with("# my-rust-app"));
    }

    #[test]
    fn existing_rules_are_included() {
        let mut knowledge = ProjectKnowledge::rust_project("test-app");
        knowledge.existing_rules = vec![
            "Always use meaningful variable names.".to_string(),
            "Never commit secrets.".to_string(),
        ];
        let content = AgentsGenerator::generate(&knowledge);

        assert!(content.contains("## Additional Rules"));
        assert!(content.contains("Always use meaningful variable names"));
        assert!(content.contains("Never commit secrets"));
    }

    #[test]
    fn python_project_specific_guidelines() {
        let mut knowledge = ProjectKnowledge::new("py-app");
        knowledge.project_type = ProjectType::Python;
        knowledge.code_style.error_handling = Some("Use custom exception classes".to_string());
        let content = AgentsGenerator::generate(&knowledge);

        assert!(content.contains("### Python Guidelines"));
        assert!(content.contains("PEP 8"));
    }

    #[test]
    fn go_project_specific_guidelines() {
        let mut knowledge = ProjectKnowledge::new("go-app");
        knowledge.project_type = ProjectType::Go;
        let content = AgentsGenerator::generate(&knowledge);

        assert!(content.contains("### Go Guidelines"));
        assert!(content.contains("effective Go"));
    }

    #[test]
    fn java_project_specific_guidelines() {
        let mut knowledge = ProjectKnowledge::new("java-app");
        knowledge.project_type = ProjectType::Java;
        let content = AgentsGenerator::generate(&knowledge);

        assert!(content.contains("### Java Guidelines"));
        assert!(content.contains("camelCase"));
    }

    #[test]
    fn content_ends_with_newline() {
        let knowledge = ProjectKnowledge::new("test-app");
        let content = AgentsGenerator::generate(&knowledge);

        assert!(content.ends_with('\n'));
    }
}
