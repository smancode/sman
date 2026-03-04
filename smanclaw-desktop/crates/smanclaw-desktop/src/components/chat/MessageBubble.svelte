<script lang="ts">
  import CodeBlock from './CodeBlock.svelte';
  import MermaidRenderer from './MermaidRenderer.svelte';
  import type { Message } from '../../lib/types';

  interface Props {
    message: Message;
  }

  let { message }: Props = $props();

  function formatTime(timestamp: number): string {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }

  // Parse message content to identify code blocks
  function parseContent(content: string): Array<{ type: 'text' | 'code' | 'mermaid'; content: string; language?: string }> {
    const parts: Array<{ type: 'text' | 'code' | 'mermaid'; content: string; language?: string }> = [];
    const codeBlockRegex = /```(\w+)?\n([\s\S]*?)```/g;
    let lastIndex = 0;
    let match;

    while ((match = codeBlockRegex.exec(content)) !== null) {
      // Add text before code block
      if (match.index > lastIndex) {
        parts.push({ type: 'text', content: content.slice(lastIndex, match.index) });
      }
      // Add code block or mermaid block
      const language = match[1] || 'plaintext';
      if (language === 'mermaid') {
        parts.push({ type: 'mermaid', content: match[2] });
      } else {
        parts.push({ type: 'code', content: match[2], language });
      }
      lastIndex = match.index + match[0].length;
    }

    // Add remaining text
    if (lastIndex < content.length) {
      parts.push({ type: 'text', content: content.slice(lastIndex) });
    }

    return parts.length > 0 ? parts : [{ type: 'text', content }];
  }

  const parts = $derived(parseContent(message.content));
</script>

<div class="message" class:user={message.role === 'user'} class:assistant={message.role === 'assistant'}>
  <div class="avatar">
    {#if message.role === 'user'}
      <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
        <circle cx="12" cy="7" r="4" />
      </svg>
    {:else}
      <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M12 2L2 7l10 5 10-5-10-5z" />
        <path d="M2 17l10 5 10-5" />
        <path d="M2 12l10 5 10-5" />
      </svg>
    {/if}
  </div>

  <div class="content">
    <div class="header">
      <span class="role">{message.role === 'user' ? 'You' : 'SmanClaw'}</span>
      <span class="time">{formatTime(message.timestamp)}</span>
    </div>

    <div class="body">
      {#each parts as part}
        {#if part.type === 'text'}
          <p class="text">{part.content}</p>
        {:else if part.type === 'mermaid'}
          <MermaidRenderer code={part.content} />
        {:else if part.type === 'code'}
          <CodeBlock code={part.content} language={part.language || 'plaintext'} />
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
    background-color: var(--accent);
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
    background-color: var(--accent);
    color: white;
    border-bottom-right-radius: 4px;
  }

  .message.assistant .body {
    background-color: var(--surface);
    border: 1px solid var(--border);
    border-bottom-left-radius: 4px;
  }

  .text {
    margin: 0;
    white-space: pre-wrap;
    line-height: 1.6;
  }

  .text + .text {
    margin-top: 0.5rem;
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
