<script lang="ts">
  import { selectedProject } from '../../lib/stores/projects';
  import { tasksStore, activeTask } from '../../lib/stores/tasks';

  function handleCancelTask() {
    if ($activeTask) {
      tasksStore.cancelTask($activeTask.id);
    }
  }

  function handleSettingsClick() {
    // Navigate to settings using browser navigation
    window.location.href = '/settings';
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
    {:else}
      <div class="task-status idle">
        <span>Ready</span>
      </div>
    {/if}
  </div>

  <div class="header-right">
    <button class="icon-btn" aria-label="Notifications" title="Notifications (coming soon)">
      <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
        <path d="M13.73 21a2 2 0 0 1-3.46 0" />
      </svg>
    </button>
    <button class="icon-btn" aria-label="Settings" title="Settings" onclick={handleSettingsClick}>
      <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
      </svg>
    </button>
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

  .task-status.idle {
    color: var(--text-secondary);
    background-color: transparent;
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

  .header-right {
    flex: 1;
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
  }

  .icon-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    color: var(--text-secondary);
    border-radius: 8px;
    transition: all 0.15s;
  }

  .icon-btn:hover {
    color: var(--text-primary);
    background-color: var(--border);
  }
</style>
