# Chatbot Multi-Mode Architecture

## Overview
Chatbot system supports three operation modes with different isolation and concurrency controls.

## Modes

### Full Mode (full)
- **Workspace**: User-selectable via `//cd` command
- **Capabilities**: Full file access, all commands, workspace switching
- **Session Management**: Per-user workspace binding
- **Use Case**: Developer assistants, project-agnostic bots

### Query Mode (query)
- **Workspace**: Fixed to `botProfile.workspace` (read-only)
- **Capabilities**: Read-only access, restricted commands
- **Restrictions**: No `//cd`, no `//workspaces`
- **Use Case**: Q&A bots, documentation assistants

### Collect Mode (collect)
- **Workspace**: Fixed to `~/.sman/iterate/{botProfileId}/`
- **Capabilities**: Feedback collection only
- **Restrictions**: No workspace switching, minimal commands
- **Use Case**: Feedback gathering, bug reporting

## Session ID Format
**Change**: `chatbot-{botProfileId}-{timestamp}-{random8}`

**Purpose**: Enforce isolation across different bot profiles

**Before**: `chatbot-{timestamp}-{random8}`
**After**: `chatbot-{botProfileId}-{timestamp}-{random8}`

## User Key Format
**Change**: `{platform}:{botProfileId}:{userId}`

**Purpose**: Prevent cross-bot session contamination

**Before**: `{platform}:{userId}`
**After**: `{platform}:{botProfileId}:{userId}`

## Concurrency Control

### Global Bot Limit
```typescript
private maxBotConcurrency = 2;
private activeBotCount = 0;
private botQueue: Array<{...}>;
```

**Rules**:
- Max 2 bot requests processing simultaneously (global, not per-bot)
- Same user cannot have concurrent requests (checked via `activeQueries`)
- Queue with position feedback for rejected requests

### Queue Processing
```typescript
private processQueue(): void {
  while (botQueue.length > 0 && activeBotCount < maxBotConcurrency) {
    const next = botQueue.shift()!;
    if (activeQueries.has(next.userKey)) {
      next.sender.finish('跳过排队消息');
      continue;
    }
    // Execute...
  }
}
```

**Behavior**:
- FIFO queue
- Skip queued messages if user has active query
- Auto-advance on completion

## Command Restrictions

### Mode-Based Filtering
```typescript
const isRestricted = mode === 'query' || mode === 'collect';

switch (command.command) {
  case 'cd':
    if (isRestricted) {
      sender.finish(mode === 'collect'
        ? '反馈收集模式，不支持切换项目'
        : '只读答疑模式，不支持切换项目');
      return;
    }
    // ...
}
```

### Help Text Customization
Each mode shows different available commands:
- **collect**: `//new`, `//help`, `//status`
- **query**: `//new`, `//help`, `//status`
- **full**: All commands (`//workspaces`, `//cd`, `//pwd`, etc.)

## Workspace Resolution

### Query Mode
```typescript
workspace = botProfile.workspace;
if (!workspace) {
  sender.finish('Bot 未绑定项目目录');
  return;
}
```

### Collect Mode
```typescript
workspace = path.join(os.homedir(), '.sman/iterate', botProfile.id);
ensureIterateDir(workspace);
```

### Full Mode
```typescript
// Auto-select first available if none set
if (!userState?.currentWorkspace) {
  const workspaces = getDesktopWorkspaces();
  if (workspaces.length > 0) {
    workspace = workspaces[0].path;
  }
}
```

## Configuration Migration

### Old Format
```json
{
  "chatbot": {
    "wecom": {
      "enabled": true,
      "botId": "xxx",
      "secret": "yyy"
    }
  }
}
```

### New Format
```json
{
  "chatbot": {
    "wecom": {
      "enabled": true,
      "bots": [{
        "id": "uuid",
        "label": "Bot Name",
        "botId": "xxx",
        "secret": "yyy",
        "mode": "full",
        "workspace": "",
        "allowedSkills": [],
        "enabled": true
      }]
    }
  }
}
```

**Migration**: Auto-converts on startup, preserves old config

## Isolation Guarantees

### Session Store
```typescript
store.setSession(userKey, workspace, sessionId, botProfile.label);
```

### Session Manager
```typescript
createSessionWithId(workspacePath, sessionId, false);
```

### Bot Profile Injection
```typescript
ensureSession(userKey, workspace, platform, botProfile);
```

## Error Messages

### Mode-Specific
- **Query**: "当前为只读答疑模式，不支持切换项目"
- **Collect**: "当前为反馈收集模式，不支持切换项目"

### Queue Feedback
- "当前有 2 个请求在处理中，你排在第 3 位，请稍候..."
- "你有一条消息正在处理中，请稍后再试"

### Configuration
- "Bot 配置未找到，请联系管理员"
- "Bot 未绑定项目目录，请联系管理员配置"

## Testing Considerations
- Test concurrent requests from different bots
- Verify queue ordering and position feedback
- Test mode-specific command restrictions
- Verify session ID isolation across bots
- Test migration from old config format
