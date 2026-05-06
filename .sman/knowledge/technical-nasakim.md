# Technical — nasakim

> Last extracted: 2026-05-06T10:22:53.100Z

## Smart Path path.md 文件格式
<!-- hash: 7a8b9c -->
- YAML front matter 字段：name、description、workspace、created_at、updated_at、status、cron_expression、steps
- body 区域为 markdown 内容（标题 + 描述文本）
- 后端存储：server/smart-path-store.ts；类型定义：src/types/settings.ts（SmartPath 含 description?）
- 前端组件 src/features/smart-paths/index.tsx 负责新建/编辑/详情页的 description 展示与编辑
<!-- end: 7a8b9c -->