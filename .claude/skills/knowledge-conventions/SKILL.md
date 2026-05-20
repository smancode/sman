---
_scanned.commitHash: "353989234d641c959d8c0aa37aea150735c4ccd8"
_scanned.scannedAt: "2026-05-21T00:00:00.000Z"
_scanned.branch: "master"
---

# Development Conventions

Top 8 conventions from incremental scan (commit 353989234d641c959d8c0aa37aea150735c4ccd8).

## 1. Git 操作必须异步化

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/git-handler.ts

所有 Git 操作必须使用异步 `execFile` 而非同步 `execSync`：
- 使用 `promisify(execFile)` 包装成 Promise
- 参数拆分数组传入，避免 shell 注入
- 所有导出函数改为 `async`：`handleGitStatus`, `handleGitDiff`, `handleGitCommit` 等
- WebSocket 处理器用 `.then()/.catch()` 处理异步结果
- AI 冲突解决：git push 遇到冲突时创建 ephemeral session 自动解决

## 2. SDK 会话清理必须完整

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/chatbot/chatbot-session-manager.ts, server/claude-session.ts

Chatbot 会话超时或重置时，必须完整清理 V2 SDK 进程和会话 ID：
1. `abort()` 停止正在进行的流式响应
2. `closeV2Session()` 终止 V2 SDK 进程并删除映射
3. `clearSdkSessionId()` 清除 SDK 会话 ID，防止下次复用旧上下文
- 三步缺一不可，否则会导致上下文残留或僵尸进程

## 3. 脚本文件扩展名白名单

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/index.ts: SCRIPT_EXTENSIONS

Reference 文件只能保存脚本文件，禁止保存数据文件：
- 白名单扩展名：`.py, .sh, .bash, .zsh, .js, .ts, .mjs, .cjs, .bat, .cmd, .ps1, .sql, .r, .rb, .go, .java, .pl, .lua, .php, .rs, .dart, .kt, .scala, .clj`
- 应用场景：Smart Path 提取 `[REFERENCE:filename.ext]` 时过滤，Earth Path 步骤规则明确
- 禁止保存 `.json, .csv, .txt, .xlsx, .xml, .yaml, .yml` 等数据文件
- 设计原因：脚本可复用，数据文件应放在 `tmp/` 中避免耦合

## 4. Smart Path 规则放在 User Prompt

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: buildStepPrompt

Smart Path 的步骤执行规则必须放在 **user prompt** 的 `[规则]` 段落，**绝对不能**修改 system prompt：
- `STEP_SYSTEM_PROMPT` 设为空字符串，规则全部移到 user prompt
- System prompt 是 session 级的，path 不应该污染它
- 步骤级别的限制（workspace skills 限制、脚本文件限制等）放在 user prompt 的 `[规则]` 段落

## 5. 步骤级 Skills 注入

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: buildSkillsContext

Smart Path 步骤可以指定需要使用的 skills，动态注入到 prompt 中：
- 步骤定义中新增 `skills?: string[]` 字段
- 执行时读取 `workspace/.claude/skills/{skillId}/SKILL.md` 内容
- 直接注入到 user prompt 的 `[规则]` 段落之后
- 未指定 skills 时明确告知：`不使用 workspace/.claude/skills 中的 skill`

## 6. 指南内容持久化与注入

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: guideChat, saveGuide

Smart Path 步骤指南必须持久化到 `references/guide{n}.md` 并在下次执行时注入：
- 指南文件命名：`{workspace}/.sman/paths/{pathId}/references/guide{stepIndex}.md`
- 首次执行后生成指南，后续执行自动注入
- 支持多轮对话调整指南内容
- 指南内容放在 `[规则]` 之前，确保优先级

## 7. 目录展开安全限制

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/git-handler.ts: expandDirFiles

Git status 中展开未跟踪目录时，必须限制深度和文件数量：
- 跳过常见大型目录：`node_modules, .git, dist, build, out, .next, .nuxt, target, vendor, __pycache__, .venv, venv, Pods, .gradle, .idea, .cache, coverage`
- 限制递归深度最多 3 层（`MAX_EXPAND_DEPTH=3`）
- 限制展开文件总数最多 500 个（`MAX_EXPAND_FILES=500`）
- 达到限制时直接返回，不抛异常

## 8. Zustand Store 与 WebSocket 集成模式

> by nasakim | 验证: 2026-05 | ✅ [已验证] src/stores/achievement.ts, src/stores/smart-path.ts

Zustand store 与 WebSocket 的标准集成模式：
- 在 store 文件中定义 `useWsConnection` 辅助函数获取 WebSocket client
- 使用 `ensureListeners()` 确保监听器只注册一次（通过 `listenersRegistered` 标志）
- WebSocket 消息监听器中直接调用 `useAchievementStore.setState()` 更新状态
- 返回 cleanup 函数：`client.off(event, handler)` 用于取消监听
- 订阅 `useWsConnection` 的变化，client 断开重连时重新注册监听器
- 所有异步操作（如 `fetchSummary()`）通过 WebSocket 发送消息，不用 fetch API

**示例结构**：
```typescript
let listenersRegistered = false;
let unregisterFn: (() => void) | null = null;

function registerListeners() {
  const client = useWsConnection.getState().client;
  if (!client) return () => {};

  const onMessage = (raw: unknown) => {
    const msg = raw as Record<string, unknown>;
    if (msg.type === 'achievement.data') {
      useAchievementStore.setState({ summary: msg.summary, isLoading: false });
    }
  };

  client.on('achievement.data', onMessage);
  return () => client.off('achievement.data', onMessage);
}

function ensureListeners(): void {
  if (listenersRegistered) return;
  listenersRegistered = true;
  const unsub = useWsConnection.subscribe((state, prev) => {
    if (state.client && state.client !== prev.client) {
      if (unregisterFn) unregisterFn();
      unregisterFn = registerListeners();
    }
  });
  if (useWsConnection.getState().client) {
    unregisterFn = registerListeners();
  }
}
```

## 9. 类型常量集中定义与 i18n key 解耦

> by nasakim | 验证: 2026-05 | ✅ [已验证] src/types/achievement.ts

类型相关的常量（枚举值、图标、颜色、阈值等）必须集中定义在 `types/` 目录，与 i18n key 分离：
- 在 `src/types/` 中定义类型常量（如 `TIER_COLORS`, `CATEGORY_LABELS`）
- 常量对象使用 `Record<Type, T>` 类型，确保类型安全
- 颜色定义包含完整的 light/dark 模式变体（如 `text`, `bg`, `border`, `glow`, `bar`）
- **禁止在常量中硬编码中文文本**（如 `CATEGORY_LABELS` 的 value）
- 所有用户可见文本必须通过 i18n key（如 `t('achievement.tier.gold')`）获取
- 组件中通过 `t(labelKey)` 动态获取翻译，而不是直接使用常量中的文本

**反例（不要这样）**：
```typescript
// ❌ 在常量中硬编码中文
export const CATEGORY_LABELS: Record<Category, string> = {
  conversation: '对话',
  advanced: '进阶',
};
```

**正例（正确做法）**：
```typescript
// ✅ 常量只用于类型安全，文本由 i18n 处理
export const CATEGORY_LABELS: Record<Category, string> = {
  conversation: 'conversation',  // 仅用于调试或日志
  advanced: 'advanced',
};

// 组件中
{t(`achievement.cat.${category}`)}
```
