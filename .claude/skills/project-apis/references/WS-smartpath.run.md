# `smartpath.run` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'smartpath.run', pathId: string, workspace: string, input?: string }
Server → Client: { type: 'smartpath.scheduledRun', pathId: string, status: string, stepIndex: number }
```

## Request Parameters
- `pathId` (string, required): Path identifier
- `workspace` (string, required): Project directory path
- `input` (string, optional): User input for first step

## Business Flow
1. Load path from SmartPathStore (path.md file)
2. SmartPathEngine executes steps sequentially
3. Each step output becomes next step context
4. Ephemeral sessions (not persisted to SQLite)
5. Stream progress via `smartpath.scheduledRun` broadcasts
6. Generate reports in .sman/paths/{pathId}/reports/

## Called Services
- `SmartPathEngine.runPath()`: Execute path steps
- `ClaudeSessionManager.createEphemeralSession()`: Temp sessions
- `SmartPathStore.saveReport()`: Persist execution reports

## Source File
`server/index.ts:1377-1407`
