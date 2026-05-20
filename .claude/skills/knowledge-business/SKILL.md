---
name: knowledge-business
description: "业务知识：产品需求、用户流程、业务规则、领域术语。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "353989234d641c959d8c0aa37aea150735c4ccd8"
  scannedAt: "2026-05-21T00:00:00.000Z"
  branch: "master"
---

# 业务知识

> 贡献者: nasakim | 验证时间: 2026-05-21

## 核心产品定位
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L1-10
- **Sman**：智能业务系统助手，核心交互方式为多端对话
- 四端支持：桌面端（Electron）、企业微信 Bot、飞书 Bot、微信 Bot
- 选择项目目录即可开始对话，零预配置

## Top 3 核心业务流（本次更新）

### 1. 成就解锁与成长晋升流 ⚠️ NEW
> by nasakim | 验证: 2026-05-21
✅ [已验证] server/achievement-engine.ts, server/achievement-definitions.ts, server/achievement-events.ts
- **用户工作流**：执行业务动作（发消息/完成会话/运行路径等）→ 触发 `emitAchievementEvent()` → AchievementEngine 检查指标阈值 → 满足条件自动解锁成就 → WebSocket 推送 `achievement.unlock` → 前端 Toast 通知 + 卡片点亮
- **业务规则**：
  - **加权积分体系**：12 个维度权重不等（智能路径 5 分/次、会话 3 分/个、Bot 数量 5 分/个、Token 0.000005 分/个）
  - **10 层等级体系**：Bronze(0) → Silver(100) → Gold(300) → Platinum(600) → Diamond(1200) → Star(2000) → King(3200) → Legend(4800) → Epic(7000) → Eternal(10000)
  - **96 个成就**：覆盖 6 大类（对话/高级/探索/协作/Bot/隐藏），事件驱动实时判定，支持隐藏成就解锁后可见
  - **连续活跃追踪**：每日首次活跃记录 `current_streak`，用于计算积分和成就（连续 7/30/100 天等）
- **解决痛点**：缺乏成长激励机制、用户难以感知自身进步、深度功能探索不足

### 2. 多维度排行榜竞争流 ⚠️ NEW
> by nasakim | 验证: 2026-05-21
✅ [已验证] src/features/achievements/LeaderboardTab.tsx, server/achievement-engine.ts
- **用户工作流**：进入成就页面 → 切换到"排行榜"标签 → 选择维度（总分/会话数/消息数/Token/智能路径/Bot/连续天数等）→ 上传维度得分到 Hub → 拉取全局排名 → 展示 Top 100 + 自己排名
- **业务规则**：
  - **10 个维度可切换**：总分默认排序，支持按单一维度对比（如"智能路径运行次数"榜单）
  - **Hub 中心化聚合**：客户端加密上传 `dimensionScores` JSON 到 Hub API，服务端存储并排序
  - **去重机制**：同一 `clientId` 只保留最新数据，防止刷榜
  - **离线降级**：Hub 不可用时显示本地数据提示"排行榜功能需要连接组队服务器"
- **解决痛点**：缺乏社交对比驱动力、无法感知自己在用户群体中的位置、深度用户缺乏荣誉感

### 3. Smart Path 步骤执行后知识固化流 ⚠️ UPDATED
> by nasakim | 验证: 2026-05-21
✅ [已验证] server/smart-path-engine.ts, src/features/smart-paths/index.tsx
- **用户工作流**：步骤执行完成 → 自动展开指南对话面板 → AI 确认结果并生成操作指南 → 用户多轮调整 → 点击"确认保存" → 固化到 references/guide{n}.md → 下次执行自动加载
- **业务规则**：
  - **指南生成限制**：AI 输出禁止大段代码（代码块最多 10 行），面向用户以文字说明为主
  - **自动加载机制**：已保存的指南下次执行时自动注入到 prompt，无需重新探索
  - **存储位置**：references/guide{n}.md（与 run.md 同级），可跨 run 复用
  - **步骤编辑**：支持修改步骤输入（stepInput）但不支持修改描述（description），防止破坏历史记录
- **解决痛点**：长流程执行经验无法固化、每次执行都要重新探索、最佳实践难以传承

## 核心功能模块
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L45-50
- **协作星图**：多 Agent 协作网络，含声望系统、任务路由、远程协作
- **地球路径**：多步骤自动化，逐步骤执行，上一步结果作为下一步上下文
- **定时任务**：Cron 表达式驱动的自动化任务，支持手动触发与队列管理
- **知识管理**：自动提取业务/规范/技术知识，通过 git push 实现团队知识共享

## 侧边栏核心功能
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L30-36, src/components/layout/Sidebar.tsx
1. 新建会话 2. 新建组合 3. 协作星图 4. 组队 5. 定时任务 6. 地球路径 7. 设置 8. 代码查看器 9. Git 面板

## Chatbot 命令
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L180-195
`//cd` (切换目录), `//pwd` (显示当前目录), `//workspaces` (列出已打开项目), `//status` (连接状态), `//help`

## 核心体验原则
> by nasakim | 验证: 2026-05
