# 复用指南

## 可复用资源
- `daily-code-stats.sh`: 统计指定用户当天 Git 提交量，按文件类型分类显示新增/删除行数
- `daily-code-stats-all-users.sh`: 统计所有用户当天 Git 提交量，按文件类型分类显示新增/删除行数

## 注意事项
- 脚本自动获取当天日期，无需修改即可每日使用
- 统计特定日期时修改 `TODAY` 变量（如 `TODAY="2026-05-07"`）
- `daily-code-stats.sh` 支持用户名参数：`./daily-code-stats.sh username`（默认使用当前 git 配置的用户名）
- 统计范围：当天 00:00:00 至 23:59:59 的所有提交

## 最佳实践
- 每天下班前运行，快速了解当天代码产出
- 配合 `git log --author="用户名" --since="today" --pretty=format:"%s"` 查看具体提交详情
- 统计结果可直接用于生成个人/团队日报
- 适用于量化代码贡献场景（绩效考核、项目汇报等）