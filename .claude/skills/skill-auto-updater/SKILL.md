---
name: skill-auto-updater
description: |
  每日自动检查并更新项目 skills。扫描项目变更，匹配最新能力。
  默认关闭，需要在 Sman 设置中启用。
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
---

# Skill Auto-Updater

仅在用户手动触发或 cron 启用时运行。默认不执行。

## 执行步骤

1. 读取 .sman/INIT.md 获取上次扫描结果
2. 检查项目是否有显著变更（git log --since、文件结构变化）
3. 如有变更，重新执行轻量扫描并匹配能力
4. 对比现有 .claude/skills/ 与最新匹配结果
5. 如有新的匹配，复制新 SKILL.md 到 .claude/skills/
6. 更新 .sman/INIT.md 时间戳
7. 汇报更新结果
