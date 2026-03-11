<script lang="ts">
  import { onMount } from "svelte";

  interface Props {
    code: string;
  }

  let { code }: Props = $props();
  let rendered = $state("");
  let error = $state("");
  let loading = $state(true);

  onMount(async () => {
    try {
      // 动态导入 mermaid 避免 SSR 问题
      const mermaid = (await import("mermaid")).default;

      mermaid.initialize({
        startOnLoad: false,
        theme: "dark",
        securityLevel: "loose",
      });

      const id =
        "mermaid-" + Date.now() + "-" + Math.random().toString(36).slice(2);
      const { svg } = await mermaid.render(id, code);
      rendered = svg;
    } catch (e: any) {
      error = e.message || "未知错误";
    } finally {
      loading = false;
    }
  });
</script>

{#if loading}
  <div class="mermaid-loading">
    <span class="loading-text">正在加载图表...</span>
  </div>
{:else if error}
  <div class="mermaid-error">
    <div class="error-header">
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
      >
        <circle cx="12" cy="12" r="10" />
        <line x1="12" y1="8" x2="12" y2="12" />
        <line x1="12" y1="16" x2="12.01" y2="16" />
      </svg>
      <span>图表渲染失败</span>
    </div>
    <pre class="error-code">{code}</pre>
  </div>
{:else if rendered}
  <div class="mermaid-container">
    {@html rendered}
  </div>
{/if}

<style>
  .mermaid-loading {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 2rem;
    background-color: var(--surface);
    border-radius: 8px;
  }

  .loading-text {
    color: var(--text-secondary);
    font-size: 0.875rem;
  }

  .mermaid-error {
    background-color: var(--surface);
    border-radius: 8px;
    overflow: hidden;
  }

  .error-header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 1rem;
    background-color: rgba(255, 107, 107, 0.1);
    color: #ff6b6b;
    font-size: 0.875rem;
    font-weight: 500;
  }

  .error-code {
    margin: 0;
    padding: 1rem;
    overflow-x: auto;
    font-family: "Consolas", "Monaco", monospace;
    font-size: 0.75rem;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-all;
  }

  .mermaid-container {
    background-color: var(--surface);
    border-radius: 8px;
    padding: 1rem;
    overflow-x: auto;
  }

  .mermaid-container :global(svg) {
    max-width: 100%;
    height: auto;
  }
</style>
