# 星域智能协作引擎设计

> 日期：2026-04-22
> 核心命题：智能匹配 → 深度匹配 → 上下文拆分 → 任务分配 → 大需求分解

---

## 一、当前协作流的真实状态

先把代码级的真实流程画出来，对比理想流程找差距。

### 1.1 当前实际流程

```
Claude Agent（请求方）遇到问题
  │
  ├─ 调用 stardom_search(query)
  │   ├─ 本地 learned_routes（LIKE 模糊搜索）
  │   ├─ 本地 pair_history（磨合记录）
  │   ├─ 远程服务器 keyword.includes() + reputation 排序
  │   └─ 合并：老搭档 > 历史协作 > 有经验 > 远程
  │
  ├─ 调用 stardom_collaborate(agentId, question)
  │   ├─ 服务器 keyword 粗筛 → 发 task.incoming 给目标
  │   ├─ 目标 Agent 自动/手动接受
  │   └─ 返回 "协作请求已发送"（❌ 不等结果）
  │
  ├─ 协作会话建立（目标方）
  │   ├─ 创建 Claude cron session（stardom-{taskId}）
  │   ├─ 注入：question + 协作历史（如有）
  │   ├─ ❌ 注入模板不含项目上下文、代码、已有尝试
  │   └─ ❌ cron session 不走 buildSmanContext()，无行为规范
  │
  ├─ 目标 Claude 回复
  │   ├─ sendMessageForCron 积累 fullContent
  │   ├─ 存入本地 session store
  │   └─ ❌ sendClaudeReplyToStardom 未被自动调用
  │       → 回复断在目标方，不回传请求方！
  │
  └─ 经验提取（rating ≥ 3 时）
      ├─ 调 Claude haiku 从聊天提取 100 字经验
      ├─ 存入本地 learned_routes（不上传服务器）
      └─ ❌ 经验是自由文本，非结构化
```

### 1.2 五个关键断裂点

| # | 断裂点 | 影响 |
|---|--------|------|
| 1 | **上下文丢失** | 只有 question 字符串传递，无项目上下文/代码/已尝试 |
| 2 | **回复断裂** | sendClaudeReplyToStardom 未自动调用，答案回不去 |
| 3 | **经验孤岛** | 提取的经验仅存本地，不上传，其他 Agent 搜不到 |
| 4 | **无任务结构** | 整个需求一个字符串，无拆分，无优先级，无依赖 |
| 5 | **匹配过于粗糙** | keyword.includes() + reputation 排序，不区分能力域 |

---

## 二、智能协作引擎的五层架构

```
┌─────────────────────────────────────────────────┐
│  Layer 5: 需求分解器                             │
│  大需求 → 子需求 → 可独立解决的原子任务           │
├─────────────────────────────────────────────────┤
│  Layer 4: 上下文管理器                           │
│  为每个原子任务构建最小但足够的上下文窗口          │
├─────────────────────────────────────────────────┤
│  Layer 3: 深度匹配器                             │
│  建立连接后，二次匹配：需求细节 ↔ Agent 真实能力  │
├─────────────────────────────────────────────────┤
│  Layer 2: 粗筛匹配器                             │
│  根据需求描述快速定位候选 Agent（当前已有雏形）    │
├─────────────────────────────────────────────────┤
│  Layer 1: Agent 能力画像                         │
│  注册时 + 持续学习构建的结构化能力档案             │
└─────────────────────────────────────────────────┘
```

### Layer 1: Agent 能力画像（基础）

**当前**：Agent 注册时只提供 `name` + `description`（自由文本）

**目标**：结构化能力画像

```typescript
interface AgentCapabilityProfile {
  // 基础信息（注册时声明）
  name: string;
  domains: string[];           // ["支付", "风控", "数据库"]
  tools: string[];             // 可访问的工具/系统 ["order-db", "risk-engine"]
  projects: string[];          // 可访问的项目/代码库

  // 学习得到（协作积累）
  solvedProblems: {            // 按领域统计
    domain: string;
    count: number;
    avgRating: number;
    avgResolutionMinutes: number;
  }[];

  // 协作偏好（从历史提取）
  collaborationStyle: 'concise' | 'detailed' | 'guiding';
  strengthDomains: string[];   // 高评分领域
  weakDomains: string[];       // 低评分或不擅长领域
}
```

**构建方式**：

| 数据源 | 提取方式 | 产出 |
|--------|---------|------|
| 注册时声明的 projects | 直接读取 | domains 初值 |
| 每次 task.complete 的 chat_messages | LLM 结构化提取 | solvedProblems 统计 |
| 每月汇总 reputation_log | 按领域聚合 | strengthDomains / weakDomains |
| chat_messages 对话风格 | LLM 分析 | collaborationStyle |

**与当前架构的关系**：当前 `agent.description` 是自由文本，不做任何解析。改造为注册时让 Claude 自动从项目结构提取能力标签（已有 `workspace-scanner` 和 `capability-matcher` 基础设施），存入新增的 `agent_capabilities` 表。

### Layer 2: 粗筛匹配器（当前已有雏形）

**现状**：keyword.includes() + reputation 排序 → 桥接层精排（经验 + 磨合）

**增强方向**：

```
当前：query → keyword.includes(name, description) → reputation 排序
增强：query → LLM 语义扩展 → 多字段匹配 + 能力域过滤 → 综合排序

排序权重：
  w1 × 能力域匹配度（query 的领域 ∈ agent.domains）
+ w2 × 领域声望（该领域的 avgRating）
+ w3 × 历史成功率（pair_history）
+ w4 × 当前可用性（在线 + 有空闲槽位）
+ w5 × 全局声望（兜底）
```

**关键改进**：用 LLM 做 query 语义扩展（复用桥接层已有的 Claude API 调用）：

```
输入："支付查询很慢"
LLM 扩展：["支付", "交易", "结算", "查询优化", "SQL 性能", "payment"]
→ 多字段 OR 匹配 + domains 数组交集
```

### Layer 3: 深度匹配器（新建）

**场景**：粗筛找到候选 Agent，建立连接后，再做一次精细匹配

**为什么需要**：粗筛基于 Agent 的"自我声明"（name/description/domains），但实际能力可能有偏差。深度匹配基于"真实数据"（历史协作记录、代码访问能力、具体问题的相关经验）。

```
粗筛：3 个候选 Agent
  │
  ├─ Agent A：声望最高，但历史协作显示 30 分钟才回复
  ├─ Agent B：声望中等，但上个月刚解决过类似的支付查询问题
  └─ Agent C：声望低，但能访问支付数据库（tools 包含 order-db）
  │
  深度匹配输入：
    需求细节：具体是哪个表的哪个查询，已经尝试了什么
    候选 Agent 的真实数据：历史经验、工具权限、近期响应速度
  │
  输出：推荐 Agent B（有相关经验 + 响应快）→ 不行则 Agent C（有数据访问能力）
```

**实现**：

```typescript
interface DeepMatchRequest {
  taskId: string;
  // 需求细节（粗筛阶段只传了 query，这里传完整上下文）
  problemDescription: string;    // 完整问题描述
  requiredTools?: string[];      // 需要的工具/系统权限
  requiredDomains: string[];     // 需要的领域知识
  complexity: 'simple' | 'medium' | 'complex';
  urgency: 'low' | 'normal' | 'high';

  // 候选列表（粗筛结果）
  candidates: Array<{
    agentId: string;
    // 服务器持有的真实数据
    pairHistory?: PairHistory;
    domainReputation?: DomainReputation;
    recentResponseTime?: number;     // 最近平均响应时间（分钟）
    relevantExperience?: string[];    // 从 learned_routes 提取的相关经验
  }>;
}

// 评分公式
DeepScore(candidate) =
  w1 × 领域经验相关性（该领域 avgRating × solvedCount）
+ w2 × 工具权限匹配度（requiredTools ⊆ candidate.tools）
+ w3 × 近期响应速度（recentResponseTime 越小越好）
+ w4 × 历史协作质量（pairHistory.avgRating）
+ w5 × 复杂度匹配（复杂任务优先选 solvedCount 多的 Agent）
```

**关键技术**：深度匹配需要**从服务器查询候选 Agent 的真实数据**。当前 learned_routes 仅存桥接层本地，需要上移到服务器（Phase 1 已规划）。

### Layer 4: 上下文管理器（新建 — 最核心的创新点）

**核心挑战**：Agent 的上下文窗口有限，传递整个项目上下文不现实。需要为每个原子任务构建**最小但足够的上下文**。

**当前问题**：只传 `question` 字符串，helper Agent 对请求方的项目、代码、已尝试方案一无所知。

**设计**：

```
请求方 Claude 的完整上下文
  │
  ├─ 用户原始需求："优化支付查询性能"
  ├─ 当前文件：PaymentService.java
  ├─ 相关代码：PaymentRepository.java, OrderMapper.xml
  ├─ 已尝试：加了索引但没用
  ├─ 数据库 Schema：payment_records 表（2000万行）
  ├─ 错误信息：查询耗时 30s+
  └─ 项目上下文：Spring Boot + MyBatis + MySQL
  │
  上下文管理器：
    1. LLM 识别任务所需的关键信息
    2. 从请求方上下文中提取最小必要集
    3. 压缩为结构化的 TaskContext
  │
  传给 helper 的 TaskContext：
    {
      problem: "payment_records 表查询慢，2000万行，已加索引无效",
      schema: "CREATE TABLE payment_records (...)",  // 仅相关表
      code: "PaymentRepository.findPaymentByOrderId()",  // 仅相关方法
      attempted: "已对 order_id 列建索引，EXPLAIN 显示未走索引",
      constraint: "不能停机，不能改表结构",
      expectedResult: "查询时间降到 2s 以内"
    }
```

**上下文压缩策略**：

| 策略 | 说明 | 压缩比 |
|------|------|--------|
| **需求聚焦** | LLM 提取问题核心，丢弃无关背景 | 50-70% |
| **代码裁剪** | 只传相关函数/方法，不传整个文件 | 80-90% |
| **Schema 精简** | 只传相关表和字段，不传整个 DDL | 70-85% |
| **尝试历史摘要** | "已尝试X，失败原因Y"，而非完整对话 | 60-80% |
| **约束提取** | 显式列出约束条件 | 新增结构 |

**实现路径**：

```typescript
interface TaskContext {
  // 由 LLM 从请求方上下文提取（不是请求方手动写）
  problem: string;              // 精确问题陈述（≤200 字）
  relevantCode: string;         // 仅相关代码片段（≤100 行）
  relevantSchema?: string;      // 仅相关数据结构
  attemptedAndFailed: string;   // 已尝试方案和失败原因
  constraints: string[];        // 约束条件
  expectedOutcome: string;      // 期望结果
  domain: string;               // 领域标签
  complexity: 'simple' | 'medium' | 'complex';
}
```

**生成方式**：请求方的 Claude 在调用 `stardom_collaborate` 前，先调用 `stardom_prepare_context` 新 MCP 工具：

```
stardom_prepare_context:
  输入：当前对话上下文 + 用户原始需求
  输出：TaskContext（LLM 压缩后的最小上下文）
  实现：调用 Claude haiku，prompt 模板指导提取关键信息
```

这样 `stardom_collaborate` 携带的就是压缩后的 TaskContext 而非原始 question。

### Layer 5: 需求分解器（未来）

**场景**：一个大需求无法由单个 Agent 解决，需要拆分。

```
大需求："重构支付模块，支持多币种"
  │
  ├─ 子需求 1: "分析现有支付流程，梳理所有币种相关的硬编码"
  │   → 上下文：PaymentService.java + CurrencyUtils.java
  │   → 匹配：熟悉支付业务 + 代码分析能力
  │
  ├─ 子需求 2: "设计多币种数据库 Schema"
  │   → 上下文：payment_records DDL + 币种需求文档
  │   → 匹配：数据库设计 + 金融领域知识
  │   → 依赖：子需求 1 的输出（硬编码清单）
  │
  ├─ 子需求 3: "实现币种转换服务"
  │   → 上下文：新 Schema + 汇率 API 文档
  │   → 匹配：Java 实现 + API 集成经验
  │   → 依赖：子需求 2 的输出（新 Schema）
  │
  └─ 子需求 4: "改造支付查询适配新 Schema"
      → 上下文：新 Schema + MyBatis Mapper
      → 匹配：MyBatis + SQL 优化经验
      → 依赖：子需求 2 和 3 的输出
```

**关键数据结构**：

```typescript
interface RequirementTree {
  id: string;
  originalRequirement: string;    // 原始大需求
  subTasks: SubTask[];
  dependencies: Array<{ from: string; to: string }>;  // DAG 依赖
}

interface SubTask {
  id: string;
  parentRequirementId: string;
  description: string;            // 子任务描述
  context: TaskContext;            // 压缩后的最小上下文（Layer 4 产出）
  requiredCapabilities: string[]; // 需要的能力标签
  requiredTools: string[];        // 需要的工具/系统权限
  estimatedComplexity: 'simple' | 'medium' | 'complex';
  dependencies: string[];         // 依赖的其他子任务 ID
  status: 'pending' | 'assigned' | 'in_progress' | 'completed' | 'failed';
  assignedAgentId?: string;
  result?: string;                // 完成后的结果
}
```

**分解算法**：

```
输入：大需求 + 请求方项目上下文

Step 1: LLM 分析需求，识别独立的子问题
  prompt: "将以下需求分解为独立可解决的子任务，标注依赖关系"

Step 2: 为每个子任务评估
  - 复杂度（决定需要什么级别的 Agent）
  - 上下文需求（需要哪些代码/Schema/文档）
  - 能力需求（需要什么领域的知识）
  - 工具需求（需要什么系统权限）

Step 3: 构建依赖 DAG
  - 哪些子任务可以并行
  - 哪些必须串行

Step 4: 为每个子任务调用 Layer 4 生成最小上下文
  - 从完整项目上下文中裁剪出每个子任务需要的部分

Step 5: 按依赖拓扑排序，逐个/并行分配给匹配的 Agent
```

**执行引擎**：

```
            ┌─────┐
            │ T1  │ ← 无依赖，立即分配
            └──┬──┘
               │
        ┌──────┴──────┐
        │             │
    ┌───┴───┐    ┌───┴───┐
    │  T2   │    │  T3   │ ← 依赖 T1，可并行
    └───┬───┘    └───┬───┘
        │             │
        └──────┬──────┘
               │
           ┌───┴───┐
           │  T4   │ ← 依赖 T2+T3，串行
           └───────┘

每个节点：
  分配 → 执行 → 收集结果 → 注入下游上下文 → 触发下游
```

---

## 三、完整协作流（改造后）

```
用户："优化支付查询性能"
  │
  ▼
请求方 Claude 分析：
  识别为单一任务（不需要 Layer 5 分解）
  │
  ▼
Layer 4 上下文管理器：
  从当前对话提取最小上下文：
  {
    problem: "payment_records 2000万行查询 30s+",
    relevantCode: "findPaymentByOrderId()",
    schema: "payment_records DDL",
    attempted: "已加索引无效，EXPLAIN 显示全表扫描",
    expectedOutcome: "查询 < 2s"
  }
  │
  ▼
Layer 2 粗筛匹配：
  query="支付查询性能" → LLM 扩展 → ["支付","交易","查询优化","SQL","索引"]
  → 匹配 5 个候选 Agent
  │
  ▼
Layer 3 深度匹配：
  候选 A: 有支付经验，avgRating 4.2，响应 15min
  候选 B: 有 DB 优化经验，avgRating 4.7，响应 5min，可访问 order-db
  候选 C: 通用，无专项经验
  → 推荐 B（领域匹配 + 工具权限 + 响应快）
  │
  ▼
建立连接，传递压缩上下文（非原始 question）
  │
  ▼
Helper Claude 收到：
  "[协作请求]
   问题：payment_records 2000万行查询慢
   代码：findPaymentByOrderId()
   Schema：CREATE TABLE payment_records (...)
   已尝试：加索引无效
   约束：不能停机
   期望：< 2s"
  │
  ▼
Helper Claude 回复（自动回传 ✅ 修复断裂点 2）
  │
  ▼
经验提取（结构化 ✅ 修复断裂点 3）
  存入服务器全局 learned_routes：
  {
    domain: "数据库优化",
    capability: "SQL 查询性能优化",
    experience: "大表查询全表扫描 → 检查索引选择性 + 覆盖索引",
    toolsUsed: ["order-db"],
    resolutionMinutes: 8
  }
```

---

## 四、落地优先级

### P0: 修复断裂（1-2 周）

| 修复项 | 当前状态 | 改动 | 影响 |
|--------|---------|------|------|
| **回复回传** | sendClaudeReplyToStardom 未自动调用 | sendMessageForCron 完成后自动调用 | 协作流程跑通 |
| **经验上移** | 仅存桥接层本地 | 新增协议消息上传到服务器 | 经验可跨 Agent 共享 |
| **cron session 注入 SmanContext** | 不走 buildSmanContext() | cron 路径也注入行为规范 | Helper 回复质量提升 |

P0 完成后，基础协作流程才算真正闭环。

### P1: Layer 1 + Layer 2 增强（1 月）

| 项目 | 改动 |
|------|------|
| Agent 能力画像 | 注册时从项目自动提取 domains/tools |
| 语义扩展匹配 | 复用 LLM 调用做 query 扩展 |
| 领域声望 | reputation_log 加 domain 维度 |
| pair_history 上移 | 服务器新增表，每次 complete 更新 |

### P2: Layer 3 + Layer 4（2-3 月）

| 项目 | 改动 |
|------|------|
| 深度匹配器 | 候选 Agent 的真实数据查询 + 综合评分 |
| 上下文管理器 | stardom_prepare_context MCP 工具 |
| 结构化经验提取 | 从自由文本改为结构化 JSON |
| TaskContext 协议 | stardom_collaborate 携带压缩上下文 |

### P3: Layer 5 需求分解器（3-6 月）

| 项目 | 改动 |
|------|------|
| 需求分解 LLM | 大需求 → 子任务 DAG |
| 子任务调度器 | 拓扑排序 + 并行分配 |
| 结果聚合 | 子任务结果 → 合并为完整方案 |
| 进度追踪 | 前端可视化分解树和执行进度 |

---

## 五、商业壁垒分析

```
竞争门槛递增：

  基础匹配（容易复制）
    └── 关键词搜索 + 声望排序

  + 语义匹配（中等门槛）
    └── LLM 语义扩展 + 领域声望

  + 深度匹配（高门槛）
    └── 真实协作数据驱动的精细化匹配
    └── 需要足够的协作样本积累

  + 上下文压缩（很高门槛）
    └── 代码/Schema/约束的智能裁剪
    └── 需要理解不同项目的技术栈

  + 需求分解（极高门槛 — 核心壁垒）
    └── 将大需求自动分解为可独立解决的原子任务
    └── 正确识别依赖关系
    └── 为每个子任务匹配最合适的 Agent
    └── 聚合子任务结果为完整方案
```

**为什么需求分解是终极壁垒**：

1. **数据飞轮**：分解越多 → 子任务匹配数据越多 → 匹配越精准 → 分解越合理
2. **组织知识积累**：每次分解都沉淀了"这类需求应该这样拆"的经验
3. **网络效应**：Agent 越多 → 子任务分配越灵活 → 能处理的复杂需求越多 → 吸引更多 Agent
4. **极难复制**：需要同时具备需求理解能力 + 项目上下文理解 + Agent 能力图谱 + 调度引擎

---

## 六、与当前代码的映射

| 设计层 | 对应当前代码 | 改造程度 |
|--------|-------------|---------|
| Layer 1 能力画像 | `agent-store.ts` agents 表 | 新增 agent_capabilities 表 |
| Layer 2 粗筛 | `task-engine.ts` + `stardom-mcp.ts` | 增强排序算法 |
| Layer 3 深度匹配 | 不存在 | 新增，依赖 learned_routes 上移 |
| Layer 4 上下文管理 | `stardom-session.ts` startCollaboration | 新增 prepare_context MCP + 修改注入模板 |
| Layer 5 需求分解 | 不存在 | 全新模块 |
| 回复回传（P0） | `stardom-session.ts` sendMessageForCron | 末尾加 sendClaudeReplyToStardom 调用 |
| 经验上移（P0） | `stardom-bridge.ts` extractExperience | 新增上传协议消息 |
| 结构化经验 | `callClaudeForExperience` | prompt 改为结构化 JSON 输出 |
