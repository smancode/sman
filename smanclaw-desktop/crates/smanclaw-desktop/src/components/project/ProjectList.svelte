<script lang="ts">
    import type { Project } from "../../lib/types";
    import ProjectCard from "./ProjectCard.svelte";

    interface Props {
        projects: Project[];
        selectedId?: string | null;
        onSelect?: (id: string) => void;
        onDelete?: (id: string) => void;
    }

    let { projects, selectedId = null, onSelect, onDelete }: Props = $props();
</script>

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
            <ProjectCard
                {project}
                selected={project.id === selectedId}
                onSelect={(id) => onSelect?.(id)}
                onDelete={(id) => onDelete?.(id)}
            />
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
