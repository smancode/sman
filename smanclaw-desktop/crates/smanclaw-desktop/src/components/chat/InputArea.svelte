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
                Math.min(textareaRef.scrollHeight, 260) + "px";
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
        padding: 0.9rem 1.25rem 1.25rem;
        background-color: transparent;
    }

    .input-container {
        display: flex;
        align-items: center;
        gap: 0.625rem;
        padding: 0.58rem 0.58rem 0.58rem 0.9rem;
        background-color: color-mix(in srgb, var(--surface) 84%, transparent);
        border: 1px solid var(--line-soft);
        border-radius: 16px;
        transition:
            border-color 0.15s,
            box-shadow 0.15s,
            background-color 0.15s;
    }

    .input-container:focus-within {
        border-color: rgba(var(--accent-rgb), 0.55);
        background-color: color-mix(
            in srgb,
            var(--surface-elevated) 92%,
            transparent
        );
        box-shadow: 0 0 0 3px rgba(var(--accent-rgb), 0.18);
    }

    .input-container.disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    textarea {
        flex: 1;
        resize: none;
        color: var(--text-primary);
        font-size: 1rem;
        line-height: 1.55;
        padding: 0.48rem 0;
        background: transparent;
        border: none;
        outline: none;
        min-height: 52px;
        max-height: 260px;
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
        width: 38px;
        height: 38px;
        color: white;
        background-color: var(--accent);
        border-radius: 10px;
        transition:
            background-color 0.15s ease,
            transform 0.15s ease;
        flex-shrink: 0;
    }

    .send-btn:hover:not(:disabled) {
        background-color: var(--accent-hover);
        transform: translateY(-1px);
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
