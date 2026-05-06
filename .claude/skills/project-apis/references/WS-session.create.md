# `session.create` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'session.create', workspace: string }
Server → Client: { type: 'session.created', sessionId: string, workspace: string }
```

## Request Parameters
- `workspace` (string, required): Project directory path

## Business Flow
1. Normalize workspace path
2. Create session in SessionStore
3. Auto-subscribe client to new session
4. Trigger async workspace initialization (non-blocking)
5. Return sessionId to client

## Called Services
- `ClaudeSessionManager.createSession()`: Session creation
- `InitManager.handleSessionCreate()`: Async workspace init
  - WorkspaceScanner: Discover .claude/ structure
  - SkillInjector: Load project skills
  - CapabilityMatcher: Match capabilities
  - ClaudeInitRunner: Run init skills

## Source File
`server/index.ts:769-783`
