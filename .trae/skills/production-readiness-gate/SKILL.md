---
name: "production-readiness-gate"
description: "Defines production readiness criteria and enforces a release gate. Invoke when user asks for production-grade quality, hardening, or pre-release validation."
---

# Production Readiness Gate

## Purpose

把“生产级”从口号变成可执行的验收门槛，用统一标准判断是否可发布。

## When to Invoke

- 用户要求“生产级”“更稳”“可上线”“别半截”
- 功能已基本可用，需要进入加固与验收阶段
- 修复线上高优先级问题后，需要回归与发布前检查
- 需要明确“完成定义”并避免主观验收

## Readiness Dimensions

1. Correctness
- 主流程、异常流程、边界输入可复现通过
- 关键状态转换可验证，无隐式完成路径

2. Reliability
- 有超时、重试、降级策略
- 异常不会把会话或任务状态置于不可恢复状态

3. Consistency
- 前后端完成信号一致
- UI 展示状态与后端实际状态不冲突

4. Observability
- 关键链路有可追踪事件与错误信息
- 失败时可定位在哪一层（输入、执行、渲染、持久化）

5. Maintainability
- 模块边界清晰，复杂逻辑可单测
- lint、typecheck、tests 全通过

## Execution Protocol

1. Compress Context
- 归纳问题现象、受影响模块、触发条件、当前保护措施

2. Deep Root-Cause
- 至少验证两个竞争性假设
- 找到“提前成功”“状态错位”“错误掩盖”的具体代码路径

3. Fix with Guardrails
- 优先修复状态机与收敛规则，再修 UI 文案
- 对中间态与最终态做显式判定，禁止模糊条件

4. Test by Scenario
- 为真实提示词和关键边界补单测
- 必须包含成功、失败、超时、乱序事件四类场景

5. Enforce Release Gate
- 执行并通过项目既有检查命令
- 任何一个门禁失败都不算“生产级”

## Chat-Orchestration Specific Gate

- 不展示半截回复：中间过程文本不可作为最终答复
- 完成判定收敛：完成信号 + 时间关系 + 稳定轮询共同满足
- 工具调用可见但不误终止：用户可见进度，不提前结束等待
- 失败可恢复：超时/轮询失败必须给出可重试结果
- 多轮会话隔离：上一轮状态不污染下一轮

## Mandatory Verification Checklist

- 目标提示词回归通过（至少 10 次重复）
- 乱序事件注入后仍正确收敛
- 超时与网络异常文案正确且可继续操作
- 单元测试新增并通过
- lint 通过
- typecheck/check 通过

## Output Template

- 现象：一句话描述用户可见问题
- 根因：列出具体代码路径与触发条件
- 修复：列出状态与判定逻辑变更
- 验证：列出执行命令与结果
- 风险：列出剩余风险与下一步加固项
