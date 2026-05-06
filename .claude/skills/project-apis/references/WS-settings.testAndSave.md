# `settings.testAndSave` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'settings.testAndSave', apiKey: string, model: string, baseUrl?: string, profileName?: string }
Server → Client: { type: 'settings.testResult', success: boolean, capabilities?: LlmCapabilities, savedLlms?: LlmProfile[], error?: string }
```

## Request Parameters
- `apiKey` (string, required): LLM API key
- `model` (string, required): Model name
- `baseUrl` (string, optional): Custom API base URL
- `profileName` (string, optional): Profile name (default: "默认")

## Business Flow
1. Validate required fields (apiKey, model)
2. Test Anthropic API compatibility (testAnthropicCompat)
3. Detect model capabilities (3-layer detection):
   - API response headers
   - Model name patterns
   - Fallback to minimal capabilities
4. Build LlmProfile with detected capabilities
5. Upsert to savedLlms list (by profileName)
6. Update all dependent services
7. Return success with capabilities and savedLlms list

## Called Services
- `testAnthropicCompat()`: API compatibility test
- `detectCapabilities()`: 3-layer capability detection
- `SettingsManager.saveLlmProfile()`: Persist profile
- `SessionManager.updateConfig()`: Update session config
- `BatchEngine.setConfig()`: Update batch engine config

## Source File
`server/index.ts:967-1007`
