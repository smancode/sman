<script lang="ts">
  import { onMount } from "svelte";
  import { appSettingsApi, openclawApi } from "$lib/api/tauri";
  import type { AppSettings, ConnectionTestResult, LlmSettings } from "$lib/types";

  // Provider types
  type Provider = "zhipu" | "minimax" | "custom";

  // Provider default configurations
  const PROVIDER_DEFAULTS: Record<Provider, { name: string; url: string; defaultModel: string }> = {
    zhipu: {
      name: "智谱 AI",
      url: "https://open.bigmodel.cn/api/coding/paas/v4",
      defaultModel: "GLM-5",
    },
    minimax: {
      name: "MiniMax 国内版",
      url: "https://api.minimaxi.com/v1",
      defaultModel: "MiniMax-M2.5",
    },
    custom: {
      name: "自定义",
      url: "",
      defaultModel: "",
    },
  };

  // Detect provider from URL
  function detectProvider(url: string): Provider {
    if (url.includes("minimaxi")) return "minimax";
    if (url.includes("bigmodel.cn") || url.includes("zhipuai")) return "zhipu";
    return "custom";
  }

  // Per-provider settings storage
  type ProviderSettingsMap = Record<Provider, LlmSettings>;

  // Default settings for each provider
  const DEFAULT_PROVIDER_SETTINGS: ProviderSettingsMap = {
    zhipu: {
      apiUrl: PROVIDER_DEFAULTS.zhipu.url,
      apiKey: "",
      defaultModel: PROVIDER_DEFAULTS.zhipu.defaultModel,
    },
    minimax: {
      apiUrl: PROVIDER_DEFAULTS.minimax.url,
      apiKey: "",
      defaultModel: PROVIDER_DEFAULTS.minimax.defaultModel,
    },
    custom: {
      apiUrl: "",
      apiKey: "",
      defaultModel: "",
    },
  };

  // Current active settings (saved to backend)
  let settings: AppSettings = $state({
    llm: { ...DEFAULT_PROVIDER_SETTINGS.zhipu },
    embedding: undefined,
    qdrant: undefined,
    webSearch: {
      braveApiKey: "",
      tavilyApiKey: "",
      bingApiKey: "",
    },
  });

  // Store settings for each provider separately
  let providerSettings: ProviderSettingsMap = $state({ ...DEFAULT_PROVIDER_SETTINGS });

  // Currently selected provider
  let selectedProvider: Provider = $state("zhipu");

  // UI states
  let isLoading = $state(false);
  let isSaving = $state(false);
  let showApiKey = $state(false);
  let showWebSearchApiKeys = $state(false);
  let saveMessage = $state<{ type: "success" | "error"; text: string } | null>(null);
  let llmTestResult: ConnectionTestResult | null = $state(null);

  onMount(async () => {
    await loadSettings();
  });

  async function loadSettings() {
    isLoading = true;
    const response = await appSettingsApi.get();
    if (response.success && response.data) {
      const loadedSettings = response.data;

      // Initialize web search settings
      if (!loadedSettings.webSearch) {
        loadedSettings.webSearch = {
          braveApiKey: "",
          tavilyApiKey: "",
          bingApiKey: "",
        };
      }

      // Detect current provider from saved URL
      const currentUrl = loadedSettings.llm?.apiUrl || "";
      const detectedProvider = detectProvider(currentUrl);
      selectedProvider = detectedProvider;

      // Load provider-specific settings from localStorage
      loadProviderSettingsFromStorage();

      // Update the detected provider with saved values
      providerSettings[detectedProvider] = {
        apiUrl: loadedSettings.llm?.apiUrl || DEFAULT_PROVIDER_SETTINGS[detectedProvider].apiUrl,
        apiKey: loadedSettings.llm?.apiKey || "",
        defaultModel: loadedSettings.llm?.defaultModel || DEFAULT_PROVIDER_SETTINGS[detectedProvider].defaultModel,
      };

      // Set current active settings to the detected provider
      settings = {
        ...loadedSettings,
        llm: { ...providerSettings[detectedProvider] },
      };
    } else {
      // No saved settings, use defaults
      loadProviderSettingsFromStorage();
      settings.llm = { ...providerSettings.zhipu };
    }
    isLoading = false;
  }

  // Load provider settings from localStorage
  function loadProviderSettingsFromStorage() {
    try {
      const saved = localStorage.getItem("sman_provider_settings");
      if (saved) {
        const parsed = JSON.parse(saved);
        providerSettings = {
          zhipu: { ...DEFAULT_PROVIDER_SETTINGS.zhipu, ...parsed.zhipu },
          minimax: { ...DEFAULT_PROVIDER_SETTINGS.minimax, ...parsed.minimax },
          custom: { ...DEFAULT_PROVIDER_SETTINGS.custom, ...parsed.custom },
        };
      } else {
        providerSettings = { ...DEFAULT_PROVIDER_SETTINGS };
      }
    } catch {
      providerSettings = { ...DEFAULT_PROVIDER_SETTINGS };
    }
  }

  // Save provider settings to localStorage
  function saveProviderSettingsToStorage() {
    try {
      localStorage.setItem("sman_provider_settings", JSON.stringify(providerSettings));
    } catch (e) {
      console.error("Failed to save provider settings:", e);
    }
  }

  // Switch to a different provider
  function switchProvider(provider: Provider) {
    // Save current provider's settings before switching
    providerSettings[selectedProvider] = {
      apiUrl: settings.llm.apiUrl,
      apiKey: settings.llm.apiKey,
      defaultModel: settings.llm.defaultModel,
    };
    saveProviderSettingsToStorage();

    // Switch to new provider
    selectedProvider = provider;

    // Load the new provider's settings
    const newSettings = providerSettings[provider];

    // If provider has no URL set, use default
    if (!newSettings.apiUrl && provider !== "custom") {
      newSettings.apiUrl = PROVIDER_DEFAULTS[provider].url;
      newSettings.defaultModel = PROVIDER_DEFAULTS[provider].defaultModel;
    }

    settings.llm = { ...newSettings };
  }

  async function saveSettings() {
    isSaving = true;
    saveMessage = null;
    llmTestResult = null;

    // Update current provider's settings in storage
    providerSettings[selectedProvider] = {
      apiUrl: settings.llm.apiUrl,
      apiKey: settings.llm.apiKey,
      defaultModel: settings.llm.defaultModel,
    };
    saveProviderSettingsToStorage();

    // Check if API key is provided
    if (!settings.llm.apiKey.trim()) {
      const response = await appSettingsApi.update(settings);
      if (response.success) {
        saveMessage = { type: "success", text: "设置已保存（未配置 API Key）" };
      } else {
        saveMessage = { type: "error", text: `保存失败: ${response.error}` };
      }
      isSaving = false;
      return;
    }

    // Test LLM connection first
    llmTestResult = null;
    const testResponse = await appSettingsApi.testLlm(settings.llm);

    if (!testResponse.success || !testResponse.data?.success) {
      llmTestResult = {
        success: false,
        error: testResponse.data?.error || testResponse.error || "连接测试失败",
        latencyMs: undefined,
      };
      saveMessage = { type: "error", text: "API 连接测试失败，请检查配置" };
      isSaving = false;
      return;
    }

    llmTestResult = testResponse.data!;

    // Save settings
    const saveResponse = await appSettingsApi.update(settings);
    if (!saveResponse.success) {
      saveMessage = { type: "error", text: `保存失败: ${saveResponse.error}` };
      isSaving = false;
      return;
    }

    // Try to restart OpenClaw server
    try {
      await openclawApi.stop();
      const startResponse = await openclawApi.start();
      if (startResponse.success) {
        saveMessage = { type: "success", text: "设置保存成功！OpenClaw 已重启" };
      } else {
        saveMessage = { type: "success", text: "设置保存成功！请重启应用使配置生效" };
      }
    } catch (err) {
      saveMessage = { type: "success", text: "设置保存成功！请重启应用使配置生效" };
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
          选择提供商，每个提供商的配置会独立保存
        </p>

        <div class="form-group">
          <span class="form-label">提供商</span>
          <div class="provider-buttons">
            <button
              class="provider-btn {selectedProvider === 'zhipu' ? 'active' : ''}"
              onclick={() => switchProvider('zhipu')}
            >
              智谱 AI
            </button>
            <button
              class="provider-btn {selectedProvider === 'minimax' ? 'active' : ''}"
              onclick={() => switchProvider('minimax')}
            >
              MiniMax
            </button>
            <button
              class="provider-btn {selectedProvider === 'custom' ? 'active' : ''}"
              onclick={() => switchProvider('custom')}
            >
              自定义
            </button>
          </div>
        </div>

        <div class="form-group">
          <label for="llm-url">API 地址</label>
          <input
            type="text"
            id="llm-url"
            bind:value={settings.llm.apiUrl}
            placeholder="https://..."
          />
        </div>

        <div class="form-group">
          <label for="llm-key">API 密钥 <span class="required">*</span></label>
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
            <button class="btn-secondary" onclick={testLlmConnection}
              >测试</button
            >
          </div>
          {#if llmTestResult}
            <div
              class="test-result {llmTestResult.success ? 'success' : 'error'}"
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
            placeholder="例如：GLM-5"
          />
        </div>
      </section>

      <section class="settings-section">
        <h2>Web Search</h2>
        <p class="section-desc">
          默认使用 DuckDuckGo（免费免 key）。可选配置 Brave/Tavily/Bing 付费 key
          作为回退。
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
              onclick={() => (showWebSearchApiKeys = !showWebSearchApiKeys)}
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
        {#if saveMessage}
          <div class="save-message {saveMessage.type}">
            {saveMessage.text}
          </div>
        {/if}
        <button
          class="btn-secondary"
          onclick={loadSettings}
          disabled={isLoading}
        >
          重置
        </button>
        <button class="btn-primary" onclick={saveSettings} disabled={isSaving}>
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

  .form-group label,
  .form-label {
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
    background: color-mix(in srgb, var(--surface-elevated) 92%, transparent);
    box-shadow: 0 0 0 3px rgba(var(--accent-rgb), 0.16);
  }

  .hint {
    display: block;
    margin-top: 0.25rem;
    font-size: 0.8rem;
    color: var(--text-secondary);
  }

  .provider-buttons {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
  }

  .provider-btn {
    padding: 0.5rem 1rem;
    border: 1px solid var(--line-soft);
    background: color-mix(in srgb, var(--surface-elevated) 92%, transparent);
    border-radius: 10px;
    color: var(--text-primary);
    cursor: pointer;
    font-size: 0.85rem;
    transition:
      border-color 0.15s ease,
      background-color 0.15s ease,
      color 0.15s ease;
  }

  .provider-btn:hover {
    border-color: var(--line-strong);
    background: var(--surface-hover);
  }

  .provider-btn.active {
    background: var(--accent);
    border-color: var(--accent);
    color: white;
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
    background: color-mix(in srgb, var(--surface-elevated) 92%, transparent);
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
    background: color-mix(in srgb, var(--surface-elevated) 92%, transparent);
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
    background: color-mix(in srgb, var(--surface-elevated) 90%, transparent);
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
    align-items: center;
    gap: 1rem;
    margin-top: 2rem;
  }

  .save-message {
    flex: 1;
    padding: 0.75rem 1rem;
    border-radius: 10px;
    font-size: 0.9rem;
  }

  .save-message.success {
    background: rgba(63, 185, 128, 0.14);
    border: 1px solid rgba(63, 185, 128, 0.36);
    color: var(--success);
  }

  .save-message.error {
    background: rgba(218, 101, 101, 0.14);
    border: 1px solid rgba(218, 101, 101, 0.32);
    color: var(--error);
  }
</style>
