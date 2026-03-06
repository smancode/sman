<script lang="ts">
    import { projectsStore, sortedProjects } from "../../lib/stores/projects";
    import ProjectList from "../project/ProjectList.svelte";
    import { invoke } from "@tauri-apps/api/core";

    interface Props {
        sidebarWidth?: number;
        onOpenSettings?: () => void;
    }

    let { sidebarWidth = 260, onOpenSettings }: Props = $props();

    async function handleNewProject() {
        try {
            const path = await invoke<string | null>("select_folder");
            if (path) {
                await projectsStore.createProject(path);
            }
        } catch (error) {
            console.error("Failed to add project:", error);
        }
    }

    async function handleDeleteProject(projectId: string) {
        await projectsStore.deleteProject(projectId);
    }

    function navigateToSettings() {
        onOpenSettings?.();
    }
</script>

<aside class="sidebar" style={`width: ${sidebarWidth}px`}>
    <div class="sidebar-content">
        <div class="section">
            <div class="section-header">
                <span class="section-title">项目</span>
                <button
                    class="add-btn"
                    onclick={handleNewProject}
                    aria-label="添加项目"
                >
                    <svg
                        xmlns="http://www.w3.org/2000/svg"
                        width="16"
                        height="16"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                    >
                        <path d="M12 5v14M5 12h14" />
                    </svg>
                </button>
            </div>

            <ProjectList
                projects={$sortedProjects}
                selectedId={$projectsStore.selectedProjectId}
                onSelect={(id) => projectsStore.selectProject(id)}
                onDelete={handleDeleteProject}
                onReorderAll={(orderedIds) =>
                    projectsStore.reorderProjectsByOrder(orderedIds)}
            />
        </div>
    </div>

    <div class="sidebar-footer">
        <button
            class="settings-btn"
            onclick={navigateToSettings}
            aria-label="打开设置"
            title="设置"
        >
            <svg
                xmlns="http://www.w3.org/2000/svg"
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
            >
                <circle cx="12" cy="12" r="3" />
                <path
                    d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
                />
            </svg>
        </button>
    </div>
</aside>

<style>
    .sidebar {
        display: flex;
        flex-direction: column;
        background-color: var(--surface);
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
        justify-content: flex-start;
        padding: 1rem;
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
</style>
