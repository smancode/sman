# 自进化 Skill 化方案建议

## 现状分析

### 当前架构的问题

| 层级 | 问题 |
|------|------|
| **存储层** | 学习记录（`learning_records`）存储在 H2 数据库，仅限于本项目使用 |
| **检索层** | 主要通过 `expert_consult` 语义搜索使用，无法被外部工具调用 |
| **复用层** | **完全缺失** - 没有 skill 化机制，学习成果无法跨项目/跨工具复用 |

### 核心矛盾

自进化产生的 **知识**（学习记录）vs **技能**（skill）：

```
当前：学习记录 → 向量搜索 → 仅限 expert_consult 使用
期望：学习记录 → skill 化 → 所有工具可调用
```

---

## 专业建议

### 方案：将学习成果 skill 化

将自进化的学习成果封装为可被其他工具调用的 skill 格式。

### 1. Skill 数据模型设计

```kotlin
// 位置: evolution/model/LearnedSkill.kt
data class LearnedSkill(
    val id: String,                    // skill ID
    val name: String,                  // skill 名称（如 "用户认证流程"）
    val description: String,            // skill 描述
    val triggerPattern: String,         // 触发模式（正则）
    val steps: List<SkillStep>,        // 执行步骤
    val examples: List<String>,         // 使用示例
    val confidence: Double,            // 置信度
    val tags: List<String>,             // 领域标签
    val createdAt: Long,               // 创建时间
    val sourceFiles: List<String>       // 来源文件
)

data class SkillStep(
    val order: Int,
    val action: String,                 // 工具调用
    val params: Map<String, String>,   // 参数
    val expectedResult: String         // 期望结果
)
```

### 2. Skill 注册与分发

```kotlin
// 位置: evolution/skill/SkillRegistry.kt
class SkillRegistry {
    // 本地注册
    fun register(skill: LearnedSkill)

    // 导出为 skill 文件（可被外部加载）
    fun exportToFile(path: Path)

    // 从文件加载
    fun loadFromFile(path: Path): List<LearnedSkill>
}
```

### 3. Skill 文件格式（输出到 `.sman/skills/`）

```yaml
# {project}/.sman/skills/user_auth_flow.yaml
id: skill_001
name: 用户认证流程
description: 标准的 JWT 用户认证流程，包含登录、验证、刷新
trigger_pattern: "用户.*认证|login|auth"
confidence: 0.95
tags: [认证, 安全, JWT]
steps:
  - order: 1
    action: grep_file
    params:
      pattern: "fun login|fun authenticate"
      scope: "**/*Controller*"
  - order: 2
    action: read_file
    params:
      path: "{{result.file}}"
  - order: 3
    action: call_chain
    params:
      method: "login"
examples:
  - "如何实现用户登录？"
  - "认证流程是怎么走的？"
```

### 4. 与现有系统的集成

| 模块 | 集成点 |
|------|--------|
| **LearningRecorder** | 学习成果同时写入 H2 + 导出 skill 文件 |
| **QuestionGenerator** | 可参考现有 skills 生成问题 |
| **Tool System** | 新增 `use_skill` 工具，直接调用 skill |
| **Expert Consult** | 搜索时优先匹配 skill |

### 5. Skill 使用场景

```
用户问题: "怎么做用户登录？"
    ↓
use_skill 工具
    ↓
匹配 trigger_pattern: "用户.*认证|login"
    ↓
执行 skill steps（自动调用工具链）
    ↓
返回完整答案
```

---

## 实施路径

| 阶段 | 任务 | 文件 |
|------|------|------|
| **Phase 1** | 定义 LearnedSkill 数据模型 | `evolution/model/LearnedSkill.kt` |
| **Phase 2** | 实现 SkillRegistry | `evolution/skill/SkillRegistry.kt` |
| **Phase 3** | 修改 LearningRecorder 同时输出 skill | `evolution/recorder/LearningRecorder.kt` |
| **Phase 4** | 新增 use_skill 工具 | `tools/ide/UseSkillTool.kt` |

---

## 预期收益

| 维度 | 收益 |
|------|------|
| **复用性** | 学习成果可被任何支持 skill 的工具调用 |
| **可移植性** | skill 文件可分享给其他项目 |
| **可解释性** | 明确的执行步骤，而非黑箱向量搜索 |
| **准确性** | 精确的工具调用链，而非模糊的语义召回 |
