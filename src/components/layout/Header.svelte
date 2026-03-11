<script lang="ts">
  import { selectedProject } from "../../lib/stores/projects";
  import { tasksStore, activeTask } from "../../lib/stores/tasks";

  function handleCancelTask() {
    if ($activeTask) {
      tasksStore.cancelTask($activeTask.id);
    }
  }
</script>

<header class="header">
  <div class="header-left">
    {#if $selectedProject}
      <div class="project-info">
        <h1 class="project-name">{$selectedProject.name}</h1>
        <span class="project-path">{$selectedProject.path}</span>
      </div>
    {:else}
      <h1 class="project-name">请选择项目</h1>
    {/if}
  </div>

  <div class="header-center">
    {#if $activeTask}
      <div class="task-status running">
        <div class="spinner"></div>
        <span>任务执行中...</span>
        <button class="cancel-btn" onclick={handleCancelTask}>取消</button>
      </div>
    {/if}
  </div>
</header>

<style>
  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 64px;
    padding: 0 1.25rem;
    background-color: color-mix(in srgb, var(--background) 92%, transparent);
    border-bottom: 1px solid var(--line-soft);
    backdrop-filter: blur(10px);
  }

  .header-left {
    flex: 1;
    min-width: 0;
  }

  .project-info {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .project-name {
    font-size: 1rem;
    font-weight: 600;
    color: var(--text-primary);
    margin: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .project-path {
    font-size: 0.75rem;
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .header-center {
    flex: 1;
    display: flex;
    justify-content: center;
  }

  .task-status {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.375rem 0.75rem;
    border-radius: 9999px;
    font-size: 0.875rem;
    border: 1px solid transparent;
  }

  .task-status.running {
    color: var(--accent-light);
    border-color: rgba(var(--accent-rgb), 0.36);
    background-color: rgba(var(--accent-rgb), 0.12);
  }

  .spinner {
    width: 14px;
    height: 14px;
    border: 2px solid var(--accent);
    border-top-color: transparent;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  .cancel-btn {
    padding: 0.25rem 0.5rem;
    font-size: 0.75rem;
    color: var(--error);
    background-color: transparent;
    border-radius: 6px;
    transition:
      color 0.15s ease,
      background-color 0.15s ease;
  }

  .cancel-btn:hover {
    background-color: rgba(239, 68, 68, 0.1);
  }
</style>
