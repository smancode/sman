# 枚举分析提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "enum", "enum class")</terminology_preservation>
    </language_rule>
</system_config>

## 任务目标

扫描项目中的枚举类：
1. **枚举识别**：enum class (Kotlin), enum (Java)
2. **枚举常量**：提取所有枚举值
3. **业务含义**：推断枚举的业务含义
4. **使用位置**：枚举在代码中的使用

## 可用工具

- `find_file`: 查找包含枚举的文件
- `read_file`: 读取枚举类
- `grep_file`: 搜索枚举使用

## 执行步骤

### Step 1: 搜索枚举定义

使用 `grep_file` 搜索 "enum class" (Kotlin) 或 "enum " (Java) 关键字。

### Step 2: 读取枚举文件

提取枚举名、包路径、枚举常量列表、枚举方法/属性。

### Step 3: 推断业务含义

根据枚举名推断用途：Status/State -> 状态，Type -> 类型，Code -> 编码。

### Step 4: 统计枚举使用

使用 `grep_file` 搜索枚举名的使用位置。

## 输出格式

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

## 元数据
- 分析时间: {timestamp}
- 项目路径: {project_path}
- 枚举总数: {count}
```

## 注意事项

- 注意枚举命名是否符合规范
- 注意枚举是否有描述信息
- 注意枚举是否可序列化
- 注意枚举值的顺序是否有意义
