# 银行核心系统 AI 分析助手

基于 LLM 驱动的银行核心系统代码分析工具。

## 架构

```
smanagent/
├── agent/              # 后端服务（Spring Boot + Java）
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       └── resources/
│   └── build.gradle
├── ide-plugin/         # IntelliJ IDEA 插件（Kotlin）
│   ├── src/
│   │   └── main/
│   │       ├── kotlin/
│   │       └── resources/
│   └── build.gradle.kts
└── ARCHITECTURE.md     # 架构设计文档
```

## 设计理念

- **LLM 是引擎，架构是底盘**
- **后端提供纯查询服务**（语义搜索、业务图谱）
- **前端插件负责 LLM 推理和交互**
- **流式输出 + 可点击链接**

参见 [ARCHITECTURE.md](ARCHITECTURE.md) 了解完整架构设计。

## 开发

### 后端服务

```bash
cd agent
./gradlew bootRun
```

### IDE 插件

```bash
cd ide-plugin
./gradlew runIde
```
