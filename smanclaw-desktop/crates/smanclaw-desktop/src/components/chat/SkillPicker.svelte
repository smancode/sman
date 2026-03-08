<script lang="ts">
    interface Props {
        skills: Array<{id: string; path: string; tags: string[]}>;
        query: string;
        onSelect: (skill: {id: string; path: string; tags: string[]}) => void;
        onClose: () => void;
    }

    let { skills, query, onSelect, onClose }: Props = $props();

    let filteredSkills = $derived(
        skills.filter(skill => {
            const q = query.toLowerCase();
            return (
                skill.path.toLowerCase().includes(q) ||
                skill.tags.some(tag => tag.toLowerCase().includes(q))
            );
        })
    );

    function handleKeyDown(event: KeyboardEvent) {
        if (event.key === "Escape") {
            onClose();
        }
    }
</script>

<svelte:window onkeydown={handleKeyDown} />

<div class="skill-picker">
    <div class="skill-picker-header">
        <span class="title">技能</span>
        <button class="close-btn" onclick={onClose} aria-label="关闭">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 6L6 18M6 6l12 12" />
            </svg>
        </button>
    </div>

    <div class="skill-list">
        {#if filteredSkills.length === 0}
            <div class="no-results">没有找到匹配的技能</div>
        {:else}
            {#each filteredSkills as skill}
                <button
                    class="skill-item"
                    onclick={() => onSelect(skill)}
                >
                    <span class="skill-path">/{skill.path}</span>
                    {#if skill.tags.length > 0}
                        <span class="skill-tags">
                            {#each skill.tags.slice(0, 3) as tag}
                                <span class="tag">{tag}</span>
                            {/each}
                        </span>
                    {/if}
                </button>
            {/each}
        {/if}
    </div>
</div>

<style>
    .skill-picker {
        position: absolute;
        bottom: 100%;
        left: 0;
        right: 0;
        max-height: 300px;
        background: var(--bg-secondary, #1e1e1e);
        border: 1px solid var(--border, #333);
        border-radius: 12px;
        box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.3);
        overflow: hidden;
        margin-bottom: 8px;
        z-index: 100;
    }

    .skill-picker-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 10px 14px;
        border-bottom: 1px solid var(--border, #333);
    }

    .title {
        font-size: 13px;
        font-weight: 500;
        color: var(--text-secondary, #888);
    }

    .close-btn {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        padding: 0;
        background: transparent;
        border: none;
        border-radius: 4px;
        color: var(--text-secondary, #888);
        cursor: pointer;
    }

    .close-btn:hover {
        background: var(--bg-hover, #2a2a2a);
    }

    .skill-list {
        max-height: 240px;
        overflow-y: auto;
        padding: 6px;
    }

    .no-results {
        padding: 16px;
        text-align: center;
        color: var(--text-secondary, #888);
        font-size: 14px;
    }

    .skill-item {
        display: flex;
        align-items: center;
        justify-content: space-between;
        width: 100%;
        padding: 10px 12px;
        background: transparent;
        border: none;
        border-radius: 8px;
        cursor: pointer;
        text-align: left;
        color: var(--text-primary, #e0e0e0);
    }

    .skill-item:hover {
        background: var(--bg-hover, #2a2a2a);
    }

    .skill-path {
        font-size: 14px;
        font-weight: 500;
    }

    .skill-tags {
        display: flex;
        gap: 4px;
    }

    .tag {
        padding: 2px 6px;
        font-size: 11px;
        background: var(--accent, #4a9eff);
        color: white;
        border-radius: 4px;
    }
</style>
