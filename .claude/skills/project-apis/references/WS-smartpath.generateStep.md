# `smartpath.generateStep` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'smartpath.generateStep', userInput: string, workspace: string, previousSteps: Array<{userInput, generatedContent?, executionResult?}>, execute?: boolean, pathId?: string, stepIndex?: number, skills?: string[] }
Server → Client: { type: 'smartpath.stepGenerated', generatedContent: string }
                 { type: 'smartpath.stepExecutionCompleted', result: string }
```

## Request Parameters
- `userInput` (string, required): Step input/description
- `workspace` (string, required): Project directory path
- `previousSteps` (array, required): Context from prior steps
- `execute` (boolean, optional): Auto-execute after generation (default: false)
- `pathId` (string, optional): Path ID for reference saving
- `stepIndex` (number, optional): Step index for progress tracking
- `skills` (string[], optional): Restrict available workspace skills 🆕

## Skills Parameter (NEW)
When `skills` array provided:
1. Load each skill from `workspace/.claude/skills/{skillId}/SKILL.md`
2. Inject into step prompt as:
   ```
   [可使用的 Skills — 严格按以下 skill 的指令执行]
   ### Skill: skill-name-1
   ...skill content...

   ### Skill: skill-name-2
   ...skill content...
   ```
3. Step execution restricted to ONLY these skills
4. If `skills` empty/undefined, step instructed to NOT use workspace skills

## Business Flow
1. Build step execution prompt with:
   - User input
   - Previous steps context
   - Reference files (if `pathId` provided)
   - **Skills context (if `skills` provided)** 🆕
   - Step execution rules (no user interaction, concise output)
2. Create ephemeral Claude session
3. Stream generation progress via `smartpath.stepExecutionProgress`
4. If `execute=true`, immediately execute generated step
5. Extract and save `[REFERENCE:filename.ext]` blocks (script files only)
6. Return result or generated content

## Reference File Extraction
Post-execution regex extracts:
```regex
\[REFERENCE:([^\]]+)\]\s*\n```\s*\n([\s\S]*?)```
```
- Only script files saved (.py, .sh, .js, .ts, .bat, .sql, etc.)
- Data files (.json, .csv, .txt, .xlsx, .xml, .yaml) explicitly rejected
- Prevents data coupling - data should be in `tmp/` directory

## Called Services
- `SmartPathEngine.runSingleStep(skills?: string[])`: Execute with skill restrictions 🔄
- `ClaudeSessionManager.createEphemeralSession()`: Temp session
- `SmartPathStore.saveReference()`: Save extracted references
- `buildStepExecutionPrompt(skills?: string[])`: Build prompt with skill context 🔄

## Source File
`server/index.ts:2049-2120`
