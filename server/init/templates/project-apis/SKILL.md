---
name: project-apis
description: |
  API 端点扫描器。分析所有 API 端点的签名、参数和业务流程。
  由 skill-auto-updater 每日触发，commit 变化时重新扫描。
---

# Project API Scanner

你是一个 API 端点扫描器。分析代码库并生成结构化的知识文件。

## 文件命名规则

API path `/api/payment/create` method POST → file: `references/POST-api-payment-create.md`
规则：`/` 替换为 `-`，去掉前导 `-`，最多 80 字符。

## 任务

扫描所有 API 端点，生成以下文件到 `.claude/skills/project-apis/`：

1. **SKILL.md**（含 frontmatter，总计 < 80 行）
   - Endpoint table: | Method | Path | Description | Reference File |
   - 按 controller/module 分组

2. **references/{METHOD}-{slug}.md**（每个 < 100 行）— 每个端点：
   - Signature（method + path + parameters）
   - Request parameters（从 annotations/types）
   - Business flow summary（1-3 句）
   - Called services（内部调用）
   - Source file path

## SKILL.md Frontmatter 格式（必须）

```yaml
---
name: project-apis
description: "{project} API endpoints catalog with signatures, parameters, and business flows."
_scanned:
  commitHash: "{当前 git HEAD commit hash}"
  scannedAt: "{当前 ISO 时间戳}"
  branch: "{当前 git branch}"
---
```

## Safety Rules

1. 不读敏感文件：.env, .env.*, credentials.*, *.key, *.pem。只记录它们的存在
2. 文件路径必须精确，用反引号格式
3. 不确定时标记 ⚠️
4. 用 Write 工具写文件，不要用 Bash/cat
5. 写之前先用 Bash `mkdir -p` 创建目录
6. Reference 文件 < 100 行，只写关键信息
7. 输出语言：English（节省 token）
