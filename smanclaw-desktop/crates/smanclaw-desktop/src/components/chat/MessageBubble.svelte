<script lang="ts">
    import MarkdownIt from "markdown-it";
    import CodeBlock from "./CodeBlock.svelte";
    import MermaidRenderer from "./MermaidRenderer.svelte";
    import type { Message } from "../../lib/types";

    interface Props {
        message: Message;
    }

    let { message }: Props = $props();

    const markdown = new MarkdownIt({
        html: false,
        linkify: true,
        breaks: true,
    });

    function formatTime(timestamp: number): string {
        const date = new Date(timestamp);
        return date.toLocaleTimeString("zh-CN", {
            hour: "2-digit",
            minute: "2-digit",
        });
    }

    // Parse message content to identify code blocks
    function parseContent(content: string): Array<{
        type: "text" | "code" | "mermaid";
        content: string;
        language?: string;
    }> {
        const parts: Array<{
            type: "text" | "code" | "mermaid";
            content: string;
            language?: string;
        }> = [];
        const codeBlockRegex = /```(\w+)?\n([\s\S]*?)```/g;
        let lastIndex = 0;
        let match;

        while ((match = codeBlockRegex.exec(content)) !== null) {
            // Add text before code block
            if (match.index > lastIndex) {
                parts.push({
                    type: "text",
                    content: content.slice(lastIndex, match.index),
                });
            }
            // Add code block or mermaid block
            const language = match[1] || "plaintext";
            if (language === "mermaid") {
                parts.push({ type: "mermaid", content: match[2] });
            } else {
                parts.push({ type: "code", content: match[2], language });
            }
            lastIndex = match.index + match[0].length;
        }

        // Add remaining text
        if (lastIndex < content.length) {
            parts.push({ type: "text", content: content.slice(lastIndex) });
        }

        return parts.length > 0 ? parts : [{ type: "text", content }];
    }

    function renderMarkdown(content: string): string {
        return markdown.render(content);
    }

    const parts = $derived(parseContent(message.content));
</script>

<div
    class="message"
    class:user={message.role === "user"}
    class:assistant={message.role === "assistant"}
>
    <div class="avatar">
        {#if message.role === "user"}
            <svg
                xmlns="http://www.w3.org/2000/svg"
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
            >
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
            </svg>
        {:else}
            <svg
                xmlns="http://www.w3.org/2000/svg"
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
            >
                <path d="M12 2L2 7l10 5 10-5-10-5z" />
                <path d="M2 17l10 5 10-5" />
                <path d="M2 12l10 5 10-5" />
            </svg>
        {/if}
    </div>

    <div class="content">
        <div class="header">
            <span class="role"
                >{message.role === "user" ? "你" : "SmanClaw"}</span
            >
            <span class="time">{formatTime(message.timestamp)}</span>
        </div>

        <div class="body">
            {#each parts as part}
                {#if part.type === "text"}
                    <div class="markdown">
                        {@html renderMarkdown(part.content)}
                    </div>
                {:else if part.type === "mermaid"}
                    <MermaidRenderer code={part.content} />
                {:else if part.type === "code"}
                    <CodeBlock
                        code={part.content}
                        language={part.language || "plaintext"}
                    />
                {/if}
            {/each}
        </div>
    </div>
</div>

<style>
    .message {
        display: flex;
        gap: 0.75rem;
        max-width: 80%;
        animation: slideIn 0.2s ease-out;
    }

    .message.user {
        align-self: flex-end;
        flex-direction: row-reverse;
    }

    .message.assistant {
        align-self: flex-start;
        width: min(92%, 980px);
        max-width: 92%;
    }

    .avatar {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 36px;
        height: 36px;
        border-radius: 50%;
        flex-shrink: 0;
    }

    .message.user .avatar {
        color: var(--text-primary);
        background-color: var(--surface-elevated);
        border: 1px solid var(--line-strong);
    }

    .message.assistant .avatar {
        color: var(--text-primary);
        background-color: var(--border);
    }

    .content {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        min-width: 0;
    }

    .message.assistant .content {
        width: 100%;
    }

    .header {
        display: flex;
        align-items: center;
        gap: 0.5rem;
    }

    .message.user .header {
        flex-direction: row-reverse;
    }

    .role {
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--text-primary);
    }

    .time {
        font-size: 0.625rem;
        color: var(--text-secondary);
    }

    .body {
        padding: 0.75rem 1rem;
        border-radius: 12px;
        overflow-wrap: break-word;
    }

    .message.user .body {
        background: linear-gradient(
            180deg,
            color-mix(in srgb, var(--surface-elevated) 96%, transparent),
            color-mix(in srgb, var(--surface) 94%, transparent)
        );
        color: var(--text-primary);
        border: 1px solid var(--line-strong);
        border-bottom-right-radius: 12px;
        border-top-right-radius: 18px;
        border-top-left-radius: 18px;
        border-bottom-left-radius: 18px;
        box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
    }

    .message.assistant .body {
        background-color: transparent;
        border-bottom-left-radius: 0;
        padding: 0;
        width: 100%;
        box-sizing: border-box;
        overflow-x: auto;
    }

    .markdown {
        line-height: 1.6;
    }

    .markdown :global(*:first-child) {
        margin-top: 0;
    }

    .markdown :global(*:last-child) {
        margin-bottom: 0;
    }

    .markdown :global(p) {
        margin: 0.45rem 0;
    }

    .markdown :global(h1),
    .markdown :global(h2),
    .markdown :global(h3),
    .markdown :global(h4),
    .markdown :global(h5),
    .markdown :global(h6) {
        margin: 0.75rem 0 0.45rem;
        font-weight: 700;
        line-height: 1.35;
    }

    .markdown :global(h1) {
        font-size: 1.1rem;
    }

    .markdown :global(h2) {
        font-size: 1rem;
    }

    .markdown :global(h3) {
        font-size: 0.95rem;
    }

    .markdown :global(ul),
    .markdown :global(ol) {
        margin: 0.5rem 0;
        padding-left: 1.3rem;
    }

    .markdown :global(li + li) {
        margin-top: 0.2rem;
    }

    .markdown :global(blockquote) {
        margin: 0.6rem 0;
        padding: 0.45rem 0.7rem;
        border-left: 3px solid var(--accent);
        background-color: color-mix(in srgb, var(--surface) 86%, transparent);
        border-radius: 4px;
    }

    .markdown :global(hr) {
        border: none;
        border-top: 1px solid var(--border);
        margin: 0.8rem 0;
    }

    .markdown :global(table) {
        width: 100%;
        border-collapse: collapse;
        margin: 0.6rem 0;
        font-size: 0.86rem;
    }

    .markdown :global(th),
    .markdown :global(td) {
        border: 1px solid var(--border);
        padding: 0.35rem 0.5rem;
        text-align: left;
        vertical-align: top;
    }

    .markdown :global(a) {
        text-decoration: underline;
    }

    .markdown :global(:not(pre) > code) {
        font-size: 0.85em;
        background-color: color-mix(in srgb, var(--surface) 84%, transparent);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.1rem 0.35rem;
    }

    @keyframes slideIn {
        from {
            opacity: 0;
            transform: translateY(8px);
        }
        to {
            opacity: 1;
            transform: translateY(0);
        }
    }
</style>
