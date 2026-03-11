<script lang="ts">
    import { onMount } from "svelte";
    import { appSettingsApi } from "$lib/api/tauri";
    import type { AppSettings, ConnectionTestResult } from "$lib/types";

    // Default LLM settings
    const DEFAULT_LLM_URL = "https://open.bigmodel.cn/api/coding/paas/v4";
    const DEFAULT_MODEL = "GLM-5";

    let settings: AppSettings = $state({
        llm: {
            apiUrl: DEFAULT_LLM_URL,
            apiKey: "",
            defaultModel: DEFAULT_MODEL,
        },
        embedding: undefined,
        qdrant: undefined,
        webSearch: {
            braveApiKey: "",
            tavilyApiKey: "",
            bingApiKey: "",
        },
    });

    let isLoading = $state(false);
    let isSaving = $state(false);
    let showApiKey = $state(false);
    let showWebSearchApiKeys = $state(false);

    // Test results
    let llmTestResult: ConnectionTestResult | null = $state(null);

    onMount(async () => {
        await loadSettings();
    });

    async function loadSettings() {
        isLoading = true;
        const response = await appSettingsApi.get();
        if (response.success && response.data) {
            settings = response.data;
            // Ensure defaults are set if empty
            if (!settings.llm.apiUrl) {
                settings.llm.apiUrl = DEFAULT_LLM_URL;
            }
            if (!settings.llm.defaultModel) {
                settings.llm.defaultModel = DEFAULT_MODEL;
            }
            if (!settings.webSearch) {
                settings.webSearch = {
                    braveApiKey: "",
                    tavilyApiKey: "",
                    bingApiKey: "",
                };
            }
        }
        isLoading = false;
    }

    async function saveSettings() {
        isSaving = true;

        // Vector settings are optional - keep them as-is if already configured
        // If not configured, they remain undefined

        const response = await appSettingsApi.update(settings);
        if (response.success) {
            // Show success in test result area
            llmTestResult = {
                success: true,
                error: undefined,
                latencyMs: undefined,
            };
        } else {
            llmTestResult = {
                success: false,
                error: response.error,
                latencyMs: undefined,
            };
        }
        isSaving = false;
    }

    async function testLlmConnection() {
        llmTestResult = null;
        const response = await appSettingsApi.testLlm(settings.llm);
        if (response.success) {
            llmTestResult = response.data!;
        } else {
            llmTestResult = {
                success: false,
                error: response.error,
                latencyMs: undefined,
            };
        }
    }
</script>

<div class="settings-page">
    <div class="settings-header">
        <h1>设置</h1>
        <p class="subtitle">配置 SmanClaw 的大模型参数</p>
    </div>

    {#if isLoading}
        <div class="loading">正在加载设置...</div>
    {:else}
        <div class="settings-content">
            <!-- LLM Configuration -->
            <section class="settings-section">
                <h2>大模型配置</h2>
                <p class="section-desc">
                    配置兼容 OpenAI 的大模型 API（推荐智谱 GLM-5）
                </p>

                <div class="form-group">
                    <label for="llm-url">API 地址</label>
                    <input
                        type="text"
                        id="llm-url"
                        bind:value={settings.llm.apiUrl}
                        placeholder={DEFAULT_LLM_URL}
                    />
                    <span class="hint">默认值：{DEFAULT_LLM_URL}</span>
                </div>

                <div class="form-group">
                    <label for="llm-key"
                        >API 密钥 <span class="required">*</span></label
                    >
                    <div class="input-with-button">
                        <input
                            type={showApiKey ? "text" : "password"}
                            id="llm-key"
                            bind:value={settings.llm.apiKey}
                            placeholder="请输入 API 密钥"
                        />
                        <button
                            class="btn-icon"
                            onclick={() => (showApiKey = !showApiKey)}
                            title={showApiKey ? "隐藏" : "显示"}
                        >
                            {showApiKey ? "👁️" : "👁️‍🗨️"}
                        </button>
                        <button
                            class="btn-secondary"
                            onclick={testLlmConnection}>测试</button
                        >
                    </div>
                    {#if llmTestResult}
                        <div
                            class="test-result {llmTestResult.success
                                ? 'success'
                                : 'error'}"
                        >
                            {#if llmTestResult.success}
                                连接成功（{llmTestResult.latencyMs}ms）
                            {:else}
                                {llmTestResult.error}
                            {/if}
                        </div>
                    {/if}
                </div>

                <div class="form-group">
                    <label for="llm-model">默认模型</label>
                    <input
                        type="text"
                        id="llm-model"
                        bind:value={settings.llm.defaultModel}
                        placeholder={DEFAULT_MODEL}
                    />
                    <span class="hint">默认值：{DEFAULT_MODEL}</span>
                </div>
            </section>

            <section class="settings-section">
                <h2>Web Search</h2>
                <p class="section-desc">
                    默认使用 DuckDuckGo（免费免 key）。可选配置
                    Brave/Tavily/Bing 付费 key 作为回退。
                </p>
                <div class="form-group">
                    <label for="brave-key">Brave API Key（可选）</label>
                    <div class="input-with-button">
                        <input
                            type={showWebSearchApiKeys ? "text" : "password"}
                            id="brave-key"
                            bind:value={settings.webSearch.braveApiKey}
                            placeholder="未配置则不启用 Brave 回退"
                        />
                        <button
                            class="btn-icon"
                            onclick={() =>
                                (showWebSearchApiKeys = !showWebSearchApiKeys)}
                            title={showWebSearchApiKeys ? "隐藏" : "显示"}
                        >
                            {showWebSearchApiKeys ? "👁️" : "👁️‍🗨️"}
                        </button>
                    </div>
                </div>
                <div class="form-group">
                    <label for="tavily-key">Tavily API Key（可选）</label>
                    <input
                        type={showWebSearchApiKeys ? "text" : "password"}
                        id="tavily-key"
                        bind:value={settings.webSearch.tavilyApiKey}
                        placeholder="未配置则不启用 Tavily 回退"
                    />
                </div>
                <div class="form-group">
                    <label for="bing-key">Bing API Key（可选）</label>
                    <input
                        type={showWebSearchApiKeys ? "text" : "password"}
                        id="bing-key"
                        bind:value={settings.webSearch.bingApiKey}
                        placeholder="未配置则不启用 Bing 回退"
                    />
                </div>
            </section>

            <!-- Advanced Settings Note -->
            <section class="settings-section info-section">
                <h3>高级说明</h3>
                <p class="info-text">
                    向量库与向量嵌入设置会根据你的项目自动配置。它们是可选项，
                    仅在语义记忆功能中需要。
                </p>
            </section>

            <!-- Action Buttons -->
            <div class="actions">
                <button
                    class="btn-secondary"
                    onclick={loadSettings}
                    disabled={isLoading}
                >
                    重置
                </button>
                <button
                    class="btn-primary"
                    onclick={saveSettings}
                    disabled={isSaving}
                >
                    {isSaving ? "保存中..." : "保存设置"}
                </button>
            </div>
        </div>
    {/if}
</div>

<style>
    .settings-page {
        padding: 2rem;
        max-width: 800px;
        margin: 0 auto;
    }

    .settings-header {
        margin-bottom: 2rem;
    }

    .settings-header h1 {
        font-size: 1.75rem;
        margin-bottom: 0.5rem;
    }

    .subtitle {
        color: var(--text-secondary);
        font-size: 0.9rem;
    }

    .loading {
        text-align: center;
        padding: 2rem;
        color: var(--text-secondary);
    }

    .settings-section {
        background: color-mix(in srgb, var(--surface) 96%, transparent);
        border: 1px solid var(--line-soft);
        border-radius: 12px;
        padding: 1.5rem;
        margin-bottom: 1.5rem;
        box-shadow: var(--shadow-soft);
    }

    .settings-section h2 {
        font-size: 1.1rem;
        margin-bottom: 0.5rem;
    }

    .settings-section h3 {
        font-size: 1rem;
        margin-bottom: 0.5rem;
        color: var(--text-secondary);
    }

    .section-desc {
        color: var(--text-secondary);
        font-size: 0.85rem;
        margin-bottom: 1.5rem;
    }

    .form-group {
        margin-bottom: 1rem;
    }

    .form-group label {
        display: block;
        margin-bottom: 0.5rem;
        font-size: 0.9rem;
        color: var(--text-secondary);
    }

    .required {
        color: var(--accent);
    }

    .form-group input {
        width: 100%;
        padding: 0.75rem;
        background: color-mix(in srgb, var(--background) 92%, transparent);
        border: 1px solid var(--line-soft);
        border-radius: 10px;
        color: var(--text-primary);
        font-size: 0.9rem;
        transition:
            border-color 0.15s ease,
            box-shadow 0.15s ease,
            background-color 0.15s ease;
    }

    .form-group input:focus {
        outline: none;
        border-color: rgba(var(--accent-rgb), 0.48);
        background: color-mix(
            in srgb,
            var(--surface-elevated) 92%,
            transparent
        );
        box-shadow: 0 0 0 3px rgba(var(--accent-rgb), 0.16);
    }

    .hint {
        display: block;
        margin-top: 0.25rem;
        font-size: 0.8rem;
        color: var(--text-secondary);
    }

    .input-with-button {
        display: flex;
        gap: 0.5rem;
    }

    .input-with-button input {
        flex: 1;
    }

    .btn-icon {
        padding: 0.5rem 0.75rem;
        border: 1px solid var(--line-soft);
        background: color-mix(
            in srgb,
            var(--surface-elevated) 92%,
            transparent
        );
        border-radius: 10px;
        cursor: pointer;
        transition:
            color 0.15s ease,
            border-color 0.15s ease,
            background-color 0.15s ease;
    }

    .btn-icon:hover {
        border-color: var(--line-strong);
        background: var(--surface-hover);
    }

    .btn-secondary {
        padding: 0.5rem 1rem;
        border: 1px solid var(--line-soft);
        background: color-mix(
            in srgb,
            var(--surface-elevated) 92%,
            transparent
        );
        border-radius: 10px;
        color: var(--text-primary);
        cursor: pointer;
        font-size: 0.85rem;
        transition:
            border-color 0.15s ease,
            background-color 0.15s ease;
    }

    .btn-secondary:hover {
        border-color: var(--line-strong);
        background: var(--surface-hover);
    }

    .btn-primary {
        padding: 0.75rem 1.5rem;
        background: var(--accent);
        border: none;
        border-radius: 10px;
        color: var(--text-primary);
        cursor: pointer;
        font-size: 0.9rem;
        font-weight: 500;
        transition:
            background-color 0.15s ease,
            transform 0.15s ease;
    }

    .btn-primary:hover {
        background: var(--accent-hover);
        transform: translateY(-1px);
    }

    .btn-primary:disabled,
    .btn-secondary:disabled {
        opacity: 0.5;
        cursor: not-allowed;
    }

    .test-result {
        margin-top: 0.5rem;
        padding: 0.625rem 0.75rem;
        border: 1px solid transparent;
        border-radius: 10px;
        font-size: 0.85rem;
    }

    .test-result.success {
        background: rgba(63, 185, 128, 0.14);
        border-color: rgba(63, 185, 128, 0.36);
        color: var(--success);
    }

    .test-result.error {
        background: rgba(218, 101, 101, 0.14);
        border-color: rgba(218, 101, 101, 0.32);
        color: var(--error);
    }

    .info-section {
        background: color-mix(
            in srgb,
            var(--surface-elevated) 90%,
            transparent
        );
        border-color: rgba(var(--accent-rgb), 0.22);
    }

    .info-text {
        color: var(--text-secondary);
        font-size: 0.85rem;
        line-height: 1.5;
    }

    .actions {
        display: flex;
        justify-content: flex-end;
        gap: 1rem;
        margin-top: 2rem;
    }
</style>
