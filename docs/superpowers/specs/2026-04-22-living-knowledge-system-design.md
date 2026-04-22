# Living Knowledge System Design

## Context

sman 的知识系统（Knowledge Extractor + skill-auto-updater）目前是"只追加不淘汰"的模式。知识文件只会增长，不会自我更新或纠错。开发过程中方案频繁变迁（A→B→C），但系统只记录不修正，导致知识 skill 充斥过时信息。

**核心问题**：知识没有生命力——不会自纠错、不验证、不淘汰、不处理冲突。

**目标**：让知识系统成为"活的"——自动跟踪方案变迁、验证知识真实性、标记冲突和存疑、清理已聚合的素材。

## Architecture

```
第一层：KnowledgeExtractor (TypeScript, 自动运行)
  对话 → 10分钟冷却 + 3分钟空闲窗口 → LLM 提取
  目标导向 prompt：只保留项目当前真实状态，方案变了就更新
  输出：.sman/knowledge/{category}-{user}.md（快速变动的素材）

第二层：skill-auto-updater (Claude Skill, cron 触发)
  每30分钟 + 5分钟空闲 + 串行执行
  辩证聚合：Claude 读代码验证每条知识
  输出：.claude/skills/knowledge-{category}/SKILL.md（可靠的开发知识库）
  副作用：删除源文件中已处理的条目
```

## Changes

### 1. KnowledgeExtractor 空闲检查

**文件**: `server/knowledge-extractor.ts`

**当前**: `this.isIdle = () => this.activeStreams.size === 0`（瞬时快照）

**改为**: 使用 `lastStreamActivityAt`，距今 >= 3 分钟才算空闲。

**实现方式**: 改 `setIdleCheck` 签名为 `setIdleCheck(getLastActivity: () => number)`，`ClaudeSessionManager.setKnowledgeExtractor()` 注入时传入 `() => this.getLastStreamActivityAt()`。Extractor 内部轮询时用 `Date.now() - getLastActivity() >= 3 * 60 * 1000 || getLastActivity() === 0` 判断。注意 `UserProfileManager` 使用同样的 boolean 模式（`claude-session.ts:144`），**不受影响**。

### 2. KnowledgeExtractor Prompt 重写

**文件**: `server/knowledge-extractor.ts` — `callLLMForExtraction()` 的 system prompt

**原则**: 目标导向，不列步骤。LLM 足够聪明，告诉它最终状态是什么。

**核心规则**:

1. **最终状态**: 每个知识文件反映项目当前的真实状态，不是历史堆砌
2. **方案变迁**: 同一问题答案变了（A→B），只保留 B，不保留废弃的 A
3. **质量门槛**: 只记录项目特有的、非显而易见的、对未来有指导的。通用知识/调试过程/看代码就知道的不记
4. **格式**: 每条知识用 `<!-- hash: xxx --> ... <!-- end: xxx -->` 包裹，6位 hex。文件 100 行以内

**删除的旧规则**: "保留已有 hash 条目一个字不改"——这正是阻止自我更新的根源。

**新增**: 已有条目如果仍成立就保留；方案变了就更新为当前方案（换新 hash）；过时的直接删除。

**示例（方案变迁）**:
```
旧知识文件中:
## 认证方式
<!-- hash: a1b2c3 -->
- 使用 Session 认证
<!-- end: a1b2c3 -->

对话中提到"认证已经从 Session 改成了 JWT"。

更新后:
## 认证方式
<!-- hash: d4e5f6 -->
- 使用 JWT 认证，Token 有效期 2 小时
<!-- end: d4e5f6 -->
（旧 hash a1b2c3 被替换为 d4e5f6，内容更新为当前方案）
```

### 3. skill-auto-updater SKILL.md 第三节重写

**文件**: `server/init/templates/skill-auto-updater/SKILL.md`

**聚合目标**: 生成的 knowledge skill 是可直接依赖的开发知识库。每条知识必须经得起验证。

**辩证聚合要求**:

- **验证**: Read/Grep 查代码确认知识是否成立。成立的标 `✅ [已验证]` + 代码位置
- **冲突**: 多用户说法不同，以代码为准，标 `⚠️ [冲突]` + 各方说法 + 代码实际
- **变迁**: 方案 A→B，标 `🔄 [变迁]` + 旧方案 + 新方案
- **存疑**: 无法验证的，标 `❓ [待验证]`，保留但提醒
- **来源**: 每条标 `> by {用户} | 验证: {YYYY-MM}`

**验证标记**:

| 标记 | 含义 |
|------|------|
| `✅ [已验证]` | 代码确认成立 |
| `⚠️ [冲突]` | 多用户不一致，以代码为准 |
| `🔄 [变迁]` | 方案发生过变化 |
| `❓ [待验证]` | 无法验证，保留待确认 |

**源文件清理**: 聚合完成后，用 Edit 工具从 `.sman/knowledge/*.md` 删除已处理的 hash 条目。只保留未聚合的新条目作为下一轮素材。

**安全约束**: 源文件清理必须在 skill 文件写入成功之后执行。如果 skill 写入失败，不清理源文件。每次验证条目数上限 30 条（防超时）。

### 4. injectMetaSkills 升级 crontab.md

**文件**: `server/init/init-manager.ts`

skill-auto-updater mtime 升级时，复制整个模板目录（包括 crontab.md）。当前代码已支持（`readdirSync + copyFileSync`）。

## Files Changed

| File | Change |
|------|--------|
| `server/knowledge-extractor.ts` | 空闲检查改 `lastStreamActivityAt`；prompt 重写为目标导向 |
| `server/init/templates/skill-auto-updater/SKILL.md` | 重写第三节：辩证聚合 + 验证标记 + 源文件清理 |

## Not Changed

- `server/claude-session.ts` — `getLastStreamActivityAt()` 已实现
- `server/cron-executor.ts` — 5 分钟空闲 + 串行已实现
- `server/init/init-manager.ts` — mtime 升级已实现
- `server/knowledge-extractor-store.ts` — 进度追踪不变
- Hash 格式不变：`<!-- hash: xxx --> ... <!-- end: xxx -->`
- 三个类别不变：business / conventions / technical

## Edge Cases

| Scenario | Handling |
|----------|----------|
| `.sman/knowledge/` 为空 | 跳过聚合，不生成空 skill |
| 所有条目验证失败 | 不生成 skill，源文件照常清理 |
| 源文件被删空 | 正常，下次 KE 重新填充 |
| 源文件清理中途失败 | 只在 skill 写入成功后清理；部分损坏不影响下次 KE 全量重写 |
| 验证条目超过 30 条 | 只处理最新 30 条，剩余留到下一轮 |
| 多人同项目不同步 | 每人本地独立，聚合时合并 |
| skill 文件不存在 | 聚合时创建 |

## Verification

1. `npx tsc --noEmit` — 编译通过
2. `npx vitest run tests/server/` — 全部测试通过
3. 手动检查：KE prompt 是否目标导向、skill-auto-updater 是否包含验证标记体系
