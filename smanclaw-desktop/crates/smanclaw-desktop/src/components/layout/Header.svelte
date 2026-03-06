<script lang="ts">
  import { selectedProject } from '../../lib/stores/projects';
  import { tasksStore, activeTask } from '../../lib/stores/tasks';

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
      <h1 class="project-name">Select a Project</h1>
    {/if}
  </div>

  <div class="header-center">
    {#if $activeTask}
      <div class="task-status running">
        <div class="spinner"></div>
        <span>Task running...</span>
        <button class="cancel-btn" onclick={handleCancelTask}>Cancel</button>
      </div>
    {/if}
  </div>
</header>

<style>
  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 56px;
    padding: 0 1rem;
    background-color: var(--surface);
    border-bottom: 1px solid var(--border);
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
  }

  .task-status.running {
    color: var(--accent);
    background-color: rgba(99, 102, 241, 0.1);
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
    border-radius: 4px;
    transition: background-color 0.15s;
  }

  .cancel-btn:hover {
    background-color: rgba(239, 68, 68, 0.1);
  }

</style>
