# SmanClaw 能力评估与执行层策略（UI + 编排层 + 可插拔执行层）

## 1. 目标与结论

### 1.1 目标

围绕以下三层能力做一次面向落地的评估与规划：

1. 自定义 UI 层（可观测、可解释、可操作）
2. 编排层（贴近业务的拆解、依赖、验收、补救）
3. 执行层（编码工具可插拔，支持 ClaudeCode/OpenCode 等快速切换）

### 1.2 核心结论

- 当前项目已经具备“可运行的编排产品雏形”，不是从 0 到 1 阶段。
- 强项在编排链路、任务文件协议、经验沉淀、事件流。
- 关键短板是后端入口未统一、状态主数据源未统一、执行引擎可插拔仍停留在单一 ZeroClaw 实现。
- 执行层应尽快升级为“统一 Executor SPI + Adapter Registry”，将 ClaudeCode/OpenCode 视为可热插拔执行器，而不是硬编码依赖。

---

## 2. 当前能力盘点（按三层）

## 2.1 UI 层（自定义能力）

### 已有能力

- 聊天窗口具备过程反馈与轮询收敛机制。
- 子任务进度与并行分组可视化已可用。
- 失败总结已支持按 task.md checklist 做“分析/设计/开发/测试”阶段拆解。

对应实现位置：

- `src/components/chat/ChatWindow.svelte`
- `src/components/task/SubTaskProgress.svelte`
- `src-tauri/src/orchestration/remediation.rs`

### 能力评分

- 当前成熟度：**70/100**
- 主要缺口：
  - “阶段说明 + 证据汇总”仍未形成稳定结构化面板；
  - 目前偏事件流拼接，不是强 schema 驱动的可审计视图。

## 2.2 编排层（业务贴合能力）

### 已有能力

- 路由能力已上线：`direct / orchestrated` 自动判定。
- Orchestrator 状态机与 DAG 执行链路已具备主流程。
- 子任务执行支持并行分组调度，支持失败补救与再验收。
- 经验沉淀链路（skills / paths / memory）已接通。

对应实现位置：

- `src-tauri/src/commands/conversation_commands.rs`
- `crates/smanclaw-core/src/orchestrator.rs`
- `src-tauri/src/orchestration/runtime.rs`
- `crates/smanclaw-core/src/sub_claw_executor.rs`

### 能力评分

- 当前成熟度：**75/100**
- 主要缺口：
  - direct 与 orchestrated 后端入口仍分叉；
  - `tasks.db` 与 `main-task.md` 双写，主数据源未统一；
  - 验收深度仍需从规则匹配进一步走向场景级验证证据。

## 2.3 执行层（编码工具可插拔能力）

### 已有能力

- 已有 StepExecutor 抽象，SubClawExecutor 可注入执行器。
- 当前默认执行器为 `ZeroclawStepExecutor`，经由 `ZeroclawBridge` 执行。
- 已存在 Claude skill run script 调用入口（`run.py/run.sh/run`）。
- 已落地 `ClaudeCodeStepExecutor` 与 `build_step_executor`，支持自动发现 Claude skill script。
- 编排主执行与补救执行都已改为统一走执行器工厂，具备“ClaudeCode 优先、ZeroClaw 回退”能力。

对应实现位置：

- `crates/smanclaw-core/src/sub_claw_executor.rs`（`StepExecutor` trait）
- `crates/smanclaw-ffi/src/zeroclaw_step_executor.rs`
- `crates/smanclaw-ffi/src/zeroclaw_bridge.rs`
- `crates/smanclaw-ffi/src/claude_code_step_executor.rs`
- `crates/smanclaw-ffi/src/step_executor_factory.rs`
- `crates/smanclaw-desktop/src-tauri/src/orchestration/runtime.rs`
- `crates/smanclaw-desktop/src-tauri/src/orchestration/remediation.rs`
- `crates/smanclaw-core/src/skill_store.rs`（`find_claude_skill_run_script`）

### 能力评分

- 当前成熟度：**68/100**
- 主要缺口：
  - 尚未形成完整注册中心（目前为工厂路由，非策略化 registry）；
  - 缺少能力声明、健康探测与可观测 SLA 指标；
  - 缺少 ACP/CLI 执行器统一协议层。

---

## 3. 关键问题：我们“真的能做到”吗？

结论：**能做到，但当前处于 60%~70% 的可交付基础阶段，不是 90% 的平台化阶段。**

### 已达到的“可做到”

- 可以完成真实的多步骤任务拆解与执行；
- 可以在失败后进入补救闭环；
- 可以把执行经验回流到技能库；
- 可以给出过程性进度与阶段性反馈。

### 仍未达到的“平台级做到”

- 单一后端入口状态机（当前仍双路径）；
- 单一事实源状态模型（当前 DB/Markdown 双写）；
- 执行器真正可插拔（当前逻辑上可注入，架构上未注册化）；
- 编排过程证据结构化展示（当前以文本事件为主）。

---

## 4. ClaudeCode 如何“打包封装进来”

## 4.1 目标形态

不是把 ClaudeCode 直接硬塞进主流程，而是把它作为执行层里的一个标准 Adapter：

- 编排层不感知“具体是谁执行代码”
- 只感知“执行能力契约 + 输出证据契约”

## 4.2 推荐封装方案（ACP 优先，CLI 兜底）

### A. 抽象统一协议（新增）

在 `smanclaw-core` 定义执行器 SPI：

- `ExecutionEngine`（执行引擎标识与能力）
- `ExecutionRequest`（prompt、workspace、constraints、timeout）
- `ExecutionResult`（output、artifacts、evidence、error）
- `EngineHealth`（ready/version/capabilities）

### B. Adapter 分层（新增）

在 `smanclaw-ffi` 增加：

- `acp_executor.rs`：通过 ACP 协议调用 ClaudeCode/OpenCode
- `cli_executor.rs`：通过命令行子进程调用（兼容 run.py/run.sh）
- `zeroclaw_executor.rs`：现有 ZeroClaw 执行器适配为统一接口

### C. 注册中心（新增）

新增 `EngineRegistry`：

- 按项目配置选择默认执行器（`claudecode` / `opencode` / `zeroclaw`）
- 执行失败自动按策略回退（如 ACP -> CLI -> ZeroClaw）
- 记录每次执行器选择与回退证据，写入事件流

### D. ClaudeCode 封装建议

- 输入：标准化 `ExecutionRequest`
- 过程：通过 ACP 调用 ClaudeCode（优先）
- 输出：标准化 `ExecutionResult`（必须包含命令、文件改动、测试结果）
- 安全：工作目录白名单、命令 allowlist、敏感信息屏蔽

## 4.3 本轮已落地实现（最小可用闭环）

- 新增 `ClaudeCodeStepExecutor`，具备两级后端：
  - 优先执行 `.claude/skills/*/run.py|run.sh|run`（当前默认检索 `code/coder/implement/coding`）
  - 可通过环境变量走命令模式（`SMANCLAW_CLAUDE_CODE_CMD` + `SMANCLAW_CLAUDE_CODE_ARGS`）
- 新增 `build_step_executor` 工厂：
  - `SMANCLAW_STEP_EXECUTOR=auto|claudecode|claude`
  - `auto` 下优先 ClaudeCode，不可用时自动回退 ZeroClaw
- `runtime` 与 `remediation` 两条执行链都已接入同一工厂，避免再次分叉执行器注入逻辑。
- 已补充执行器单测：skill script 路径、命令路径、失败路径。

---

## 5. 目标架构（你定义的三层）

## 5.1 自定义 UI

职责：

- 展示“阶段时间线 + 证据面板 + 补救轮次”
- 支持按阶段过滤证据（分析/设计/开发/测试）
- 支持查看执行器选择与回退记录

## 5.2 编排层（核心业务能力）

职责：

- 根据业务语义拆解任务并形成 DAG
- 定义验收标准并驱动补救闭环
- 提供“业务模板化编排能力”（行业/团队定制）

## 5.3 执行层（可插拔编码工具）

职责：

- 屏蔽底层执行工具差异（ClaudeCode/OpenCode/ZeroClaw）
- 提供统一能力声明与健康检查
- 对上游暴露稳定契约，支持快速替换

---

## 6. 改进清单（按优先级）

## P0（必须优先完成）

1. 统一后端执行入口为单一状态机入口。
2. 统一状态主数据源（定义 DB 与 Markdown 的权威关系）。
3. 建立执行器 SPI + Registry（先接 ZeroClaw 与 ClaudeCode 两种实现）。
4. 在 UI 落地阶段化证据面板（至少支持四阶段和补救轮次）。

## P1（增强可插拔与工程化）

1. 接入 OpenCode Adapter，验证多执行器切换。
2. 增加执行器健康检查、冷启动检测与预热机制。
3. 增加执行器 SLA 指标（成功率、平均耗时、回退率）。
4. 增加编排模板（按业务域差异化拆解策略）。

## P2（平台化）

1. 支持执行器灰度发布和 A/B 对比。
2. 支持多租户策略（不同项目默认执行器不同）。
3. 形成执行证据标准并用于审计与复盘。

---

## 7. 里程碑与验收标准（建议）

## M1：统一入口与状态（2 周）

- 验收：
  - direct/orchestrated 从同一入口进入；
  - 任一任务的状态可从单一来源回放；
  - 旧入口仅保留兼容转发。

## M2：执行器可插拔最小闭环（2 周）

- 验收：
  - ZeroClaw 与 ClaudeCode 可通过同一 SPI 执行；
  - 项目级可切换默认执行器；
  - 执行失败可自动回退并记录证据。

## M3：结构化可视化（2 周）

- 验收：
  - UI 展示分析/设计/开发/测试阶段；
  - 每阶段至少一条结构化证据；
  - 补救轮次可查看失败原因、修复动作、回归结果。

---

## 8. 风险与应对

- 风险：执行器抽象过度设计导致推进变慢。  
  应对：先做最小 SPI，只承载当前必需字段。

- 风险：多执行器输出格式不一致。  
  应对：强制 `ExecutionResult` schema，适配器做归一化。

- 风险：引入 ACP 后运维复杂度上升。  
  应对：保留 CLI fallback，先灰度到单项目。

---

## 9. 最终判断

你们的核心定位是正确的：

- **自定义 UI**：承载解释性与信任；
- **编排层**：承载业务差异化能力；
- **执行层可插拔**：承载技术路线灵活性与供应商解耦。

当前不缺方向，缺的是把“已有能力”收敛成“平台契约”。
下一阶段最关键的不是继续加功能，而是完成：**单一入口 + 单一状态源 + 执行器注册化** 三件事。

---

## 10. 完整实施清单（基于当前项目，可直接开工）

说明：

- 本清单基于当前仓库现状与已实现能力编制，不是通用模板。
- 每个任务都对齐你当前三层目标：UI / 编排层 / 可插拔执行层。
- 推荐按 T0 -> T6 顺序执行，避免后续返工。

### 10.1 当前代码锚点（用于任务落位）

- 编排主执行入口：`crates/smanclaw-desktop/src-tauri/src/orchestration/runtime.rs`
- 编排补救入口：`crates/smanclaw-desktop/src-tauri/src/orchestration/remediation.rs`
- 执行器工厂：`crates/smanclaw-ffi/src/step_executor_factory.rs`
- ClaudeCode 执行适配器：`crates/smanclaw-ffi/src/claude_code_step_executor.rs`
- 执行抽象契约：`crates/smanclaw-core/src/sub_claw_executor.rs`
- UI 进度组件：`crates/smanclaw-desktop/src/components/task/SubTaskProgress.svelte`
- UI 会话组件：`crates/smanclaw-desktop/src/components/chat/ChatWindow.svelte`

### 10.2 T0 治理基线（必须先做）

1. T0-01 冻结当前行为基线样例（direct/orchestrated 各 10 条）。
2. T0-02 定义单一入口状态机图（输入、状态、终态、异常态）。
3. T0-03 定义单一事实源策略（DB 权威、Markdown 投影）。
4. T0-04 定义 Evidence Schema v1（阶段、动作、结果、耗时、执行器）。
5. T0-05 固化四级触发策略（观察/提醒/建议/自动执行）。
6. T0-06 固化工程门禁命令（lint/typecheck/test/check）。

验收：

- 有可复现基线样例；
- 状态图、数据源策略、证据 schema 文档可评审；
- 门禁命令在 CI 与本地一致。

### 10.3 T1 单一入口状态机（P0）

1. T1-01 盘点 direct/orchestrated 入口与调用路径。
2. T1-02 新增统一入口模块，旧入口仅做兼容转发。
3. T1-03 下沉路由判定为统一 pre-routing 步骤。
4. T1-04 统一生命周期状态枚举与终态判定。
5. T1-05 显式区分可补救失败与不可恢复失败。
6. T1-06 接入超时、取消、乱序事件状态转换。
7. T1-07 将补救执行链纳入同一状态机入口。
8. T1-08 补状态机单测（成功/失败/超时/乱序）。
9. T1-09 补集成回归（direct 与 orchestrated 一致性）。
10. T1-10 增加灰度开关，支持快速回滚。

验收：

- direct/orchestrated 通过同一入口；
- 无“中间输出即完成”的提前收敛；
- 异常路径可恢复且状态可回放。

### 10.4 T2 单一事实源（P0）

1. T2-01 盘点所有 DB/Markdown 写入点与读路径。
2. T2-02 统一 DB 为权威源，Markdown 改为投影视图。
3. T2-03 新增状态仓储接口，所有状态更新只走仓储层。
4. T2-04 runtime 执行结果统一写入仓储。
5. T2-05 remediation 执行结果统一写入仓储。
6. T2-06 增加 Markdown 投影生成器（只读 DB 生成）。
7. T2-07 编写历史数据迁移脚本并回灌验证。
8. T2-08 增加启动一致性校验与漂移告警。
9. T2-09 增加幂等更新保护（重复事件不污染状态）。
10. T2-10 补崩溃恢复回放测试。

验收：

- 任一任务状态可从 DB 单源回放；
- Markdown 与 DB 不再双写竞争；
- 迁移后历史任务状态一致。

### 10.5 T3 执行器注册化与多引擎（P0->P1）

1. T3-01 将工厂升级为 EngineRegistry（注册/发现/选择）。
2. T3-02 定义 ExecutionRequest/ExecutionResult 协议对象。
3. T3-03 定义 EngineHealth/Capabilities 能力声明。
4. T3-04 ZeroClaw 适配统一协议并接入 registry。
5. T3-05 ClaudeCode 适配统一协议并接入 registry。
6. T3-06 接入 OpenCode Adapter（最小可执行路径）。
7. T3-07 项目级默认执行器配置 + 任务级覆盖。
8. T3-08 失败自动回退链（OpenCode/ClaudeCode/ZeroClaw）。
9. T3-09 冷启动检测与预热机制。
10. T3-10 增加 SLA 指标（成功率、均耗、回退率）。

验收：

- 三执行器可在同一契约下切换；
- 回退有策略且可观测；
- SLA 指标可在 UI 或日志中查询。

### 10.6 T4 UI 证据面板（P0）

1. T4-01 定义证据数据模型（阶段、轮次、动作、产物）。
2. T4-02 增加阶段时间线（分析/设计/开发/测试）。
3. T4-03 增加阶段筛选与证据搜索。
4. T4-04 增加补救轮次视图（失败原因/修复动作/回归结果）。
5. T4-05 展示执行器选择与回退记录。
6. T4-06 增加证据详情抽屉（命令、改动、测试摘要）。
7. T4-07 增加前端 schema 校验，拒绝异常事件渲染。
8. T4-08 补组件测试与端到端回归。

验收：

- 四阶段与补救轮次可视化完整；
- 每阶段至少一条结构化证据；
- 不再出现状态展示与后端状态冲突。

### 10.7 T5 主动服务三条链路（P1）

1. T5-01 信息巡检链（定时汇总高优先事项）。
2. T5-02 项目推进链（阻塞检测与下一步建议）。
3. T5-03 异常诊断链（日志聚合 -> 深度诊断 -> 修复建议）。
4. T5-04 固化触发 ClaudeCode 的六字段输入契约。
5. T5-05 落地四级触发策略与确认门控。
6. T5-06 加入触发去重与噪声抑制。
7. T5-07 加入采纳反馈回写与策略优化循环。

验收：

- 三条链路稳定运行两周；
- 误报率可控、采纳率持续上升；
- 高风险动作有人工确认保护。

### 10.8 T6 生产门禁与发布（必须全绿）

1. T6-01 功能回归门禁（主流程+异常流程+边界输入）。
2. T6-02 一致性门禁（前后端状态与 UI 展示一致）。
3. T6-03 可靠性门禁（超时、重试、降级可复现）。
4. T6-04 可观测门禁（关键链路可追踪、可定位）。
5. T6-05 工程门禁（lint/typecheck/tests/check 全通过）。
6. T6-06 安全门禁（命令白名单、路径白名单、敏感信息屏蔽）。
7. T6-07 发布门禁（灰度开关、回滚预案、值班流程）。

验收：

- 门禁项全部通过才允许上线；
- 任一门禁失败，发布自动阻断。

### 10.9 里程碑建议（8 周）

- W1-W2：T0 + T1（统一入口状态机）。
- W3-W4：T2（单一事实源）。
- W5-W6：T3（注册化 + OpenCode 最小接入）。
- W7：T4（UI 证据面板）。
- W8：T5 + T6（主动链路 + 生产门禁发布）。

### 10.10 每个任务的完成定义（DoD）

- 代码实现完成并通过评审。
- 单测 + 集成测试覆盖新增路径。
- 成功与失败路径都有验证证据。
- 关键事件可观测、可定位、可回放。
- 门禁命令全绿后方可标记完成。
