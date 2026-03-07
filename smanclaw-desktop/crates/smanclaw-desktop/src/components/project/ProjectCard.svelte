<script lang="ts">
    import type { Project } from "../../lib/types";

    interface Props {
        project: Project;
        selected?: boolean;
        onSelect?: (id: string) => void;
        onDelete?: (id: string) => void;
        onPressStart?: (id: string, event: MouseEvent) => void;
        onHoverWhilePressed?: (id: string) => void;
        onPressEnd?: () => void;
    }

    let {
        project,
        selected = false,
        onSelect,
        onDelete,
        onPressStart,
        onHoverWhilePressed,
        onPressEnd,
    }: Props = $props();

    function handleClick() {
        onSelect?.(project.id);
    }

    function handleDelete(event: Event) {
        event.stopPropagation();
        onDelete?.(project.id);
    }

    function handleMouseDown(event: MouseEvent) {
        onPressStart?.(project.id, event);
    }

    function handleMouseEnter() {
        onHoverWhilePressed?.(project.id);
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
    onmousedown={handleMouseDown}
    onmouseenter={handleMouseEnter}
    onmouseup={onPressEnd}
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
        padding: 0.65rem 0.72rem;
        background-color: color-mix(in srgb, var(--surface) 90%, transparent);
        border: 1px solid var(--line-soft);
        border-radius: 10px;
        cursor: pointer;
        transition:
            border-color 0.18s ease,
            background-color 0.18s ease,
            box-shadow 0.18s ease,
            transform 0.18s ease;
    }

    .project-card:hover {
        border-color: var(--line-strong);
        background-color: color-mix(
            in srgb,
            var(--surface-elevated) 94%,
            transparent
        );
        box-shadow: 0 6px 14px rgba(0, 0, 0, 0.16);
        transform: translateY(-1px);
    }

    .project-card:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 2px;
    }

    .project-card.selected {
        background-color: rgba(var(--accent-rgb), 0.12);
        border: 1px solid rgba(var(--accent-rgb), 0.56);
        box-shadow: 0 0 0 1px rgba(var(--accent-rgb), 0.2) inset;
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
        background-color: color-mix(
            in srgb,
            var(--surface-elevated) 88%,
            transparent
        );
        border: 1px solid var(--line-soft);
        border-radius: 8px;
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
        border-radius: 6px;
        opacity: 0;
        transition:
            opacity 0.14s ease,
            color 0.14s ease,
            background-color 0.14s ease;
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
