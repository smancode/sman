<script lang="ts">
    import hljs from "highlight.js";

    interface Props {
        code: string;
        language: string;
    }

    let { code, language }: Props = $props();

    let copied = $state(false);

    function normalizeLanguage(lang: string): string {
        const value = lang.trim().toLowerCase();
        const aliases: Record<string, string> = {
            js: "javascript",
            ts: "typescript",
            jsx: "javascript",
            tsx: "typescript",
            py: "python",
            sh: "bash",
            zsh: "bash",
            shell: "bash",
            yml: "yaml",
            md: "markdown",
            csharp: "csharp",
            "c#": "csharp",
            "c++": "cpp",
            plaintext: "plaintext",
            text: "plaintext",
        };
        return aliases[value] || value;
    }

    function highlightCode(source: string, lang: string): string {
        const normalized = normalizeLanguage(lang);
        if (normalized !== "plaintext" && hljs.getLanguage(normalized)) {
            return hljs.highlight(source, {
                language: normalized,
                ignoreIllegals: true,
            }).value;
        }
        return hljs.highlightAuto(source).value;
    }

    let normalizedLanguage = $derived(normalizeLanguage(language));
    let highlightedCode = $derived(highlightCode(code, language));

    async function copyToClipboard() {
        try {
            await navigator.clipboard.writeText(code);
            copied = true;
            setTimeout(() => {
                copied = false;
            }, 2000);
        } catch (err) {
            console.error("Failed to copy:", err);
        }
    }

    // Get display name for language
    function getLanguageDisplayName(lang: string): string {
        const displayNames: Record<string, string> = {
            typescript: "TypeScript",
            javascript: "JavaScript",
            python: "Python",
            rust: "Rust",
            go: "Go",
            java: "Java",
            kotlin: "Kotlin",
            swift: "Swift",
            c: "C",
            cpp: "C++",
            csharp: "C#",
            ruby: "Ruby",
            php: "PHP",
            shell: "Shell",
            bash: "Bash",
            sql: "SQL",
            json: "JSON",
            yaml: "YAML",
            xml: "XML",
            html: "HTML",
            css: "CSS",
            svelte: "Svelte",
            vue: "Vue",
            jsx: "JSX",
            tsx: "TSX",
            markdown: "Markdown",
            plaintext: "Text",
        };
        const normalized = normalizeLanguage(lang);
        return displayNames[normalized] || lang;
    }
</script>

<div class="code-block">
    <div class="header">
        <span class="language">{getLanguageDisplayName(language)}</span>
        <button
            class="copy-btn"
            onclick={copyToClipboard}
            aria-label="Copy code"
        >
            {#if copied}
                <svg
                    xmlns="http://www.w3.org/2000/svg"
                    width="16"
                    height="16"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                >
                    <path d="M20 6L9 17l-5-5" />
                </svg>
                <span>Copied!</span>
            {:else}
                <svg
                    xmlns="http://www.w3.org/2000/svg"
                    width="16"
                    height="16"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                >
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                    <path
                        d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"
                    />
                </svg>
                <span>Copy</span>
            {/if}
        </button>
    </div>

    <div class="content">
        <pre><code class={`hljs language-${normalizedLanguage}`}
                >{@html highlightedCode}</code
            ></pre>
    </div>
</div>

<style>
    .code-block {
        margin: 0.5rem 0;
        background-color: #f8f8fa;
        border-radius: 10px;
        overflow: hidden;
        border: 1px solid var(--border);
    }

    .header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0.5rem 1rem;
        background-color: #f0f1f4;
        border-bottom: 1px solid var(--border);
    }

    .language {
        font-size: 0.75rem;
        font-weight: 500;
        color: var(--text-secondary);
    }

    .copy-btn {
        display: flex;
        align-items: center;
        gap: 0.25rem;
        padding: 0.25rem 0.5rem;
        font-size: 0.75rem;
        color: var(--text-secondary);
        background-color: transparent;
        border-radius: 4px;
        transition: all 0.15s;
    }

    .copy-btn:hover {
        color: var(--text-primary);
        background-color: rgba(var(--accent-rgb), 0.08);
    }

    .content {
        overflow-x: auto;
        padding: 1rem;
    }

    pre {
        margin: 0;
        font-family: "JetBrains Mono", Menlo, Monaco, monospace;
        font-size: 0.8125rem;
        line-height: 1.6;
        color: var(--text-primary);
    }

    pre code {
        font-family: inherit;
        white-space: pre;
        display: block;
    }
</style>
