<script lang="ts">
  import { projectsStore, sortedProjects } from '../../lib/stores/projects';
  import ProjectList from '../project/ProjectList.svelte';
  import { invoke } from '@tauri-apps/api/core';

  let isCollapsed = $state(false);

  async function handleNewProject() {
    try {
      const path = await invoke<string | null>('select_folder');
      if (path) {
        // createProject now only takes path - backend extracts name from path
        await projectsStore.createProject(path);
      }
    } catch (error) {
      console.error('Failed to add project:', error);
    }
  }

  function toggleSidebar() {
    isCollapsed = !isCollapsed;
  }

  function navigateToSettings() {
    window.location.href = '/settings';
  }

</script>

<aside class="sidebar" class:collapsed={isCollapsed}>
  <div class="sidebar-header">
    <button class="toggle-btn" onclick={toggleSidebar} aria-label="Toggle sidebar">
      <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        {#if isCollapsed}
          <path d="M9 18l6-6-6-6" />
        {:else}
          <path d="M15 18l-6-6 6-6" />
        {/if}
      </svg>
    </button>
  </div>

  {#if !isCollapsed}
    <div class="sidebar-content">
      <div class="section">
        <div class="section-header">
          <span class="section-title">Projects</span>
          <button class="add-btn" onclick={handleNewProject} aria-label="Add project">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </button>
        </div>

        <ProjectList
          projects={$sortedProjects}
          selectedId={$projectsStore.selectedProjectId}
          onSelect={(id) => projectsStore.selectProject(id)}
        />
      </div>
    </div>

    <div class="sidebar-footer">
      <button class="settings-btn" onclick={navigateToSettings} aria-label="Open settings" title="Settings">
        <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
      </button>
      <div class="status">
        <span class="status-dot"></span>
        <span>Ready</span>
      </div>
    </div>
  {/if}
</aside>

<style>
  .sidebar {
    display: flex;
    flex-direction: column;
    width: 260px;
    background-color: var(--surface);
    border-right: 1px solid var(--border);
    transition: width 0.2s ease;
  }

  .sidebar.collapsed {
    width: 48px;
  }

  .sidebar-header {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    padding: 1rem;
    border-bottom: 1px solid var(--border);
  }

  .toggle-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    color: var(--text-secondary);
    border-radius: 4px;
    transition: background-color 0.15s;
  }

  .toggle-btn:hover {
    background-color: var(--border);
  }

  .sidebar-content {
    flex: 1;
    overflow-y: auto;
    padding: 0.5rem;
  }

  .section {
    margin-bottom: 1rem;
  }

  .section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0.5rem;
  }

  .section-title {
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--text-secondary);
  }

  .add-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    color: var(--text-secondary);
    border-radius: 4px;
    transition: all 0.15s;
  }

  .add-btn:hover {
    color: var(--accent);
    background-color: var(--border);
  }

  .sidebar-footer {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 1rem;
    border-top: 1px solid var(--border);
  }

  .settings-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    color: var(--text-secondary);
    border-radius: 6px;
    transition: all 0.15s;
  }

  .settings-btn:hover {
    color: var(--text-primary);
    background-color: var(--border);
  }

  .status {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.875rem;
    color: var(--text-secondary);
  }

  .status-dot {
    width: 8px;
    height: 8px;
    background-color: var(--success);
    border-radius: 50%;
  }
</style>
