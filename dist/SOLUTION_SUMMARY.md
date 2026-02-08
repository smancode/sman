# SmanAgent 统一打包方案总结

## 方案对比

### ❌ 原方案（smanunion 项目）
- 复制整个 smanagent 项目到 smanunion
- 代码重复，维护成本高
- 无实质性收益

### ✅ 最终方案（在 smanagent 添加 dist 目录）
- 保持 smanagent 原项目不变
- 仅添加 `dist/` 目录用于打包
- 前后端分离能力完全保留

## 实现的改动

### 1. 新增文件

**dist/ 目录**（打包相关）:
```
dist/
├── build-unified-plugin.sh    # 统一打包脚本
└── README.md                   # 打包说明文档
```

### 2. 修改文件

**ide-plugin/build.gradle.kts**:
```kotlin
// 添加后端 JAR 打包逻辑
prepareSandbox {
    from("../agent/build/libs/smanagent-agent-1.0.0.jar") {
        into("${project.name}/lib")
    }
}
```

**ide-plugin/src/main/kotlin/.../SmanAgentPlugin.kt**:
```kotlin
// 添加后端自动启动逻辑
- 端口检测
- 进程启动
- 启动超时处理
```

### 3. 保持不变

- ✅ `agent/` - 后端源码完全不变
- ✅ `ide-plugin/src/` - 前端源码基本不变（仅主类）
- ✅ 原有的构建系统完全不变
- ✅ 前后端可以独立开发、独立运行

## 使用方式

### 开发模式（前后端分离）

```bash
# 终端 1：启动后端
cd agent
./gradlew bootRun

# 终端 2：启动前端
cd ide-plugin
./gradlew runIde
```

### 发布模式（统一打包）

```bash
# 一键打包
./dist/build-unified-plugin.sh
```

生成文件：`ide-plugin/build/distributions/smanagent-1.1.0.zip` (70MB)

### 安装使用

1. 安装插件 ZIP
2. 配置环境变量
3. 插件自动启动后端
4. 开始使用

## 验证结果

✅ **构建成功**: 后端 JAR (48MB) 已包含在插件中
✅ **功能完整**: 保留所有原有功能
✅ **独立开发**: 前后端仍可独立运行
✅ **一键打包**: 单个脚本完成打包

## 项目结构

```
smanagent/
├── agent/              # 后端 Spring Boot（不变）
│   └── build/libs/smanagent-agent-1.0.0.jar
├── ide-plugin/         # 前端插件（仅添加打包配置）
│   ├── build.gradle.kts        # 添加 prepareSandbox
│   ├── src/.../SmanAgentPlugin.kt  # 添加自动启动
│   └── build/distributions/smanagent-1.1.0.zip
├── dist/               # 新增：打包相关
│   ├── build-unified-plugin.sh
│   └── README.md
├── docs/               # 文档（不变）
├── ARCHITECTURE.md     # 架构文档（不变）
└── README.md           # 项目说明（不变）
```

## 核心优势

1. **最小改动**: 仅修改 2 个文件，新增 2 个文件
2. **向后兼容**: 原有开发流程完全不变
3. **灵活部署**: 支持分离开发和统一打包
4. **易于维护**: 无代码重复，单一数据源

## 与 smanunion 的区别

| 维度 | smanunion | smanagent + dist |
|------|-----------|------------------|
| 代码重复 | 是（完整复制） | 否 |
| 维护成本 | 高（两处修改） | 低（单一源码） |
| 改动范围 | 新建整个项目 | 仅添加打包逻辑 |
| 前后端分离 | 是 | 是 |
| 一键打包 | 是 | 是 |

## 下一步

smanunion 项目可以删除，使用 smanagent + dist 方案即可。

```bash
# 删除 smanunion（如果不需要）
rm -rf /Users/liuchao/projects/smanunion
```

## 总结

**最简方案 = 在原项目添加打包逻辑**

- ✅ 保留前后端分离能力
- ✅ 添加统一打包能力
- ✅ 最小改动原则
- ✅ 无代码重复
