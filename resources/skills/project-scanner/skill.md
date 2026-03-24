---
name: project-scanner
description: 扫描项目结构，生成项目概览报告
version: 1.0.0
triggers:
  - manual
tags:
  - analysis
  - onboarding
---

# Project Scanner

扫描当前项目结构，生成项目概览报告。

## 触发方式

用户说"扫描项目"、"分析项目结构"、"项目概览"时触发。

## 执行步骤

1. 列出项目根目录结构
2. 检查 package.json / pom.xml / go.mod 等项目配置文件
3. 识别技术栈和框架
4. 生成项目概览报告

## 输出格式

```markdown
# 项目概览

## 技术栈
- 语言: ...
- 框架: ...
- 构建工具: ...

## 目录结构
...

## 关键配置
...
```
