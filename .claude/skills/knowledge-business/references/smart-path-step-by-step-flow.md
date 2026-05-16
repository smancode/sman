# 地球路径逐步执行工作流

> 验证时间: 2026-05-17 | 相关文件: server/smart-path-engine.ts, docs/superpowers/specs/2026-05-16-step-by-step-execution-design.md

## 用户工作流（Step-by-Step）

1. **创建路径**：用户定义多步骤工作流，每个步骤包含名称和用户输入指令
2. **点"逐步执行"**：
   - 调用 `smartpath.orchestrate` → 主编分析 → 返回蓝图（PathBlueprint）
   - 创建 run 记录（status: 'running'）
   - 清空 tmp/ 目录
3. **逐步骤循环执行**：
   - for step[0..N]:
     - 调用 `smartpath.runStep`（传入蓝图 + 步骤索引 + 前序结果）
     - 流式显示执行结果（`smartpath.stepExecutionProgress`）
     - 步骤完成后暂停
     - **用户可编辑**：
       - 结果文本（Textarea）
       - 步骤描述（Input）
     - **用户选择**：
       - 点"重新执行" → 重新调用 runStep
       - 点"继续" → 进入下一步
4. **生成报告**：
   - 所有步骤完成后调用 `smartpath.finalize`
   - 生成执行报告（reports/run-{timestamp}.md）
   - 更新 run.md（可复用资源指南 + 经验沉淀）
   - 状态：`completed`

## 业务规则

### 主编分析（Orchestration）
- **目标**：理解整个工作流的目标、每步的作用，产出执行方案
- **输出**：PathBlueprint
  ```typescript
  {
    goal: string;              // 全局目标
    stepPlans: [{
      revisedInput: string;    // 修正后的指令
      roleDescription: string; // 步骤作用
      expectedOutputs: string; // 应产出的关键信息
      dependenciesOnPrior: string; // 需要前序步骤的什么
    }];
    modifications: [{
      step: number;
      original: string;
      revised: string;
      reason: string;
    }];
  }
  ```
- **规则**：已清晰的步骤 revisedInput 可不变；第一步 dependenciesOnPrior 填"无"；modifications 只记录实际修正的

### 临时文件与可复用资源规范
- **tmp/ 目录**：
  - 存放临时输出（每次运行开始时自动清空）
  - 路径：`{workspace}/.sman/paths/{pathId}/tmp/`
  - 用途：中间产物、草稿文件
- **references/ 目录**：
  - 存放可复用资源（不清空）
  - 路径：`{workspace}/.sman/paths/{pathId}/references/`
  - 标记方法：用 `[REFERENCE:filename.ext]` 包裹内容
  - 用途：脚本、配置文件、文档模板等
- **run.md**：
  - 路径：`{workspace}/.sman/paths/{pathId}/references/run.md`
  - 内容：路径概述 → 执行策略 → 步骤修正记录 → 可复用资源清单（必须包含完整路径和用途）→ 注意事项 → 最佳实践

### 交付检查（Delivery Check）
- **触发条件**：步骤定义了 deliveryCheck 字段
- **检查流程**：
  - 执行完成后自动调用 LLM 核对
  - 检查 prompt：严格比对交付标准 vs 执行结果
  - 输出格式：`PASS` 或 `FAIL: {原因}`
- **失败处理**：
  - 自动重试 1 次
  - 重试 prompt 增加上次结果 + 失败原因
  - 重试后再检查一次

### 每步独立 Session
- **Session ID**：`smartpath-ephemeral-{runId}-step-{stepIndex}-{timestamp}`
- **生命周期**：步骤执行完立即销毁（closeV2Session + removeEphemeralSession）
- **隔离性**：每步互不影响，可独立重试

## 领域术语

| 术语 | 定义 |
|------|------|
| **PathBlueprint** | 主编分析产出的执行方案，含全局目标、每步计划、修正记录 |
| **StepPlan** | 单步执行计划，含 revisedInput、roleDescription、expectedOutputs、dependenciesOnPrior |
| **orchestrate** | 协调阶段，主编分析理解整个路径 |
| **runStep** | 单步执行，接收蓝图、步骤索引、前序结果，返回步骤结果 |
| **finalize** | 收尾阶段，生成报告 + 更新 run.md |
| **deliveryCheck** | 交付检查标准，用 LLM 核对执行结果是否满足要求 |
| **ephemeral session** | 临时会话，执行完立即销毁，不保留上下文 |

## 解决的痛点

1. **长流程执行中间出错需全部重跑**：逐步执行可从任意步骤重试
2. **无法人工干预和调整**：每步完成后可编辑结果和描述
3. **资源复用混乱**：tmp/ 和 references/ 明确分离，run.md 记录可复用资源路径
4. **执行策略不清晰**：主编分析先理解整体再逐步执行，避免"走一步看一步"
