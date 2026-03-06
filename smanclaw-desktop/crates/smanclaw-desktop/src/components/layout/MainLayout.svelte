<script lang="ts">
  import { onDestroy } from 'svelte';
  import Sidebar from './Sidebar.svelte';
  import Header from './Header.svelte';
  import SettingsPage from '../../routes/settings/+page.svelte';

  interface Props {
    children?: import('svelte').Snippet;
  }

  let { children }: Props = $props();
  let sidebarWidth = $state(260);
  let isResizing = $state(false);
  let isSettingsOpen = $state(false);
  const minSidebarWidth = 220;
  const maxSidebarWidth = 520;

  function openSettings() {
    isSettingsOpen = true;
  }

  function closeSettings() {
    isSettingsOpen = false;
  }

  function handleBackdropKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      closeSettings();
    }
  }

  function startResize(event: MouseEvent) {
    if (typeof window === 'undefined') {
      return;
    }
    event.preventDefault();
    isResizing = true;
    window.addEventListener('mousemove', onResizeMove);
    window.addEventListener('mouseup', stopResize);
  }

  function onResizeMove(event: MouseEvent) {
    if (!isResizing) {
      return;
    }
    const nextWidth = Math.max(minSidebarWidth, Math.min(maxSidebarWidth, event.clientX));
    sidebarWidth = nextWidth;
  }

  function stopResize() {
    isResizing = false;
    if (typeof window === 'undefined') {
      return;
    }
    window.removeEventListener('mousemove', onResizeMove);
    window.removeEventListener('mouseup', stopResize);
  }

  onDestroy(() => {
    stopResize();
  });
</script>

<div class="layout">
  <Sidebar {sidebarWidth} onOpenSettings={openSettings} />
  <button
    class="sidebar-divider"
    class:active={isResizing}
    type="button"
    aria-label="Resize sidebar"
    onmousedown={startResize}
  >
    <span>&lt;&gt;</span>
  </button>
  <div class="main-container">
    <Header />
    <main class="content">
      {@render children?.()}
    </main>
  </div>
</div>

{#if isSettingsOpen}
  <div
    class="settings-modal-backdrop"
    role="button"
    tabindex="0"
    aria-label="Close settings"
    onclick={closeSettings}
    onkeydown={handleBackdropKeydown}
  >
    <div
      class="settings-modal"
      role="dialog"
      tabindex="-1"
      aria-modal="true"
      aria-label="Settings"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => event.stopPropagation()}
    >
      <div class="settings-modal-header">
        <button class="settings-close-btn" type="button" onclick={closeSettings}>关闭</button>
      </div>
      <SettingsPage />
    </div>
  </div>
{/if}

<style>
  .layout {
    display: flex;
    height: 100vh;
    width: 100vw;
    background-color: var(--background);
    overflow: hidden;
  }

  .sidebar-divider {
    width: 12px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    color: transparent;
    background-color: transparent;
    border-left: 1px solid var(--border);
    border-right: 1px solid transparent;
    cursor: ew-resize;
    user-select: none;
    transition: color 0.15s, border-color 0.15s, background-color 0.15s;
  }

  .sidebar-divider span {
    font-size: 0.65rem;
    letter-spacing: -0.03em;
    font-weight: 600;
  }

  .sidebar-divider:hover,
  .sidebar-divider.active {
    color: var(--text-secondary);
    border-right-color: var(--border);
    background-color: rgba(255, 255, 255, 0.02);
  }

  .main-container {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
  }

  .content {
    flex: 1;
    overflow: auto;
    padding: 1rem;
  }

  .settings-modal-backdrop {
    position: fixed;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 1.5rem;
    background: rgba(0, 0, 0, 0.5);
    z-index: 1000;
  }

  .settings-modal {
    width: min(900px, 100%);
    max-height: calc(100vh - 3rem);
    overflow: auto;
    background: var(--background);
    border: 1px solid var(--border);
    border-radius: 12px;
  }

  .settings-modal-header {
    position: sticky;
    top: 0;
    display: flex;
    justify-content: flex-end;
    padding: 0.75rem 1rem;
    background: var(--surface);
    border-bottom: 1px solid var(--border);
    z-index: 1;
  }

  .settings-close-btn {
    padding: 0.4rem 0.85rem;
    border-radius: 6px;
    color: var(--text-secondary);
    border: 1px solid var(--border);
    background: var(--background);
    transition: all 0.15s;
  }

  .settings-close-btn:hover {
    color: var(--text-primary);
    background: var(--surface);
  }
</style>
