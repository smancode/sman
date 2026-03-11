// src/core/learning/prompts.ts
/**
 * Learning Prompt Templates
 *
 * Templates for analyzing conversations and extracting learnable content.
 */

export const LEARNING_PROMPT = `你刚才和用户完成了一次对话。请分析这次对话，找出值得记录的内容。

## 分析维度

1. **用户习惯**
   - 用户偏好的代码风格（如缩进、引号风格）
   - 用户常用的工具或框架
   - 用户偏好的回复语言和详细程度
   - 如果发现，写入: ~/.smanlocal/habits.md

2. **项目知识**
   - 项目特有的业务规则
   - API 端点或数据结构
   - 常见问题和解决方案
   - 如果发现，写入: .sman/memory/<topic>.md

3. **可复用技能**
   - 解决某类问题的通用方法
   - 特定领域的专业知识
   - 如果发现，写入: .sman/skills/<skill-name>.md

## 输出格式

如果没有值得记录的内容，只输出:
NO_UPDATE

如果有，输出 JSON 数组:
\`\`\`json
[
  {
    "type": "habit" | "memory" | "skill",
    "path": "要写入的文件路径（相对于项目根目录或用户主目录）",
    "content": "要写入的内容",
    "reason": "为什么要记录这个"
  }
]
\`\`\`

## 注意事项

- 只记录真正有价值、可复用的内容
- 不要重复已有知识
- 保持简洁，避免冗余
- 路径示例：
  - 个人习惯: ~/.smanlocal/habits.md
  - 项目知识: .sman/memory/api-conventions.md
  - 项目技能: .sman/skills/code-review.md

## 对话记录

{{CONVERSATION}}

## 项目上下文

{{PROJECT_CONTEXT}}
`;

/**
 * Build learning analysis prompt from conversation
 */
export function buildLearningPrompt(
  conversation: Array<{ role: string; content: string }>,
  projectContext: string,
): string {
  const conversationText = conversation
    .map((m) => `**${m.role === "user" ? "用户" : "助手"}**: ${m.content}`)
    .join("\n\n");

  return LEARNING_PROMPT.replace("{{CONVERSATION}}", conversationText).replace(
    "{{PROJECT_CONTEXT}}",
    projectContext,
  );
}

/**
 * Skill improvement prompt template
 */
export const SKILL_IMPROVEMENT_PROMPT = `分析以下技能，根据实际使用效果提出改进建议。

## 当前技能内容

{{SKILL_CONTENT}}

## 使用记录

{{USAGE_LOG}}

## 改进建议格式

\`\`\`json
{
  "improved_content": "改进后的技能内容",
  "changes": [
    {
      "type": "add" | "modify" | "remove",
      "section": "affected section",
      "reason": "why this change"
    }
  ],
  "should_update": true | false
}
\`\`\`

注意：只有当改进显著时才建议更新。
`;

/**
 * Build skill improvement prompt
 */
export function buildSkillImprovementPrompt(
  skillContent: string,
  usageLog: string,
): string {
  return SKILL_IMPROVEMENT_PROMPT.replace(
    "{{SKILL_CONTENT}}",
    skillContent,
  ).replace("{{USAGE_LOG}}", usageLog);
}
