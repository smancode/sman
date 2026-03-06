<script lang="ts">
    import type { Project } from "../../lib/types";

    interface Props {
        project: Project;
        selected?: boolean;
        onSelect?: (id: string) => void;
        onDelete?: (id: string) => void;
    }

    let { project, selected = false, onSelect, onDelete }: Props = $props();

    function handleClick() {
        onSelect?.(project.id);
    }

    function handleDelete(event: Event) {
        event.stopPropagation();
        onDelete?.(project.id);
    }

    function formatDate(timestamp: string): string {
        const date = new Date(timestamp);
        const now = new Date();
        const diff = now.getTime() - date.getTime();
        const days = Math.floor(diff / (1000 * 60 * 60 * 24));

        if (days === 0) return "今天";
        if (days === 1) return "昨天";
        if (days < 7) return `${days} 天前`;
        return date.toLocaleDateString("zh-CN");
    }
</script>

<div
    class="project-card"
    class:selected
    onclick={handleClick}
    role="button"
    tabindex="0"
    onkeydown={(e) => e.key === "Enter" && handleClick()}
>
    <div class="icon">
        <svg
            xmlns="http://www.w3.org/2000/svg"
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
        >
            <path
                d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"
            />
        </svg>
    </div>

    <div class="info">
        <span class="name">{project.name}</span>
        <span class="meta">{formatDate(project.lastAccessed)}</span>
    </div>

    <button class="delete-btn" onclick={handleDelete} aria-label="删除项目">
        <svg
            xmlns="http://www.w3.org/2000/svg"
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
        >
            <path d="M3 6h18" />
            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
            <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
        </svg>
    </button>
</div>

<style>
    .project-card {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        width: 100%;
        padding: 0.625rem 0.75rem;
        background-color: transparent;
        border: 1px solid transparent;
        border-radius: 6px;
        cursor: pointer;
        transition: all 0.15s;
    }

    .project-card:hover {
        background-color: var(--border);
    }

    .project-card:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 2px;
    }

    .project-card.selected {
        background-color: rgba(var(--accent-rgb), 0.13);
        border: 1px solid var(--accent);
    }

    .project-card.selected .icon {
        color: var(--accent);
    }

    .icon {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 32px;
        height: 32px;
        color: var(--text-secondary);
        background-color: var(--background);
        border-radius: 6px;
        flex-shrink: 0;
    }

    .info {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 0.125rem;
    }

    .name {
        font-size: 0.875rem;
        font-weight: 500;
        color: var(--text-primary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .meta {
        font-size: 0.75rem;
        color: var(--text-secondary);
    }

    .delete-btn {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        color: var(--text-secondary);
        border-radius: 4px;
        opacity: 0;
        transition: all 0.15s;
    }

    .project-card:hover .delete-btn,
    .project-card:focus-within .delete-btn {
        opacity: 1;
    }

    .delete-btn:hover {
        color: var(--error);
        background-color: rgba(239, 68, 68, 0.1);
    }
</style>
