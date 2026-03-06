<script lang="ts">
    import type { Project } from "../../lib/types";
    import ProjectCard from "./ProjectCard.svelte";

    interface Props {
        projects: Project[];
        selectedId?: string | null;
        onSelect?: (id: string) => void;
        onDelete?: (id: string) => void;
        onReorder?: (draggedId: string, targetId: string) => void;
    }

    let {
        projects,
        selectedId = null,
        onSelect,
        onDelete,
        onReorder,
    }: Props = $props();
    let dragSourceId: string | null = null;
    let mouseDragSourceId: string | null = null;

    function handleDragStart(event: DragEvent, projectId: string) {
        dragSourceId = projectId;
        event.dataTransfer?.setData("text/plain", projectId);
        if (event.dataTransfer) {
            event.dataTransfer.effectAllowed = "move";
            event.dataTransfer.dropEffect = "move";
        }
    }

    function handleDragOver(event: DragEvent) {
        event.preventDefault();
        if (event.dataTransfer) {
            event.dataTransfer.dropEffect = "move";
        }
    }

    function handleDrop(event: DragEvent, targetId: string) {
        event.preventDefault();
        if (!dragSourceId || dragSourceId === targetId) {
            dragSourceId = null;
            return;
        }
        onReorder?.(dragSourceId, targetId);
        dragSourceId = null;
    }

    function handlePressStart(projectId: string, event: MouseEvent) {
        if (event.button !== 0) {
            return;
        }
        const target = event.target as HTMLElement | null;
        if (target?.closest("button")) {
            return;
        }
        mouseDragSourceId = projectId;
    }

    function handleHoverWhilePressed(targetId: string) {
        if (!mouseDragSourceId || mouseDragSourceId === targetId) {
            return;
        }
        onReorder?.(mouseDragSourceId, targetId);
        mouseDragSourceId = targetId;
    }

    function handlePressEnd() {
        mouseDragSourceId = null;
    }
</script>

<svelte:window onmouseup={handlePressEnd} />

<div class="project-list">
    {#if projects.length === 0}
        <div class="empty-state">
            <svg
                xmlns="http://www.w3.org/2000/svg"
                width="24"
                height="24"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
            >
                <path
                    d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"
                />
            </svg>
            <p>暂无项目</p>
            <span>点击 + 按钮添加项目</span>
        </div>
    {:else}
        {#each projects as project (project.id)}
            <div
                class="draggable-item"
                draggable="true"
                role="listitem"
                ondragstart={(event) => handleDragStart(event, project.id)}
                ondragover={handleDragOver}
                ondrop={(event) => handleDrop(event, project.id)}
                ondragend={() => (dragSourceId = null)}
            >
                <ProjectCard
                    {project}
                    selected={project.id === selectedId}
                    onSelect={(id) => onSelect?.(id)}
                    onDelete={(id) => onDelete?.(id)}
                    onPressStart={handlePressStart}
                    onHoverWhilePressed={handleHoverWhilePressed}
                    onPressEnd={handlePressEnd}
                />
            </div>
        {/each}
    {/if}
</div>

<style>
    .project-list {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        padding: 0 0.25rem;
    }

    .draggable-item {
        border-radius: 6px;
    }

    .empty-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 2rem 1rem;
        color: var(--text-secondary);
        text-align: center;
    }

    .empty-state svg {
        margin-bottom: 0.5rem;
        opacity: 0.5;
    }

    .empty-state p {
        font-size: 0.875rem;
        font-weight: 500;
        margin: 0 0 0.25rem;
    }

    .empty-state span {
        font-size: 0.75rem;
    }
</style>
