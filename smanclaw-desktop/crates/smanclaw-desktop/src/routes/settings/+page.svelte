<script lang="ts">
  import { onMount } from 'svelte';
  import { appSettingsApi } from '$lib/api/tauri';
  import type { AppSettings, LlmSettings, EmbeddingSettings, QdrantSettings, ConnectionTestResult } from '$lib/types';

  let settings: AppSettings = $state({
    llm: { apiUrl: '', apiKey: '', defaultModel: '' },
    embedding: undefined,
    qdrant: undefined
  });

  // Local state for optional sections (to avoid bind issues with optional chaining)
  let embeddingUrl = $state('');
  let embeddingKey = $state('');
  let embeddingModel = $state('text-embedding-3-small');
  let embeddingDims = $state(1536);

  let qdrantUrl = $state('');
  let qdrantCollection = $state('smanclaw_memories');
  let qdrantKey = $state('');

  let isLoading = $state(false);
  let isSaving = $state(false);
  let showApiKey = $state(false);
  let showEmbeddingKey = $state(false);
  let showQdrantKey = $state(false);

  // Test results
  let llmTestResult: ConnectionTestResult | null = $state(null);
  let embeddingTestResult: ConnectionTestResult | null = $state(null);
  let qdrantTestResult: ConnectionTestResult | null = $state(null);

  // Enable flags
  let enableEmbedding = $state(false);
  let enableQdrant = $state(false);

  onMount(async () => {
    await loadSettings();
  });

  async function loadSettings() {
    isLoading = true;
    const response = await appSettingsApi.get();
    if (response.success && response.data) {
      settings = response.data;
      enableEmbedding = !!settings.embedding;
      enableQdrant = !!settings.qdrant;

      // Load embedding values
      if (settings.embedding) {
        embeddingUrl = settings.embedding.apiUrl;
        embeddingKey = settings.embedding.apiKey;
        embeddingModel = settings.embedding.model;
        embeddingDims = settings.embedding.dimensions;
      }

      // Load qdrant values
      if (settings.qdrant) {
        qdrantUrl = settings.qdrant.url;
        qdrantCollection = settings.qdrant.collection;
        qdrantKey = settings.qdrant.apiKey || '';
      }
    }
    isLoading = false;
  }

  async function saveSettings() {
    isSaving = true;

    // Build embedding settings
    if (enableEmbedding) {
      settings.embedding = {
        apiUrl: embeddingUrl,
        apiKey: embeddingKey,
        model: embeddingModel,
        dimensions: embeddingDims
      };
    } else {
      settings.embedding = undefined;
    }

    // Build qdrant settings
    if (enableQdrant) {
      settings.qdrant = {
        url: qdrantUrl,
        collection: qdrantCollection,
        apiKey: qdrantKey || undefined
      };
    } else {
      settings.qdrant = undefined;
    }

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

  async function testEmbeddingConnection() {
    if (!embeddingUrl || !embeddingKey) return;
    embeddingTestResult = null;
    const embSettings: EmbeddingSettings = {
      apiUrl: embeddingUrl,
      apiKey: embeddingKey,
      model: embeddingModel,
      dimensions: embeddingDims
    };
    const response = await appSettingsApi.testEmbedding(embSettings);
    if (response.success) {
      embeddingTestResult = response.data!;
    } else {
      embeddingTestResult = { success: false, error: response.error, latencyMs: undefined };
    }
  }

  async function testQdrantConnection() {
    if (!qdrantUrl) return;
    qdrantTestResult = null;
    const qdrantSettings: QdrantSettings = {
      url: qdrantUrl,
      collection: qdrantCollection,
      apiKey: qdrantKey || undefined
    };
    const response = await appSettingsApi.testQdrant(qdrantSettings);
    if (response.success) {
      qdrantTestResult = response.data!;
    } else {
      qdrantTestResult = { success: false, error: response.error, latencyMs: undefined };
    }
  }
</script>

<div class="settings-page">
  <div class="settings-header">
    <h1>⚙️ Settings</h1>
    <p class="subtitle">Configure your LLM, embedding, and vector store settings</p>
  </div>

  {#if isLoading}
    <div class="loading">Loading settings...</div>
  {:else}
    <div class="settings-content">
      <!-- LLM Configuration -->
      <section class="settings-section">
        <h2>📡 LLM Configuration</h2>
        <p class="section-desc">Configure your OpenAI-compatible LLM API (required)</p>

        <div class="form-group">
          <label for="llm-url">API URL</label>
          <input
            type="text"
            id="llm-url"
            bind:value={settings.llm.apiUrl}
            placeholder="https://api.openai.com/v1"
          />
        </div>

        <div class="form-group">
          <label for="llm-key">API Key</label>
          <div class="input-with-button">
            <input
              type={showApiKey ? 'text' : 'password'}
              id="llm-key"
              bind:value={settings.llm.apiKey}
              placeholder="sk-..."
            />
            <button class="btn-icon" onclick={() => showApiKey = !showApiKey}>
              {showApiKey ? '👁️' : '👁️‍🗨️'}
            </button>
            <button class="btn-secondary" onclick={testLlmConnection}>Test</button>
          </div>
          {#if llmTestResult}
            <div class="test-result {llmTestResult.success ? 'success' : 'error'}">
              {#if llmTestResult.success}
                ✅ Connected successfully ({llmTestResult.latencyMs}ms)
              {:else}
                ❌ {llmTestResult.error}
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
            placeholder="gpt-4o"
          />
        </div>
      </section>

      <!-- Embedding Configuration -->
      <section class="settings-section">
        <div class="section-header">
          <h2>📊 Embedding Configuration</h2>
          <label class="toggle">
            <input type="checkbox" bind:checked={enableEmbedding} />
            <span class="slider"></span>
            <span class="label">Enable</span>
          </label>
        </div>
        <p class="section-desc">Configure embedding API for semantic search (optional)</p>

        {#if enableEmbedding}
          <div class="form-group">
            <label for="emb-url">API URL</label>
            <div class="input-with-button">
              <input
                type="text"
                id="emb-url"
                bind:value={embeddingUrl}
                placeholder="https://api.openai.com/v1"
              />
              <button class="btn-secondary" onclick={testEmbeddingConnection}>Test</button>
            </div>
            {#if embeddingTestResult}
              <div class="test-result {embeddingTestResult.success ? 'success' : 'error'}">
                {#if embeddingTestResult.success}
                  ✅ Connected successfully ({embeddingTestResult.latencyMs}ms)
                {:else}
                  ❌ {embeddingTestResult.error}
                {/if}
              </div>
            {/if}
          </div>

          <div class="form-group">
            <label for="emb-key">API Key</label>
            <div class="input-with-button">
              <input
                type={showEmbeddingKey ? 'text' : 'password'}
                id="emb-key"
                bind:value={embeddingKey}
                placeholder="sk-..."
              />
              <button class="btn-icon" onclick={() => showEmbeddingKey = !showEmbeddingKey}>
                {showEmbeddingKey ? '👁️' : '👁️‍🗨️'}
              </button>
            </div>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label for="emb-model">Model</label>
              <input
                type="text"
                id="emb-model"
                bind:value={embeddingModel}
                placeholder="text-embedding-3-small"
              />
            </div>
            <div class="form-group">
              <label for="emb-dims">Dimensions</label>
              <input
                type="number"
                id="emb-dims"
                bind:value={embeddingDims}
                placeholder="1536"
              />
            </div>
          </div>
        {/if}
      </section>

      <!-- Qdrant Configuration -->
      <section class="settings-section">
        <div class="section-header">
          <h2>🗄️ Vector Store (Qdrant)</h2>
          <label class="toggle">
            <input type="checkbox" bind:checked={enableQdrant} />
            <span class="slider"></span>
            <span class="label">Enable</span>
          </label>
        </div>
        <p class="section-desc">Configure Qdrant vector database (optional)</p>

        {#if enableQdrant}
          <div class="form-group">
            <label for="qd-url">URL</label>
            <div class="input-with-button">
              <input
                type="text"
                id="qd-url"
                bind:value={qdrantUrl}
                placeholder="http://localhost:6333"
              />
              <button class="btn-secondary" onclick={testQdrantConnection}>Test</button>
            </div>
            {#if qdrantTestResult}
              <div class="test-result {qdrantTestResult.success ? 'success' : 'error'}">
                {#if qdrantTestResult.success}
                  ✅ Connected successfully ({qdrantTestResult.latencyMs}ms)
                {:else}
                  ❌ {qdrantTestResult.error}
                {/if}
              </div>
            {/if}
          </div>

          <div class="form-row">
            <div class="form-group">
              <label for="qd-collection">Collection</label>
              <input
                type="text"
                id="qd-collection"
                bind:value={qdrantCollection}
                placeholder="smanclaw_memories"
              />
            </div>
            <div class="form-group">
              <label for="qd-key">API Key (optional)</label>
              <div class="input-with-button">
                <input
                  type={showQdrantKey ? 'text' : 'password'}
                  id="qd-key"
                  bind:value={qdrantKey}
                  placeholder="Optional"
                />
                <button class="btn-icon" onclick={() => showQdrantKey = !showQdrantKey}>
                  {showQdrantKey ? '👁️' : '👁️‍🗨️'}
                </button>
              </div>
            </div>
          </div>
        {/if}
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

  .section-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 0.5rem;
  }

  .section-header h2 {
    font-size: 1.1rem;
    margin: 0;
  }

  .settings-section h2 {
    font-size: 1.1rem;
    margin-bottom: 0.5rem;
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

  .form-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 1rem;
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

  .toggle {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    cursor: pointer;
  }

  .toggle input {
    display: none;
  }

  .toggle .slider {
    width: 36px;
    height: 20px;
    background: var(--border, #2a2a32);
    border-radius: 10px;
    position: relative;
    transition: background 0.2s;
  }

  .toggle .slider::after {
    content: '';
    position: absolute;
    width: 16px;
    height: 16px;
    background: var(--text-primary, #f5f5f7);
    border-radius: 50%;
    top: 2px;
    left: 2px;
    transition: transform 0.2s;
  }

  .toggle input:checked + .slider {
    background: var(--accent, #6366f1);
  }

  .toggle input:checked + .slider::after {
    transform: translateX(16px);
  }

  .toggle .label {
    font-size: 0.85rem;
    color: var(--text-secondary, #888);
  }

  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 1rem;
    margin-top: 2rem;
  }
</style>
