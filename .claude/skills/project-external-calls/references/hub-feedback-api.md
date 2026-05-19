# Hub Feedback/Error Reporting API

Sman 通过 Hub 服务器上报用户反馈和错误信息，支持加密传输和本地降级。

## API Endpoints

| 端点 | 用途 | 超时 | 降级 |
|------|------|------|------|
| `POST {hubUrl}/api/feedback` | 用户反馈提交 | 10s | 保存到 `~/logs/feedback-{timestamp}.json` |
| `POST {hubUrl}/api/error-report` | 错误日志上报 | 5s | 保存到 `~/logs/error-report-{timestamp}.json` |

## Request Format

**Feedback**:
```typescript
{ clientId: string, message: string, workspace?: string, llmModel?: string, llmBaseUrl?: string, osInfo: string }
```

**Error Report**:
```typescript
{ clientId: string, error: string, stack?: string, sessionId?: string, workspace?: string, osInfo: string }
```

## Security

- **PSK 加密**: AES-256-GCM，密钥来自 `~/.sman/hub.key`
- **防重放**: 时间戳验证（服务端校验时间差）
- **加密函数**: `buildEncryptedRequest(payload)` → `{ encrypted, iv, tag }`

## Implementation

```typescript
// Encryption
const psk = fs.readFileSync(path.join(os.homedir(), '.sman', 'hub.key'), 'utf-8').trim();
const key = crypto.createHash('sha256').update(psk).digest();
const iv = crypto.randomBytes(12);
const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
const plaintext = JSON.stringify({ ...payload, ts: Date.now() });
const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
const tag = cipher.getAuthTag();

// Submission
const response = await fetch(`${hubUrl}/api/feedback`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ encrypted: encrypted.toString('base64'), iv: iv.toString('base64'), tag: tag.toString('base64') }),
  signal: AbortController.timeout(10_000).signal,
});

// Local Fallback
const ts = new Date().toISOString().replace(/[:.]/g, '-');
fs.writeFileSync(path.join(os.homedir(), 'logs', `feedback-${ts}.json`), JSON.stringify(feedback, null, 2));
```

## WebSocket API

```typescript
// 请求
{ type: 'feedback.submit', message: string, workspace?: string }

// 响应
{ type: 'feedback.submit.ack', success: boolean, error?: string, path?: string }
```

## Architectural Decisions

1. **Graceful Degradation**: Hub 不可用时不影响核心功能，反馈自动保存到本地
2. **Privacy First**: LLM 配置仅在用户明确发送反馈时上报，不自动采集
3. **Fast Timeout**: 反馈 10s，错误上报 5s，避免阻塞 UI
4. **No Retries**: 失败不重试（避免刷屏），错误上报静默失败
