---
name: skill-auto-updater
description: |
  每日自动检查并更新项目 skills。扫描项目变更，匹配最新能力。
  默认关闭，需要在 Sman 设置中启用。
---

# Skill Auto-Updater

仅在用户手动触发或 cron 启用时运行。默认不执行。

## 目标

保持项目的 `.claude/skills/` 与项目当前状态同步。当项目发生显著变化时，自动更新或补充匹配的 skills。

## 执行步骤

1. **读取基线** — 读取 `.sman/INIT.md` 获取上次扫描结果（项目类型、技术栈、已注入 skills）
2. **检测变更** — 对比项目当前状态与基线，判断是否有显著变化（如新增依赖、目录结构变化、技术栈变更等）
3. **更新 capability skills** — 如有显著变化，重新匹配能力，更新或补充 `.claude/skills/` 中的通用能力 skills
4. **记录结果** — 更新 `.sman/INIT.md` 时间戳和匹配结果，记录本次变更内容

## 边界条件

- 如果 `.sman/INIT.md` 不存在，说明项目未通过 Sman 新建会话初始化过，跳过本次执行
- 如果没有显著变化，跳过本次执行
- 不要删除用户手动添加的 skills，只更新和补充
- `project-structure`、`project-apis`、`project-external-calls` 是独立的元 skills，由各自的 cron 任务触发更新，不需要在这里处理
