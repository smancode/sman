<script lang="ts">
  import type {
    SubTaskInfo,
    SubTaskStatus,
    OrchestrationProgress,
  } from "../../lib/types";

  interface Props {
    subtasks: SubTaskInfo[];
    progress: OrchestrationProgress | null;
    parallelGroups: string[][];
  }

  let { subtasks, progress, parallelGroups }: Props = $props();

  function getStatusColor(status: SubTaskStatus): string {
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

  function getStatusLabel(status: SubTaskStatus): string {
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

  function getStatusIcon(status: SubTaskStatus): string {
    switch (status) {
      case "pending":
        return "circle";
      case "running":
        return "spinner";
      case "completed":
        return "check";
      case "failed":
        return "x";
      default:
        return "circle";
    }
  }
</script>

<div class="subtask-progress">
  {#if progress}
    <div class="overall-progress">
      <div class="progress-header">
        <span class="progress-label">总体进度</span>
        <span class="progress-count"
          >{progress.completed} / {progress.total}</span
        >
      </div>
      <div class="progress-bar">
        <div
          class="progress-fill"
          style="width: {progress.percent * 100}%"
        ></div>
      </div>
      <div class="progress-percent">
        {Math.round(progress.percent * 100)}%
      </div>
    </div>
  {/if}

  {#if subtasks.length > 0}
    <div class="subtasks-list">
      <div class="subtasks-header">子任务</div>
      {#each subtasks as subtask (subtask.id)}
        <div
          class="subtask-item"
          class:running={subtask.status === "running"}
          class:completed={subtask.status === "completed"}
          class:failed={subtask.status === "failed"}
        >
          <div
            class="subtask-status"
            style="color: {getStatusColor(subtask.status)}"
          >
            {#if subtask.status === "running"}
              <div class="spinner"></div>
            {:else if subtask.status === "completed"}
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path d="M20 6L9 17l-5-5" />
              </svg>
            {:else if subtask.status === "failed"}
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="14"
                height="14"
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
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <circle cx="12" cy="12" r="10" />
              </svg>
            {/if}
          </div>
          <div class="subtask-content">
            <span class="subtask-description">{subtask.description}</span>
            {#if subtask.depends_on.length > 0}
              <span class="subtask-deps"
                >依赖：{subtask.depends_on.join(", ")}</span
              >
            {/if}
          </div>
          <span
            class="subtask-status-label"
            style="color: {getStatusColor(subtask.status)}"
          >
            {getStatusLabel(subtask.status)}
          </span>
        </div>
      {/each}
    </div>
  {/if}

  {#if parallelGroups.length > 1}
    <div class="parallel-groups">
      <div class="groups-header">并行执行分组</div>
      {#each parallelGroups as group, groupIndex}
        <div class="group-item">
          <span class="group-label">分组 {groupIndex + 1}</span>
          <span class="group-tasks">{group.length} 个任务</span>
        </div>
      {/each}
    </div>
  {/if}
</div>

<style>
  .subtask-progress {
    display: flex;
    flex-direction: column;
    gap: 1rem;
  }

  .overall-progress {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .progress-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .progress-label {
    font-size: 0.875rem;
    font-weight: 500;
    color: var(--text-primary);
  }

  .progress-count {
    font-size: 0.75rem;
    color: var(--text-secondary);
  }

  .progress-bar {
    height: 6px;
    background-color: var(--border);
    border-radius: 3px;
    overflow: hidden;
  }

  .progress-fill {
    height: 100%;
    background-color: var(--accent);
    border-radius: 3px;
    transition: width 0.3s ease;
  }

  .progress-percent {
    font-size: 0.75rem;
    color: var(--text-secondary);
    text-align: right;
  }

  .subtasks-list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .subtasks-header {
    font-size: 0.75rem;
    font-weight: 500;
    color: var(--text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  .subtask-item {
    display: flex;
    align-items: flex-start;
    gap: 0.75rem;
    padding: 0.5rem 0.75rem;
    background-color: var(--background);
    border-radius: 6px;
  }

  .subtask-status {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    margin-top: 2px;
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

  .subtask-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    min-width: 0;
  }

  .subtask-description {
    font-size: 0.875rem;
    color: var(--text-primary);
    word-break: break-word;
  }

  .subtask-deps {
    font-size: 0.75rem;
    color: var(--text-secondary);
  }

  .subtask-status-label {
    font-size: 0.75rem;
    font-weight: 500;
    flex-shrink: 0;
  }

  .subtask-item.completed {
    opacity: 0.8;
  }

  .parallel-groups {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .groups-header {
    font-size: 0.75rem;
    font-weight: 500;
    color: var(--text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  .group-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0.375rem 0.75rem;
    background-color: var(--background);
    border-radius: 4px;
    font-size: 0.75rem;
  }

  .group-label {
    color: var(--text-primary);
  }

  .group-tasks {
    color: var(--text-secondary);
  }
</style>
