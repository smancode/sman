<script lang="ts">
    import type { FileChange } from "../../lib/types";

    interface Props {
        fileChanges: FileChange[];
    }

    let { fileChanges }: Props = $props();

    function getActionIcon(action: FileChange["action"]): string {
        switch (action) {
            case "Created":
                return "+";
            case "Modified":
                return "~";
            case "Deleted":
                return "-";
            default:
                return "";
        }
    }

    function getActionColor(action: FileChange["action"]): string {
        switch (action) {
            case "Created":
                return "var(--success)";
            case "Modified":
                return "var(--warning)";
            case "Deleted":
                return "var(--error)";
            default:
                return "var(--text-secondary)";
        }
    }

    function getActionLabel(action: FileChange["action"]): string {
        switch (action) {
            case "Created":
                return "已创建";
            case "Modified":
                return "已修改";
            case "Deleted":
                return "已删除";
            default:
                return "未知";
        }
    }

    function getFileName(path: string): string {
        return path.split("/").pop() || path;
    }

    function getDirectory(path: string): string {
        const parts = path.split("/");
        parts.pop();
        return parts.join("/");
    }

    // Group files by directory
    const groupedChanges = $derived(() => {
        const groups: Record<string, FileChange[]> = {};

        for (const change of fileChanges) {
            const dir = getDirectory(change.path);
            if (!groups[dir]) {
                groups[dir] = [];
            }
            groups[dir].push(change);
        }

        return Object.entries(groups);
    });

    const stats = $derived({
        added: fileChanges.reduce((sum, c) => sum + c.linesAdded, 0),
        removed: fileChanges.reduce((sum, c) => sum + c.linesRemoved, 0),
    });
</script>

<div class="file-tree">
    <div class="header">
        <span class="title">文件变更</span>
        <div class="stats">
            <span class="added">+{stats.added}</span>
            <span class="removed">-{stats.removed}</span>
        </div>
    </div>

    <div class="files">
        {#each groupedChanges() as [directory, changes]}
            <div class="group">
                {#if directory}
                    <div class="directory">
                        <svg
                            xmlns="http://www.w3.org/2000/svg"
                            width="14"
                            height="14"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            stroke-width="2"
                        >
                            <path
                                d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"
                            />
                        </svg>
                        <span>{directory}</span>
                    </div>
                {/if}

                {#each changes as change}
                    <div
                        class="file"
                        style="--action-color: {getActionColor(change.action)}"
                    >
                        <span
                            class="action-icon"
                            style="color: {getActionColor(change.action)}"
                        >
                            {getActionIcon(change.action)}
                        </span>
                        <svg
                            xmlns="http://www.w3.org/2000/svg"
                            width="14"
                            height="14"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            stroke-width="2"
                        >
                            <path
                                d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"
                            />
                            <path d="M14 2v6h6" />
                        </svg>
                        <span class="file-name">{getFileName(change.path)}</span
                        >
                        <div class="line-stats">
                            {#if change.linesAdded > 0}
                                <span class="added">+{change.linesAdded}</span>
                            {/if}
                            {#if change.linesRemoved > 0}
                                <span class="removed"
                                    >-{change.linesRemoved}</span
                                >
                            {/if}
                        </div>
                    </div>
                {/each}
            </div>
        {/each}
    </div>
</div>

<style>
    .file-tree {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
    }

    .header {
        display: flex;
        align-items: center;
        justify-content: space-between;
    }

    .title {
        font-size: 0.875rem;
        font-weight: 500;
        color: var(--text-primary);
    }

    .stats {
        display: flex;
        gap: 0.5rem;
        font-size: 0.75rem;
        font-weight: 500;
    }

    .stats .added {
        color: var(--success);
    }

    .stats .removed {
        color: var(--error);
    }

    .files {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        padding: 0.5rem;
        background-color: color-mix(
            in srgb,
            var(--background) 92%,
            transparent
        );
        border: 1px solid var(--line-soft);
        border-radius: 10px;
        max-height: 200px;
        overflow-y: auto;
    }

    .group {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
    }

    .directory {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.25rem 0;
        font-size: 0.75rem;
        color: var(--text-secondary);
    }

    .file {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.25rem 0.5rem;
        margin-left: 1rem;
        border: 1px solid transparent;
        border-radius: 8px;
        transition:
            border-color 0.15s ease,
            background-color 0.15s ease;
    }

    .file:hover {
        border-color: var(--line-soft);
        background-color: color-mix(in srgb, var(--surface) 92%, transparent);
    }

    .action-icon {
        width: 14px;
        font-size: 0.75rem;
        font-weight: 700;
        text-align: center;
    }

    .file svg {
        color: var(--text-secondary);
        flex-shrink: 0;
    }

    .file-name {
        flex: 1;
        font-size: 0.8125rem;
        color: var(--text-primary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .line-stats {
        display: flex;
        gap: 0.375rem;
        font-size: 0.6875rem;
        font-weight: 500;
    }

    .line-stats .added {
        color: var(--success);
    }

    .line-stats .removed {
        color: var(--error);
    }
</style>
