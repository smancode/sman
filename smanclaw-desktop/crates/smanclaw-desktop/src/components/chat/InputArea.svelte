<script lang="ts">
    import { activeTask } from "../../lib/stores/tasks";

    interface Props {
        disabled?: boolean;
        placeholder?: string;
        onSubmit: (prompt: string) => void;
    }

    let {
        disabled = false,
        placeholder = "请输入消息...",
        onSubmit,
    }: Props = $props();

    let inputValue = $state("");
    let textareaRef: HTMLTextAreaElement = $state()!;
    let isComposing = $state(false);

    function handleSubmit() {
        const trimmed = inputValue.trim();
        if (!trimmed || disabled) {
            return;
        }

        onSubmit(trimmed);
        inputValue = "";
        adjustHeight();
    }

    function handleKeyDown(event: KeyboardEvent) {
        if (event.key === "Enter" && !event.shiftKey) {
            if (isComposing || event.isComposing || event.keyCode === 229) {
                return;
            }
            event.preventDefault();
            handleSubmit();
        }
    }

    function handleCompositionStart() {
        isComposing = true;
    }

    function handleCompositionEnd() {
        isComposing = false;
    }

    function handleInput() {
        adjustHeight();
    }

    function adjustHeight() {
        if (textareaRef) {
            textareaRef.style.height = "auto";
            textareaRef.style.height =
                Math.min(textareaRef.scrollHeight, 200) + "px";
        }
    }
</script>

<div class="input-area">
    <div class="input-container" class:disabled>
        <textarea
            bind:this={textareaRef}
            bind:value={inputValue}
            {placeholder}
            {disabled}
            rows="1"
            onkeydown={handleKeyDown}
            oninput={handleInput}
            oncompositionstart={handleCompositionStart}
            oncompositionend={handleCompositionEnd}
        ></textarea>

        <button
            class="send-btn"
            onclick={handleSubmit}
            disabled={disabled || !inputValue.trim()}
            aria-label="发送消息"
        >
            {#if $activeTask?.status === "running"}
                <div class="spinner"></div>
            {:else}
                <svg
                    xmlns="http://www.w3.org/2000/svg"
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                >
                    <path d="M22 2L11 13" />
                    <path d="M22 2l-7 20-4-9-9-4 20-7z" />
                </svg>
            {/if}
        </button>
    </div>
</div>

<style>
    .input-area {
        display: flex;
        flex-direction: column;
        padding: 0.85rem 1rem;
        background-color: var(--surface);
    }

    .input-container {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.45rem 0.45rem 0.45rem 0.75rem;
        background-color: var(--background);
        border: 1px solid var(--border);
        border-radius: 12px;
        transition:
            border-color 0.15s,
            box-shadow 0.15s;
    }

    .input-container:focus-within {
        border-color: var(--accent);
        box-shadow: 0 0 0 3px rgba(var(--accent-rgb), 0.16);
    }

    .input-container.disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    textarea {
        flex: 1;
        resize: none;
        color: var(--text-primary);
        font-size: 0.92rem;
        line-height: 1.45;
        padding: 0.42rem 0;
        background: transparent;
        border: none;
        outline: none;
        min-height: 38px;
        max-height: 200px;
    }

    textarea::placeholder {
        color: var(--text-secondary);
    }

    textarea:disabled {
        cursor: not-allowed;
    }

    .send-btn {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 36px;
        height: 36px;
        color: white;
        background-color: var(--accent);
        border-radius: 10px;
        transition: all 0.15s;
        flex-shrink: 0;
    }

    .send-btn:hover:not(:disabled) {
        background-color: var(--accent-hover);
    }

    .send-btn:disabled {
        opacity: 0.5;
        cursor: not-allowed;
    }

    .spinner {
        width: 18px;
        height: 18px;
        border: 2px solid white;
        border-top-color: transparent;
        border-radius: 50%;
        animation: spin 0.8s linear infinite;
    }

    @keyframes spin {
        to {
            transform: rotate(360deg);
        }
    }
</style>
