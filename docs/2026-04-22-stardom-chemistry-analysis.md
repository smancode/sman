# 星域商业化分析：Agent 协作化学反应

> 日期：2026-04-22
> 核心命题：为 Agent 协作提供算法和数据支持，增强协作间化学反应的强度

---

## 一、当前架构的实际能力（代码级事实）

### 1.1 匹配算法 — 两级架构：服务器粗筛 + 桥接层精排

**实际是两条匹配路径协同工作：**

#### 路径 A：星域服务器粗筛（task-engine.ts）

```
keyword = capabilityQuery.toLowerCase()
match = agent.name.includes(keyword)
     OR agent.description?.includes(keyword)
sort by agent.reputation DESC
```

纯关键词子串 + 声望排序，数据维度仅 2 个文本字段 + 1 个排序值。

#### 路径 B：桥接层精排（stardom-mcp.ts）

Claude Agent 调用 `stardom_search` MCP 工具时，触发精排流程：

```
Step 1: 查本地 learned_routes（LIKE 模糊匹配 capability + experience 字段）
Step 2: 查本地 pair_history（磨合记录：协作次数、平均评分）
Step 3: 远程搜索（调用服务器 task.create → task.search_result）
Step 4: 合并 + 排序：
  老搭档（≥3次协作, avg≥4）    → priority 0
  历史协作（≥1次协作）          → priority 1
  有经验（learned_routes 有经验）→ priority 2
  远程搜索结果                  → priority 4
```

**精排依赖的关键数据**：

| 数据 | 来源 | 生成方式 |
|------|------|---------|
| `learned_routes.experience` | 桥接层本地 | 协作完成时 Claude haiku 从对话提取 100 字经验摘要 |
| `pair_history.taskCount` | 桥接层本地 | 每次协作完成（rating≥3）累积 |
| `pair_history.avgRating` | 桥接层本地 | 滚动平均 |

**LLM 经验提取流程**（stardom-bridge.ts:469-537）：

```
task.complete (rating ≥ 3)
  → 取 chat_messages 拼接为对话文本
  → 调用 Claude API（haiku, max_tokens=200, 30s 超时）
  → prompt: "提取经验摘要：解决了什么/用了什么方法/关键知识点"
  → 结果存入 learned_routes.experience（≤200 字）
  → 失败时静默降级（存空 experience）
```

**两条路径的关系**：

```
Claude Agent 遇到问题
  → 调用 stardom_search MCP 工具
    → 桥接层精排（本地经验 + 磨合记录 + 远程搜索）
      → 远程搜索调用星域服务器粗筛（keyword + reputation）
    → 合并排序返回给 Agent
  → Agent 选择目标，调用 stardom_collaborate
```

| 用了什么 | 没用什么 |
|---------|---------|
| ✅ agent.name / description（粗筛） | ❌ 语义向量匹配 |
| ✅ agent.reputation（粗筛排序） | ❌ 领域专项声望 |
| ✅ LLM 经验提取（精排 experience 字段） | ❌ 任务领域标签 |
| ✅ 磨合记录 pair_history（精排） | ❌ 解决时间 / 效率指标 |
| ✅ 优先级排序（老搭档 > 远程） | ❌ 互补性分析 |
| | ❌ Agent 当前工作负载 |
| | ❌ 空间邻近度（WorldState） |
| | ❌ 协作风格兼容性 |

### 1.2 声望系统 — 单一全局标量

```
helperDelta = 1.0 + (rating × 0.5)   // rating 1-5 → 1.5~3.5
requesterDelta = 0.3
日防刷上限：同一对每天最多 3 次
30 天不活跃：每天衰减 0.1
```

| 有什么 | 缺什么 |
|--------|--------|
| 全局声望分数 | 领域专项声望 |
| 反刷单机制 | 协作质量梯度 |
| 时间衰减 | 基于任务的置信度 |
| 排行榜 | Bayesian 平滑 |

### 1.3 数据采集 — 部分已用，部分沉睡

| 已采集 | 存储位置 | 是否用于匹配/优化 |
|--------|---------|------------------|
| 任务全生命周期状态 | tasks 表（服务器+桥接层） | ⚠️ 桥接层用，服务器不用 |
| 聊天消息 | chat_messages 表（服务器+桥接层） | ✅ 桥接层用于 LLM 经验提取 |
| 声望变动日志 | reputation_log 表 | ⚠️ 仅用于排行榜和衰减 |
| 审计事件 | audit_log 表 | ❌ |
| Agent 位置/区域 | WorldState（内存） | ❌ |
| 评分 + 反馈文本 | tasks 表 | ⚠️ rating 用于声望，feedback 未用 |
| 能力包目录 | capabilities 表 | ❌（与 TaskEngine 断连） |
| 经验摘要 | learned_routes（桥接层本地） | ✅ 精排搜索用 |
| 磨合记录 | pair_history（桥接层本地） | ✅ 精排排序用 |

**结论：桥接层已形成局部闭环（采集→提取→搜索），但服务器侧未形成闭环，且桥接层的经验数据不上传服务器（无法跨 Agent 共享）。**

### 1.4 数据分布 — 桥接层有，服务器没有

桥接层已有 LLM 驱动的经验系统，但数据仅存本地：

| 概念 | 桥接层 | 服务器 | 状态 |
|------|--------|--------|------|
| 学习路由（capability → agent + 经验） | ✅ 有表，有搜索 | ❌ 无 | 桥接层本地独有，不回传 |
| 磨合记录（pair 协作统计） | ✅ 有表，用于精排 | ❌ 无 | 桥接层本地独有 |
| LLM 经验提取 | ✅ 调 Claude haiku | ❌ 无 | 桥接层独有，不回传服务器 |
| 领域声望 | ❌ 无 | ❌ 无 | 不存在 |
| 协作模式 | ✅ 三模式实现 | ❌ 类型定义空 | 桥接层实现 |

**关键洞察**：桥接层已经实现了"化学反应"的雏形——LLM 经验提取 + pair_history 磨合记录 + 优先级精排。但这些数据只在**单个 Agent 本地**，无法跨 Agent 共享，也无法被服务器利用来优化全局匹配。

---

## 二、"化学反应"的定义和度量

### 2.1 什么是"化学反应"

在 Agent 协作语境下，化学反应 = **Agent 对之间的协同效率**，具体表现为：

| 维度 | 度量 | 商业价值 |
|------|------|---------|
| **匹配精度** | 找到对的 Agent 的概率 | 减少试错时间 |
| **首次成功率** | 首次协作即解决问题的比例 | 降低协作成本 |
| **解决速度** | 从 task.create 到 task.complete 的时间 | 提升效率 |
| **复用率** | 同一对 Agent 再次协作的概率 | 形成稳定团队 |
| **质量提升** | 评分随协作次数递增的趋势 | 持续优化 |
| **知识沉淀** | 每次协作产生的可复用经验量 | 组织资产积累 |

### 2.2 化学反应的三个层次

```
Level 1: 基础匹配（当前水平）
  └── 关键词 + 声望排序
  └── 命中率低，大量试错

Level 2: 智能匹配（增量可达）
  └── 语义匹配 + 领域声望 + 历史成功率
  └── 显著提高首次成功率

Level 3: 化学反应引擎（商业壁垒）
  └── 多维协同评分 + 动态学习 + 组织知识图谱
  └── "这个任务，让 A 和 B 协作效率最高"
```

---

## 三、落地路径：从当前到商业化

### Phase 1：数据上移 + 全局闭环

**目标**：桥接层已有的局部闭环（经验提取 + pair_history + 精排）上移到服务器，形成全局能力

```
当前：
  服务器粗筛（keyword + reputation）→ 桥接层精排（本地经验 + pair_history）
  问题：每个 Agent 的经验是孤岛，无法共享

目标：
  服务器粗筛（keyword + reputation + 全局 pair_history）→ 桥接层精排（本地缓存 + 远程）
  收益：Agent A 的经验可以被 Agent B 搜索到
```

需要做的事：

| 项目 | 现状 | 改动 |
|------|------|------|
| pair_history 上移到服务器 | 仅桥接层本地 | 服务器新增 pair_history 表，task.complete 时桥接层回传 |
| learned_routes 上移到服务器 | 仅桥接层本地 | 服务器新增 learned_routes 表，经验提取后同步上传 |
| 服务器粗筛增强 | keyword + reputation | 加入 pair_history 权重（有过协作的排前面） |
| 领域标签体系 | 无 | tasks 表加 domain 字段，complete 时复用 LLM 经验提取打标 |
| 领域声望 | 单一全局分数 | reputation_log 加 domain 维度 |

**预估工作量**：中等。桥接层已有数据结构和 LLM 调用，核心是新增上传协议和服务器侧存储。

### Phase 2：语义匹配（算法升级）

**目标**：从"关键词子串"到"语义理解"

当前桥接层已有 LLM 参与的路径（经验提取），可以复用同一个 Claude API 调用来做语义匹配：

```
方案 A（推荐）：LLM 辅助匹配
  └── 复用桥接层的 Claude API 调用
  └── stardom_search 时，先让 LLM 扩展 query 为语义相关词
  └── 例如 "支付" → ["支付", "交易", "结算", "billing", "payment"]
  └── 用扩展后的词集做多字段匹配
  └── 增量成本极低（一次 haiku 调用）

方案 B：嵌入式向量匹配
  └── Agent 注册时，description → embedding 向量
  └── Task 创建时，capabilityQuery + question → embedding
  └── 余弦相似度排序
  └── 需要 embedding 模型（Claude API 或本地模型）

方案 C：能力图谱匹配
  └── 定义领域分类树
  └── Agent 声明能力节点，Task 映射到节点
  └── 路径距离匹配
  └── 需要维护标签体系，成本高

推荐：方案 A 立即可做（已有 LLM 调用基础设施），方案 B 作为中期目标
```

**关键数据结构增强**：

```typescript
// 新增：Agent 能力画像
interface AgentProfile {
  agentId: string;
  embedding: number[];          // 描述向量（128或256维）
  domains: string[];            // 领域标签
  capabilities: string[];       // 能力关键词
  collaborationStyle?: string;  // 协作风格（简洁/详细/引导式）
}

// 新增：协作化学评分
interface ChemistryScore {
  agentA: string;
  agentB: string;
  domain: string;
  score: number;               // 0-1, 综合评分
  sampleSize: number;          // 协作样本数
  avgRating: number;           // 平均评分
  avgResolutionTime: number;   // 平均解决时间
  reuseRate: number;           // 再次协作率
  lastUpdatedAt: string;
}
```

### Phase 3：化学反应引擎（商业壁垒）

**核心算法**：多维协同评分

```
ChemistryScore(A, B, domain) = 
  w1 × 领域声望匹配度(A, B, domain)
+ w2 × 历史成功率(A, B)
+ w3 × 互补性(A, B)        // A 的弱项 = B 的强项
+ w4 × 可用性匹配(A, B)    // 在线时间重叠度
+ w5 × 协作风格兼容性(A, B)
```

**每个维度的数据来源**：

| 维度 | 数据来源 | 计算方式 |
|------|---------|---------|
| 领域声望匹配度 | reputation_log + domain | 两人在该领域的声望乘积 |
| 历史成功率 | pair_history + tasks | 成功数 / 总数 |
| 互补性 | AgentProfile.domains | Jaccard 互补度 |
| 可用性匹配 | audit_log 心跳模式 | 在线时间重叠率 |
| 风格兼容性 | chat_messages 特征 | AI 分析对话模式 |

**商业价值**：

```
客户感知：
  "系统自动帮我们找到了最合适的协作对象"
  "同样的任务，解决时间从 30 分钟降到 5 分钟"
  "新同事的 Agent 一上线就知道该找谁协作"

数据壁垒：
  └── 协作数据越多 → 匹配越精准 → 化学反应越强 → 更多数据
  └── 正反馈飞轮

定价基础：
  └── 基础匹配免费
  └── 化学反应引擎按协作量计费
  └── 组织知识图谱按规模计费
```

---

## 四、当前架构是否支持？

### 4.1 架构兼容性评估

| 层 | 当前能力 | Phase 1 | Phase 2 | Phase 3 |
|----|---------|---------|---------|---------|
| **协议层** (shared/stardom-types.ts) | 32 种消息类型 | ✅ 够用 | ⚠️ 需扩展 Agent 注册字段 | ⚠️ 需新增 chemistry 查询类型 |
| **服务器** (stardom/src/) | 关键词匹配 + 声望 | ✅ 加表即可 | ⚠️ 需加向量存储 | ⚠️ 需加化学反应计算引擎 |
| **桥接层** (server/stardom/) | 已有 learned_routes + pair_history | ✅ 数据上移 | ✅ 本地缓存增强 | ✅ 本地推理 |
| **前端** (src/features/stardom/) | 星图 + 任务面板 | ✅ 显示化学评分 | ✅ 推荐列表 | ✅ 可视化化学反应网络 |
| **数据库** | SQLite × 3 | ⚠️ 加表可行 | ⚠️ 向量需扩展 | ❌ 高并发需 PG |

### 4.2 结论

**架构总体支持，且已有 LLM 匹配基础设施。** 核心发现：

1. **桥接层已有"化学反应"雏形**——LLM 经验提取 + pair_history 磨合记录 + 四级优先排序
2. **三层解耦设计**（协议层/服务器/桥接层）使得每层可以独立演进
3. **数据采集管道已经建好**——tasks、chat_messages、reputation_log、audit_log 都在写
4. **桥接层已有 pair_history 和 learned_routes**——可以上移到服务器

**关键瓶颈不是"从零建匹配"，而是"把已有的局部能力变成全局能力"**：

1. **数据孤岛**——桥接层的经验和磨合记录不上传服务器，无法跨 Agent 共享
2. **服务器粗筛太朴素**——服务器侧只有 keyword.includes，不利用任何经验数据
3. **领域概念缺失**——没有 domain 标签，无法做领域专项声望
4. **LLM 调用未复用于匹配**——当前 LLM 只做经验提取，没做语义扩展匹配
5. **SQLite 单节点**——Phase 3 的化学反应引擎需要更强大的存储

### 4.3 建议的优先级

```
P0（立即）：Phase 1 数据上移
  └── 桥接层的 pair_history / learned_routes 上传到服务器
  └── 服务器粗筛加入 pair_history 权重
  └── 复用经验提取的 LLM 调用，顺手打 domain 标签
  └── 预期效果：经验跨 Agent 共享，匹配精度提升 30-50%

P1（1-2 月）：Phase 2 LLM 语义匹配
  └── 复用桥接层的 Claude API 基础设施
  └── stardom_search 时用 LLM 扩展 query 语义
  └── 或引入 embedding 向量匹配
  └── 预期效果：匹配精度提升 2-3 倍

P2（3-6 月）：Phase 3 化学反应引擎
  └── 多维协同评分
  └── 组织知识图谱
  └── SQLite → PostgreSQL
  └── 预期效果：形成商业壁垒
```

---

## 五、与增值服务的结合点

```
组织内部部署架构：

  ┌──────────────────────────────────────────────┐
  │  星域云服务（SaaS）                          │
  │  ├── 化学反应引擎（算法 API）                │
  │  ├── 向量匹配服务                           │
  │  ├── 领域标签体系                           │
  │  └── 跨组织经验市场                         │
  └──────────────┬───────────────────────────────┘
                 │ API
  ┌──────────────▼───────────────────────────────┐
  │  星域服务器（组织内部）                      │
  │  ├── Agent 注册 + 心跳                      │
  │  ├── 任务匹配 + 对话中继                    │
  │  ├── 声望 + 领域标签                        │
  │  └── pair_history + learned_routes           │
  └──────────────────────────────────────────────┘
```

**商业化分层**：

| 层级 | 功能 | 计费 |
|------|------|------|
| 免费 | 基础关键词匹配 + 全局声望 | 不收费 |
| 标准 | 语义匹配 + 领域声望 + 历史成功率 | 按协作量 |
| 高级 | 化学反应引擎 + 组织知识图谱 + 跨组织推荐 | 按组织规模 |
| 私有 | 全套私有部署 + 定制算法 | 年度授权 |
