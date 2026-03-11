<script lang="ts">
  import type { Task, TaskStatus } from "../../lib/types";

  interface Props {
    task: Task;
  }

  let { task }: Props = $props();

  function getStatusColor(status: TaskStatus): string {
    switch (status) {
      case "pending":
        return "var(--text-secondary)";
      case "running":
        return "var(--accent)";
      case "completed":
        return "var(--success)";
      case "failed":
        return "var(--error)";
      default:
        return "var(--text-secondary)";
    }
  }

  function getStatusLabel(status: TaskStatus): string {
    switch (status) {
      case "pending":
        return "等待中";
      case "running":
        return "执行中";
      case "completed":
        return "已完成";
      case "failed":
        return "失败";
      default:
        return "未知";
    }
  }

  function toTimestamp(value?: string): number | null {
    if (!value) {
      return null;
    }
    const timestamp = Date.parse(value);
    return Number.isNaN(timestamp) ? null : timestamp;
  }

  function formatDuration(startedAt?: string, completedAt?: string): string {
    const start = toTimestamp(startedAt);
    if (!start) return "-";

    const end = toTimestamp(completedAt) ?? Date.now();
    const duration = Math.floor((end - start) / 1000);

    if (duration < 60) return `${duration}s`;
    if (duration < 3600)
      return `${Math.floor(duration / 60)}m ${duration % 60}s`;
    return `${Math.floor(duration / 3600)}h ${Math.floor((duration % 3600) / 60)}m`;
  }
</script>

<div class="task-progress">
  <div class="header">
    <div class="status" style="color: {getStatusColor(task.status)}">
      {#if task.status === "running"}
        <div
          class="spinner"
          style="border-color: {getStatusColor(task.status)}"
        ></div>
      {:else if task.status === "completed"}
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
      {:else if task.status === "failed"}
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
          <path d="M15 9l-6 6" />
          <path d="M9 9l6 6" />
        </svg>
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
          <circle cx="12" cy="12" r="10" />
        </svg>
      {/if}
      <span>{getStatusLabel(task.status)}</span>
    </div>

    <span class="duration"
      >{formatDuration(task.createdAt, task.completedAt)}</span
    >
  </div>

  <div class="prompt">
    <p>{task.input}</p>
  </div>

  <div class="progress-bar">
    <div
      class="progress-fill"
      style="width: {task.progress ?? 0}%; background-color: {getStatusColor(
        task.status,
      )}"
    ></div>
  </div>

  <div class="progress-label">
    <span>{Math.round(task.progress ?? 0)}%</span>
  </div>

  {#if task.steps && task.steps.length > 0}
    <div class="steps">
      {#each task.steps as step}
        <div
          class="step"
          class:completed={step.status === "completed"}
          class:running={step.status === "running"}
          class:failed={step.status === "failed"}
        >
          <span class="step-name">{step.name}</span>
          <span class="step-status">{getStatusLabel(step.status)}</span>
        </div>
      {/each}
    </div>
  {/if}

  {#if task.error}
    <div class="error-message">
      <p>{task.error}</p>
    </div>
  {/if}
</div>

<style>
  .task-progress {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }

  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .status {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.875rem;
    font-weight: 500;
  }

  .spinner {
    width: 14px;
    height: 14px;
    border: 2px solid;
    border-top-color: transparent !important;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  .duration {
    font-size: 0.75rem;
    color: var(--text-secondary);
  }

  .prompt {
    padding: 0.75rem;
    background-color: var(--background);
    border-radius: 6px;
  }

  .prompt p {
    margin: 0;
    font-size: 0.875rem;
    color: var(--text-primary);
    line-height: 1.5;
  }

  .progress-bar {
    height: 4px;
    background-color: var(--border);
    border-radius: 2px;
    overflow: hidden;
  }

  .progress-fill {
    height: 100%;
    border-radius: 2px;
    transition: width 0.3s ease;
  }

  .progress-label {
    display: flex;
    justify-content: flex-end;
    font-size: 0.75rem;
    color: var(--text-secondary);
  }

  .steps {
    display: flex;
    flex-direction: column;
    gap: 0.375rem;
    padding: 0.5rem;
    background-color: var(--background);
    border-radius: 6px;
  }

  .step {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0.25rem 0;
    font-size: 0.75rem;
  }

  .step-name {
    color: var(--text-primary);
  }

  .step-status {
    color: var(--text-secondary);
    text-transform: capitalize;
  }

  .step.completed .step-status {
    color: var(--success);
  }

  .step.running .step-status {
    color: var(--accent);
  }

  .step.failed .step-status {
    color: var(--error);
  }

  .error-message {
    padding: 0.75rem;
    background-color: rgba(239, 68, 68, 0.1);
    border-radius: 6px;
  }

  .error-message p {
    margin: 0;
    font-size: 0.875rem;
    color: var(--error);
  }
</style>
