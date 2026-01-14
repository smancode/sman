# SmanAgent 完整流程图

## 整体架构流程（突出 Search 的核心作用）

```mermaid
flowchart TD
    subgraph DataSources["📊 业务图谱数据来源（3个）"]
        KGS["knowledge-graph-system<br/>（分析代码仓库推送）"]
        MANUAL["人工维护<br/>（规则/配置）"]
        AUTO["历史会话自动生成<br/>（挖掘关联）"]
    end

    subgraph Storage["💾 本地存储（KnowledgeGraphClient）"]
        BGC["BusinessContext<br/>业务背景"]
        BGK["BusinessKnowledge<br/>业务知识"]
        CM["CodeMapping<br/>业务↔代码映射"]
    end

    subgraph SearchCore["🔍 Search SubAgent（核心）"]
        ANALYZE["分析查询类型<br/>业务需求 vs 代码查询"]
        QUERY_KG["查询知识图谱<br/>业务背景+规则"]
        QUERY_CODE["搜索代码<br/>向量+关键词"]
        SYNTHESIS["LLM 综合推理<br/>生成结构化答案"]
    end

    subgraph MainFlow["主流程"]
        JUDGE["LLM 判断<br/>是否需要 Search"]
        PREPROCESS["Search 预处理<br/>深度理解+知识加载"]
        REACT["ReAct 循环<br/>工具调用"]
    end

    %% 数据来源流向
    KGS -->|HTTP推送| BGC
    KGS -->|HTTP推送| CM
    MANUAL -->|API写入| BGK
    AUTO -->|自动挖掘| CM

    %% Search 内部流程
    BGC -.->|查询| QUERY_KG
    BGK -.->|查询| QUERY_KG
    CM -.->|查询| QUERY_KG
    CM -.->|查询| QUERY_CODE

    ANALYZE -->|业务需求| QUERY_KG
    ANALYZE -->|代码查询| QUERY_CODE
    QUERY_KG --> SYNTHESIS
    QUERY_CODE --> SYNTHESIS

    %% 主流程
    Start([用户输入]) --> JUDGE
    JUDGE -->|第一轮/新主题| PREPROCESS
    JUDGE -->|追问/修改| REACT

    PREPROCESS -->|调用| SearchCore
    SYNTHESIS -->|返回| PREPROCESS
    PREPROCESS -->|注入上下文| REACT

    REACT --> Final([返回结果])

    style SearchCore fill:#e1f5ff,stroke:#01579b,stroke-width:3px
    style SYNTHESIS fill:#c8e6c9,stroke:#2e7d32,stroke-width:2px
    style DataSources fill:#fff3e0,stroke:#ef6c00
    style Storage fill:#f3e5f5,stroke:#7b1fa2
    style PREPROCESS fill:#ffe0b2,stroke:#ef6c00,stroke-width:2px
```

## Search 数据来源详解

```mermaid
flowchart LR
    subgraph Sources["业务图谱数据来源"]
        direction TB
        S1["1️⃣ knowledge-graph-system<br/>自动推送"]
        S2["2️⃣ 人工维护<br/>运营/开发录入"]
        S3["3️⃣ 历史会话自动生成<br/>挖掘用户问题与代码的关联"]
    end

    subgraph API["API 接口（KnowledgeGraphController）"]
        A1["POST /api/knowledge/update/context<br/>更新业务背景"]
        A2["POST /api/knowledge/update/knowledge<br/>更新业务知识"]
        A3["POST /api/knowledge/update/mapping<br/>更新代码映射"]
    end

    subgraph Client["KnowledgeGraphClient（本地存储）"]
        C1["businessContextMap<br/>ConcurrentHashMap"]
        C2["businessKnowledgeMap<br/>ConcurrentHashMap"]
        C3["codeMappingMap<br/>ConcurrentHashMap"]
    end

    subgraph AutoGen["自动生成（历史会话挖掘）"]
        AG1["会话历史分析"]
        AG2["问题-代码关联挖掘"]
        AG3["自动更新映射"]
    end

    %% 数据流向
    S1 -->|HTTP调用| A1
    S1 -->|HTTP调用| A3
    S2 -->|后台管理| A2
    S3 --> AG1

    A1 --> C1
    A2 --> C2
    A3 --> C3
    AG1 --> AG2
    AG2 --> AG3
    AG3 --> C3

    style Sources fill:#fff3e0
    style API fill:#e1f5ff
    style Client fill:#f3e5f5
    style AutoGen fill:#c8e6c9
```

## Search 预处理完整流程（突出数据来源）

```mermaid
flowchart TD
    Start([用户输入]) --> FirstRound{第一轮对话?<br/>messageCount ≤ 2}

    FirstRound -->|是| NeedSearch[需要 Search]
    FirstRound -->|否| LLMJudge[LLM 智能判断]

    LLMJudge --> BuildPrompt[构建判断 Prompt<br/>最近3轮对话+当前输入]
    BuildPrompt --> CallLLM[调用 LLM]
    CallLLM --> ParseResult[解析 JSON 结果]

    ParseResult --> CheckDecision{LLM 判断}
    CheckDecision -->|needSearch=true| NeedSearch
    CheckDecision -->|needSearch=false| SkipSearch[跳过 Search<br/>复用上下文]

    NeedSearch --> SearchStart[🔍 开始 Search 预处理]

    SearchStart --> PushReasoning[推送 reasoning<br/>正在深度理解...]

    PushReasoning --> SearchAgent[SearchSubAgent.search]

    SearchAgent --> AnalyzeQuery[分析查询类型<br/>业务需求 vs 代码查询]

    AnalyzeQuery --> BizCheck{业务需求?}
    BizCheck -->|是| GetKG[从知识图谱查询<br/>▼▼▼]
    BizCheck -->|否| GetCode[搜索代码<br/>TODO: 向量搜索]

    GetKG --> KGData((业务图谱数据))
    KGData --> BGC["BusinessContext<br/>▼▼▼ 来源1: KGS推送<br/>来源2: 人工维护<br/>来源3: 自动生成"]
    KGData --> BGK["BusinessKnowledge<br/>▼▼▼ 来源: 人工维护"]
    KGData --> CM["CodeMapping<br/>▼▼▼ 来源1: KGS推送<br/>来源3: 自动生成"]

    BGC --> MergeCtx[合并上下文]
    BGK --> MergeCtx
    CM --> MergeCtx

    GetCode --> MergeCtx
    MergeCtx --> LLMReason[LLM 综合推理<br/>生成结构化答案]

    LLMReason --> ParseResult2[解析结果<br/>▼▼▼ businessContext<br/>businessKnowledge<br/>codeEntries<br/>codeRelations<br/>summary]

    ParseResult2 --> BuildPart[构建上下文 Part<br/>Search 预处理结果]

    BuildPart --> InjectToSession[注入到会话<br/>SYSTEM 消息]

    InjectToSession --> MainFlow[进入主流程<br/>基于已加载的上下文]

    SkipSearch --> MainFlow

    style SearchAgent fill:#e1f5ff,stroke:#01579b,stroke-width:3px
    style LLMReason fill:#c8e6c9,stroke:#2e7d32,stroke-width:2px
    style KGData fill:#fff3e0,stroke:#ef6c00
    style MainFlow fill:#e8f5e9
```

## 数据来源详细说明

```mermaid
flowchart TB
    subgraph Source1["来源1: knowledge-graph-system（自动推送）"]
        direction LR
        S1A["代码仓库分析"]
        S1B["业务实体提取"]
        S1C["业务-代码关联"]
        S1D["HTTP 推送到 SmanAgent"]
    end

    subgraph Source2["来源2: 人工维护"]
        direction LR
        S2A["运营人员配置业务规则"]
        S2B["开发人员维护映射关系"]
        S2C["后台管理界面"]
        S2D["API 写入 KnowledgeGraphClient"]
    end

    subgraph Source3["来源3: 历史会话自动生成"]
        direction LR
        S3A["收集用户问题"]
        S3B["分析涉及的代码"]
        S3C["挖掘关联关系"]
        S3D["自动更新 CodeMapping"]
    end

    subgraph Storage["KnowledgeGraphClient 存储"]
        ST1["BusinessContext<br/>项目名称、描述、领域"]
        ST2["BusinessKnowledge<br/>规则、SOP、流程"]
        ST3["CodeMapping<br/>业务↔代码 双向映射"]
    end

    subgraph Consumer["消费者：SearchSubAgent"]
        CS1["查询业务背景"]
        CS2["查询业务知识"]
        CS3["查询代码映射"]
    end

    %% 数据流向
    S1D --> ST1
    S1D --> ST3

    S2D --> ST2

    S3D --> ST3

    ST1 --> CS1
    ST2 --> CS2
    ST3 --> CS3

    style Source1 fill:#ffe0b2
    style Source2 fill:#c8e6c9
    style Source3 fill:#e1bee7
    style Storage fill:#e1f5ff,stroke:#01579b,stroke-width:2px
    style Consumer fill:#fff9c4
```

## 完整对话流程（含数据来源）

```mermaid
sequenceDiagram
    participant User as 用户
    participant Loop as SmanAgentLoop
    participant Judge as Search判断 LLM
    participant Search as SearchSubAgent
    participant KG as KnowledgeGraphClient
    participant Main as 主 LLM

    %% 数据来源说明
    Note over KG: 数据来源：<br/>1. knowledge-graph-system<br/>2. 人工维护<br/>3. 历史会话自动生成

    %% 第一轮对话
    User->>Loop: "520提额添加客户经理浮层提示"
    Loop->>Loop: messageCount=1 ≤ 2
    Loop->>Search: 执行 Search 预处理

    Search->>KG: 查询业务背景
    KG-->>Search: BusinessContext<br/>（来源：KGS推送）
    Search->>KG: 查询业务知识
    KG-->>Search: BusinessKnowledge<br/>（来源：人工维护）
    Search->>KG: 查询代码映射
    KG-->>Search: CodeMapping<br/>（来源：KGS推送+自动生成）

    Search->>Search: LLM 综合推理
    Search-->>Loop: Search 结果

    Loop->>Loop: 注入到会话（SYSTEM 消息）
    Loop->>Main: 进入主循环

    Main->>Main: 基于 Search 上下文规划
    Main-->>Loop: 返回方案
    Loop-->>User: 方案结果

    %% 第二轮对话
    User->>Loop: "把浮层颜色改成红色"
    Loop->>Judge: LLM 判断是否 Search
    Judge->>Judge: 分析对话历史+当前输入
    Judge-->>Loop: needSearch=false<br/>reason: 追问模式
    Loop->>Loop: 跳过 Search
    Loop->>Main: 直接进入主循环
    Main->>Main: 基于已有上下文处理
    Main-->>Loop: 修改完成
    Loop-->>User: 修改结果

    %% 自动生成环节
    Note over KG: 会话结束后<br/>自动挖掘关联<br/>更新 CodeMapping

    style Search fill:#e1f5ff,stroke:#01579b,stroke-width:2px
    style KG fill:#fff3e0,stroke:#ef6c00
```

## 主循环 ReAct 流程（Search 预处理后）

```mermaid
flowchart TD
    Start([ReAct 循环开始]) --> CheckContext{有 Search 上下文?}

    CheckContext -->|有| UseContext[使用 Search 上下文<br/>业务背景+代码入口]
    CheckContext -->|无| Normal[正常模式]

    UseContext --> BuildPrompt[构建 Prompt<br/>系统提示词+对话历史<br/>+Search上下文+工具结果]
    Normal --> BuildPrompt

    BuildPrompt --> CallLLM[调用 LLM]
    CallLLM --> ExtractJSON{提取 JSON?}

    ExtractJSON -->|失败| TextMode[纯文本模式<br/>退出循环]
    ExtractJSON -->|成功| ParseParts[解析 Parts]

    ParseParts --> CheckTool{有工具调用?}
    CheckTool -->|否| CheckSubTask{有 SubTask?}

    CheckTool -->|是| ExecTool[执行工具<br/>read_file/grep_file/find_file等]
    CheckSubTask -->|是| ExecSubTask[执行 SubTask]

    ExecTool --> AddToolResult[添加工具结果到历史]
    ExecSubTask --> AddSubTaskResult[添加 SubTask 结果到历史]

    AddToolResult --> CheckMaxSteps{达到最大步数?}
    AddSubTaskResult --> CheckMaxSteps

    CheckMaxSteps -->|否| BuildPrompt
    CheckMaxSteps -->|是| MaxStepsExit[达到最大步数<br/>退出循环]

    CheckTool -->|否| CheckSubTask
    CheckSubTask -->|否| FinalAnswer[最终回答<br/>退出循环]

    TextMode --> End([循环结束])
    FinalAnswer --> End
    MaxStepsExit --> End

    style UseContext fill:#c8e6c9,stroke:#2e7d32,stroke-width:2px
    style CallLLM fill:#e1f5ff
    style ExecTool fill:#fff3e0
    style ExecSubTask fill:#f3e5f5
    style FinalAnswer fill:#ffe0b2
```

## 关键设计决策

| 决策点 | 方案 | 理由 |
|--------|------|------|
| **Search 触发** | LLM 判断 | 避免硬编码，智能识别新主题 vs 追问 |
| **第一轮对话** | 必定 Search | 需要加载业务背景和代码入口 |
| **追问/修改** | 跳过 Search | 复用已有上下文，提高效率 |
| **数据来源1** | KGS 自动推送 | 代码分析自动化，减少人工维护 |
| **数据来源2** | 人工维护 | 业务规则需要专家配置 |
| **数据来源3** | 历史会话生成 | 持续优化映射关系 |
| **数据存储** | 内存占位符 | 后续替换为数据库持久化 |

