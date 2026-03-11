<script lang="ts">
  import { onDestroy } from "svelte";
  import Sidebar from "./Sidebar.svelte";
  import Header from "./Header.svelte";
  import SettingsPage from "../../routes/settings/+page.svelte";

  interface Props {
    children?: import("svelte").Snippet;
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
    if (event.key === "Escape") {
      closeSettings();
    }
  }

  function startResize(event: MouseEvent) {
    if (typeof window === "undefined") {
      return;
    }
    event.preventDefault();
    isResizing = true;
    document.body.style.cursor = "ew-resize";
    window.addEventListener("mousemove", onResizeMove);
    window.addEventListener("mouseup", stopResize);
  }

  function onResizeMove(event: MouseEvent) {
    if (!isResizing) {
      return;
    }
    const nextWidth = Math.max(
      minSidebarWidth,
      Math.min(maxSidebarWidth, event.clientX),
    );
    sidebarWidth = nextWidth;
  }

  function stopResize() {
    isResizing = false;
    if (typeof window === "undefined") {
      return;
    }
    document.body.style.removeProperty("cursor");
    window.removeEventListener("mousemove", onResizeMove);
    window.removeEventListener("mouseup", stopResize);
  }

  function handleResizeHoverStart() {
    if (typeof window === "undefined") {
      return;
    }
    document.body.style.cursor = "ew-resize";
  }

  function handleResizeHoverEnd() {
    if (typeof window === "undefined" || isResizing) {
      return;
    }
    document.body.style.removeProperty("cursor");
  }

  onDestroy(() => {
    stopResize();
  });
</script>

<div class="layout">
  <Sidebar {sidebarWidth} onOpenSettings={openSettings} />
  <button
    class="sidebar-resize-hitbox"
    class:active={isResizing}
    type="button"
    style={`left: ${sidebarWidth}px;`}
    aria-label="调整侧边栏宽度"
    onmouseenter={handleResizeHoverStart}
    onmouseleave={handleResizeHoverEnd}
    onmousedown={startResize}
  ></button>
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
    aria-label="关闭设置"
    onclick={closeSettings}
    onkeydown={handleBackdropKeydown}
  >
    <div
      class="settings-modal"
      role="dialog"
      tabindex="-1"
      aria-modal="true"
      aria-label="设置"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => event.stopPropagation()}
    >
      <div class="settings-modal-header">
        <button class="settings-close-btn" type="button" onclick={closeSettings}
          >关闭</button
        >
      </div>
      <SettingsPage />
    </div>
  </div>
{/if}

<style>
  .layout {
    position: relative;
    display: flex;
    height: 100vh;
    width: 100vw;
    background-color: var(--background);
    overflow: hidden;
  }

  .sidebar-resize-hitbox {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 18px;
    transform: translateX(-50%);
    background-color: transparent;
    border: none;
    padding: 0;
    cursor: ew-resize;
    user-select: none;
    z-index: 20;
  }

  .sidebar-resize-hitbox::after {
    content: "";
    position: absolute;
    left: 50%;
    top: 20%;
    width: 2px;
    height: 60%;
    border-radius: 999px;
    background: rgba(var(--accent-rgb), 0.34);
    transform: translateX(-50%);
    opacity: 0;
    transition:
      opacity 0.16s ease,
      background-color 0.16s ease;
  }

  .sidebar-resize-hitbox:hover::after,
  .sidebar-resize-hitbox.active::after {
    opacity: 1;
  }

  .sidebar-resize-hitbox.active::after {
    background: rgba(var(--accent-rgb), 0.62);
  }

  .main-container {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    background-color: var(--background);
  }

  .content {
    flex: 1;
    overflow: auto;
    padding: 0;
    background-color: var(--background);
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
    background: var(--surface);
    border: 1px solid var(--line-soft);
    border-radius: 14px;
    box-shadow: var(--shadow-floating);
  }

  .settings-modal-header {
    position: sticky;
    top: 0;
    display: flex;
    justify-content: flex-end;
    padding: 0.75rem 1rem;
    background: color-mix(in srgb, var(--surface) 92%, transparent);
    border-bottom: 1px solid var(--line-soft);
    z-index: 1;
  }

  .settings-close-btn {
    padding: 0.4rem 0.85rem;
    border-radius: 8px;
    color: var(--text-secondary);
    border: 1px solid var(--line-soft);
    background: color-mix(in srgb, var(--surface-elevated) 92%, transparent);
    transition:
      color 0.15s ease,
      background-color 0.15s ease,
      border-color 0.15s ease;
  }

  .settings-close-btn:hover {
    color: var(--text-primary);
    border-color: var(--line-strong);
    background: var(--surface-hover);
  }
</style>
