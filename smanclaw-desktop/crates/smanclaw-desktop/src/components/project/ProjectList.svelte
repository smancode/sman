<script lang="ts">
    import { flip } from "svelte/animate";
    import { cubicOut } from "svelte/easing";
    import {
        dndzone,
        DRAGGED_ELEMENT_ID,
        SHADOW_ITEM_MARKER_PROPERTY_NAME,
        type DndEvent,
    } from "svelte-dnd-action";
    import type { Project } from "../../lib/types";
    import ProjectCard from "./ProjectCard.svelte";

    interface Props {
        projects: Project[];
        selectedId?: string | null;
        onSelect?: (id: string) => void;
        onDelete?: (id: string) => void;
        onReorderAll?: (orderedIds: string[]) => void;
    }

    let {
        projects,
        selectedId = null,
        onSelect,
        onDelete,
        onReorderAll,
    }: Props = $props();
    let dndProjects: Project[] = $state([]);
    const flipDurationMs = 260;
    const dropTargetClasses = ["project-list-drop-target"];

    $effect(() => {
        dndProjects = projects;
    });

    function isShadowItem(project: Project): boolean {
        return Boolean(
            (project as unknown as Record<string, unknown>)[
                SHADOW_ITEM_MARKER_PROPERTY_NAME
            ],
        );
    }

    function syncVisualOrder(event: CustomEvent<DndEvent<Project>>) {
        dndProjects = event.detail.items;
    }

    function commitOrder(event: CustomEvent<DndEvent<Project>>) {
        dndProjects = event.detail.items;
        const orderedIds = dndProjects
            .filter((project) => !isShadowItem(project))
            .map((project) => project.id);
        onReorderAll?.(orderedIds);
    }

    function transformDraggedElement(element?: HTMLElement) {
        if (!element) {
            return;
        }
        element.id = DRAGGED_ELEMENT_ID;
        const card = element.querySelector(".project-card");
        if (!(card instanceof HTMLElement)) {
            return;
        }
        card.classList.add("drag-preview-card");
    }
</script>

<div
    class="project-list"
    use:dndzone={{
        items: dndProjects,
        flipDurationMs,
        morphDisabled: true,
        dropTargetClasses,
        transformDraggedElement,
    }}
    onconsider={syncVisualOrder}
    onfinalize={commitOrder}
>
    {#if dndProjects.length === 0}
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
        {#each dndProjects as project (project.id)}
            <div
                class="draggable-item"
                class:shadow-item={isShadowItem(project)}
                role="listitem"
                animate:flip={{
                    duration: flipDurationMs,
                    easing: cubicOut,
                }}
            >
                {#if !isShadowItem(project)}
                    <ProjectCard
                        {project}
                        selected={project.id === selectedId}
                        onSelect={(id) => onSelect?.(id)}
                        onDelete={(id) => onDelete?.(id)}
                    />
                {/if}
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
        will-change: transform;
        transition: transform 0.2s ease;
    }

    .draggable-item.shadow-item {
        min-height: 48px;
        border: 1px dashed rgba(var(--accent-rgb), 0.4);
        background: rgba(var(--accent-rgb), 0.08);
        border-radius: 8px;
    }

    .draggable-item :global(.project-card) {
        cursor: grab;
    }

    .draggable-item :global(.project-card:active) {
        cursor: grabbing;
    }

    .project-list :global(#dnd-action-dragged-el) {
        z-index: 30;
    }

    .project-list :global(#dnd-action-dragged-el .project-card),
    .project-list :global(.project-card.drag-preview-card) {
        transform: scale(1.015);
        border-color: rgba(var(--accent-rgb), 0.28);
        background: color-mix(in srgb, var(--surface) 82%, white 18%);
        box-shadow:
            0 14px 30px rgba(0, 0, 0, 0.15),
            0 2px 8px rgba(0, 0, 0, 0.08);
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
