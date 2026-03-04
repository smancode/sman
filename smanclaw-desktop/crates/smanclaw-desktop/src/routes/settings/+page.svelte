<script lang="ts">
  import { onMount } from 'svelte';
  import { appSettingsApi } from '$lib/api/tauri';
  import type { AppSettings, LlmSettings, ConnectionTestResult } from '$lib/types';

  // Default LLM settings
  const DEFAULT_LLM_URL = 'https://open.bigmodel.cn/api/coding/paas/v4';
  const DEFAULT_MODEL = 'GLM-5';

  let settings: AppSettings = $state({
    llm: { apiUrl: DEFAULT_LLM_URL, apiKey: '', defaultModel: DEFAULT_MODEL },
    embedding: undefined,
    qdrant: undefined
  });

  let isLoading = $state(false);
  let isSaving = $state(false);
  let showApiKey = $state(false);

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
    }
    isLoading = false;
  }

  async function saveSettings() {
    isSaving = true;

    // Vector settings are optional - keep them as-is if already configured
    // If not configured, they remain undefined

    const response = await appSettingsApi.update(settings);
    if (response.success) {
      alert('Settings saved successfully!');
    } else {
      alert('Failed to save settings: ' + response.error);
    }
    isSaving = false;
  }

  async function testLlmConnection() {
    llmTestResult = null;
    const response = await appSettingsApi.testLlm(settings.llm);
    if (response.success) {
      llmTestResult = response.data!;
    } else {
      llmTestResult = { success: false, error: response.error, latencyMs: undefined };
    }
  }
</script>

<div class="settings-page">
  <div class="settings-header">
    <h1>Settings</h1>
    <p class="subtitle">Configure your LLM settings for SmanClaw</p>
  </div>

  {#if isLoading}
    <div class="loading">Loading settings...</div>
  {:else}
    <div class="settings-content">
      <!-- LLM Configuration -->
      <section class="settings-section">
        <h2>LLM Configuration</h2>
        <p class="section-desc">Configure your OpenAI-compatible LLM API (智谱 GLM-5 recommended)</p>

        <div class="form-group">
          <label for="llm-url">API URL</label>
          <input
            type="text"
            id="llm-url"
            bind:value={settings.llm.apiUrl}
            placeholder={DEFAULT_LLM_URL}
          />
          <span class="hint">Default: {DEFAULT_LLM_URL}</span>
        </div>

        <div class="form-group">
          <label for="llm-key">API Key <span class="required">*</span></label>
          <div class="input-with-button">
            <input
              type={showApiKey ? 'text' : 'password'}
              id="llm-key"
              bind:value={settings.llm.apiKey}
              placeholder="Enter your API key"
            />
            <button class="btn-icon" onclick={() => showApiKey = !showApiKey} title={showApiKey ? 'Hide' : 'Show'}>
              {showApiKey ? '👁️' : '👁️‍🗨️'}
            </button>
            <button class="btn-secondary" onclick={testLlmConnection}>Test</button>
          </div>
          {#if llmTestResult}
            <div class="test-result {llmTestResult.success ? 'success' : 'error'}">
              {#if llmTestResult.success}
                Connected successfully ({llmTestResult.latencyMs}ms)
              {:else}
                {llmTestResult.error}
              {/if}
            </div>
          {/if}
        </div>

        <div class="form-group">
          <label for="llm-model">Default Model</label>
          <input
            type="text"
            id="llm-model"
            bind:value={settings.llm.defaultModel}
            placeholder={DEFAULT_MODEL}
          />
          <span class="hint">Default: {DEFAULT_MODEL}</span>
        </div>
      </section>

      <!-- Advanced Settings Note -->
      <section class="settings-section info-section">
        <h3>Advanced Settings</h3>
        <p class="info-text">
          Vector store and embedding settings are configured automatically based on your project.
          They are optional and only needed for semantic memory features.
        </p>
      </section>

      <!-- Action Buttons -->
      <div class="actions">
        <button class="btn-secondary" onclick={loadSettings} disabled={isLoading}>
          Reset
        </button>
        <button class="btn-primary" onclick={saveSettings} disabled={isSaving}>
          {isSaving ? 'Saving...' : 'Save Settings'}
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
    color: var(--text-secondary, #888);
    font-size: 0.9rem;
  }

  .loading {
    text-align: center;
    padding: 2rem;
    color: var(--text-secondary, #888);
  }

  .settings-section {
    background: var(--surface, #1a1a1f);
    border: 1px solid var(--border, #2a2a32);
    border-radius: 8px;
    padding: 1.5rem;
    margin-bottom: 1.5rem;
  }

  .settings-section h2 {
    font-size: 1.1rem;
    margin-bottom: 0.5rem;
  }

  .settings-section h3 {
    font-size: 1rem;
    margin-bottom: 0.5rem;
    color: var(--text-secondary, #888);
  }

  .section-desc {
    color: var(--text-secondary, #888);
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
    color: var(--text-secondary, #888);
  }

  .required {
    color: var(--accent, #6366f1);
  }

  .form-group input {
    width: 100%;
    padding: 0.75rem;
    background: var(--background, #0f0f12);
    border: 1px solid var(--border, #2a2a32);
    border-radius: 6px;
    color: var(--text-primary, #f5f5f7);
    font-size: 0.9rem;
  }

  .form-group input:focus {
    outline: none;
    border-color: var(--accent, #6366f1);
  }

  .hint {
    display: block;
    margin-top: 0.25rem;
    font-size: 0.8rem;
    color: var(--text-secondary, #888);
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
    background: var(--surface, #1a1a1f);
    border: 1px solid var(--border, #2a2a32);
    border-radius: 6px;
    cursor: pointer;
  }

  .btn-icon:hover {
    background: var(--border, #2a2a32);
  }

  .btn-secondary {
    padding: 0.5rem 1rem;
    background: var(--surface, #1a1a1f);
    border: 1px solid var(--border, #2a2a32);
    border-radius: 6px;
    color: var(--text-primary, #f5f5f7);
    cursor: pointer;
    font-size: 0.85rem;
  }

  .btn-secondary:hover {
    background: var(--border, #2a2a32);
  }

  .btn-primary {
    padding: 0.75rem 1.5rem;
    background: var(--accent, #6366f1);
    border: none;
    border-radius: 6px;
    color: white;
    cursor: pointer;
    font-size: 0.9rem;
    font-weight: 500;
  }

  .btn-primary:hover {
    opacity: 0.9;
  }

  .btn-primary:disabled,
  .btn-secondary:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .test-result {
    margin-top: 0.5rem;
    padding: 0.5rem;
    border-radius: 4px;
    font-size: 0.85rem;
  }

  .test-result.success {
    background: rgba(34, 197, 94, 0.1);
    color: #22c55e;
  }

  .test-result.error {
    background: rgba(239, 68, 68, 0.1);
    color: #ef4444;
  }

  .info-section {
    background: rgba(99, 102, 241, 0.05);
    border-color: rgba(99, 102, 241, 0.2);
  }

  .info-text {
    color: var(--text-secondary, #888);
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
