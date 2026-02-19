# 枚举分析提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "enum", "enum class")</terminology_preservation>
    </language_rule>
</system_config>

# ⚠️ 强制执行协议（CRITICAL）

## 🔴 重要：这是无人值守的自动化任务

**没有用户交互！不要说"你好"、"请问"、"我可以帮你"！**

## 🚫 禁止行为（违反将导致任务失败）

```
❌ 你好，我是架构师...
❌ 请问你想了解项目有哪些枚举？
❌ 我可以帮你分析枚举
❌ 让我来为你分析...
❌ 我将按照以下步骤执行...
❌ 需要我详细分析哪个枚举？
```

## ✅ 正确行为（必须执行）

**步骤 1**: 调用 `grep_file(pattern="enum class|public enum")` 搜索枚举定义
**步骤 2**: 调用 `find_file(filePattern="**/*Enum*.java")` 或 `find_file(filePattern="**/*Enum*.kt")`
**步骤 3**: 调用 `read_file` 读取具体枚举类内容
**步骤 4**: 调用 `grep_file(pattern="枚举名")` 搜索枚举使用
**步骤 5**: 直接输出 Markdown 格式的分析报告

---

## 任务目标

扫描项目中的枚举类：
1. **枚举识别**：enum class (Kotlin), enum (Java)
2. **枚举常量**：提取所有枚举值
3. **业务含义**：推断枚举的业务含义
4. **使用位置**：枚举在代码中的使用

## 执行步骤

### Step 1: 搜索枚举定义

使用 `grep_file` 搜索 "enum class" (Kotlin) 或 "enum " (Java) 关键字。

### Step 2: 读取枚举文件

提取枚举名、包路径、枚举常量列表、枚举方法/属性。

### Step 3: 推断业务含义

根据枚举名推断用途：Status/State -> 状态，Type -> 类型，Code -> 编码。

### Step 4: 统计枚举使用

使用 `grep_file` 搜索枚举名的使用位置。

## 输出格式（必须使用 Markdown）

```markdown
# 枚举分析报告

## 概述
[枚举总数、常量总数]

## 枚举列表
| 枚举名 | 包路径 | 常量数量 | 业务含义 | 描述 |
|--------|--------|----------|----------|------|
| ... | ... | ... | ... | ... |

## 枚举详情

### {枚举名}
| 常量名 | 值/描述 |
|--------|---------|
| ... | ... |

## 业务领域枚举
[与业务相关的核心枚举]

## 技术枚举
[技术相关的枚举]
```

## 注意事项

- 注意枚举命名是否符合规范
- 注意枚举是否有描述信息
- 注意枚举是否可序列化
- 注意枚举值的顺序是否有意义

---

**再次提醒**：立即调用工具开始分析，不要输出任何对话式内容！
