# `smartpath.run` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'smartpath.run', pathId: string, workspace: string, args?: string, useRefs?: boolean }
Server → Client: { type: 'smartpath.running', pathId: string, status: string, stepIndex: number }
```

## Request Parameters
- `pathId` (string, required): Path identifier
- `workspace` (string, required): Project directory path
- `args` (string, optional): User input for first step
- `useRefs` (boolean, optional): Enable reference file injection from previous runs (default: false) 🆕

## Business Flow
1. Load path from SmartPathStore (path.md file)
2. SmartPathEngine executes steps sequentially
3. Each step output becomes next step context
4. Ephemeral sessions (not persisted to SQLite)
5. Stream progress via `smartpath.progress` broadcasts
6. Generate reports in .sman/paths/{pathId}/reports/
7. **If `useRefs=true`**: Inject reference files from `.sman/paths/{pathId}/references/` into step prompts 🆕

## Reference File Injection (NEW)
When `useRefs=true`, step prompts include:
```
[复用资源]
以下是之前运行保存的可复用文件，可以直接使用：
[REFERENCE:script.py]
```python
... content ...
```
```

Only script files are saved as references (.py, .sh, .js, .ts, .bat, .sql, etc.)

## Called Services
- `SmartPathEngine.runPath(useRefs?: boolean)`: Execute path steps with reference flag 🔄
- `ClaudeSessionManager.createEphemeralSession()`: Temp sessions
- `SmartPathStore.listReferences()`: Load reference files 🆕
- `SmartPathStore.saveReport()`: Persist execution reports

## Source File
`server/index.ts:1989-2022`
