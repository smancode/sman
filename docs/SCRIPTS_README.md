# SiliconMan Agent 启动脚本使用说明

## 快速开始

### 启动 Agent
```bash
cd /Users/liuchao/projects/sman/agent
./start.sh
```

### 停止 Agent
```bash
cd /Users/liuchao/projects/sman/agent
./stop.sh
```

---

## 脚本功能详解

### start.sh - 启动脚本

**主要功能**：
1. ✅ 环境检查（Java版本、可用内存）
2. ✅ 自动编译（Gradle clean build）
3. ✅ 端口占用检查和清理
4. ✅ 旧进程清理
5. ✅ 启动应用（带GC日志）
6. ✅ 健康检查（等待最多60秒）
7. ✅ 显示系统状态和管理命令

**内存配置**（本地开发）：
- 初始内存：512MB
- 最大内存：1GB
- 垃圾收集器：G1GC
- Worker数量：3个

**日志文件**：
- 应用日志：`logs/app.log`
- GC日志：`logs/gc/gc.log`
- PID文件：`app.pid`

---

### stop.sh - 停止脚本

**主要功能**：
1. ✅ 检查PID文件和进程
2. ✅ 优雅停止（SIGTERM，等待10秒）
3. ✅ 强制停止（SIGKILL）
4. ✅ 清理所有Worker进程
5. ✅ 清理端口占用
6. ✅ 保留工作目录（下次快速启动）

**清理说明**：
- ✅ 清理Agent主进程
- ✅ 清理所有Claude Code worker进程
- ✅ 清理端口占用
- 💾 保留worker工作目录（复用）
- 💾 保留会话历史（`data/sessions/`）
- 💾 保留向量索引（`data/vector-index/`）

---

## 目录结构

```
sman/
├── docs/md/                    # 文档目录
│   └── architecture-qa.md      # 架构说明
└── agent/
    ├── start.sh                # 启动脚本 ⭐
    ├── stop.sh                 # 停止脚本 ⭐
    ├── app.pid                 # 进程ID文件（运行时生成）
    ├── build/libs/             # JAR文件目录
    │   └── siliconman-agent-1.0.0.jar
    ├── logs/                   # 日志目录
    │   ├── app.log            # 应用日志
    │   └── gc/                # GC日志目录
    │       └── gc.log         # GC日志
    └── data/                   # 数据目录
        ├── sessions/          # 会话存储
        ├── vector-index/      # 向量索引
        └── claude-code-workspaces/  # Worker工作目录
```

---

## 常用命令

### 查看日志
```bash
# 进入agent目录
cd /Users/liuchao/projects/sman/agent

# 实时查看应用日志
tail -f logs/app.log

# 查看最近的错误
grep ERROR logs/app.log | tail -20

# 查看worker通信日志
grep "Claude Code" logs/app.log | tail -50
```

### 检查进程状态
```bash
# 检查进程是否运行
cat app.pid
ps -p $(cat app.pid)

# 查看进程池状态
curl http://localhost:8080/api/claude-code/pool/status | jq

# 健康检查
curl http://localhost:8080/api/claude-code/health
```

### 测试API
```bash
# 发送分析请求
curl -X POST http://localhost:8080/api/analysis/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-001",
    "message": "读取文件异常了增加重试1次的功能",
    "projectKey": "autoloop"
  }'

# 查看会话历史
curl http://localhost:8080/api/sessions/list
```

---

## 故障排查

### 端口被占用
```bash
# 查看占用8080端口的进程
lsof -i:8080

# 强制清理端口
kill -9 $(lsof -ti:8080)
```

### 启动失败
```bash
# 1. 查看详细日志
cd /Users/liuchao/projects/sman/agent
tail -100 logs/app.log

# 2. 检查Java版本（需要Java 17+）
java -version

# 3. 检查内存（至少2GB可用）
free -m  # Linux
vm_stat | grep "Pages free"  # macOS

# 4. 清理并重新启动
./stop.sh
./start.sh
```

### Worker进程问题
```bash
# 查看所有worker进程
ps aux | grep "claude-code-mock"

# 清理所有worker
pkill -f "claude-code-mock"

# 查看worker工作目录
ls -la data/claude-code-workspaces/
```

---

## 性能优化

### 生产环境配置

修改 `src/main/resources/application.yml`：

```yaml
claude-code:
  pool:
    size: 10                    # 生产环境建议10-15个worker

# 或者使用环境变量
export CLAUDE_CODE_POOL_SIZE=15
./start.sh
```

### 内存优化

修改 `start.sh` 中的内存配置：

```bash
# 大型项目（3500+个类）
JAVA_OPTS="-Xms2g -Xmx4g"

# 中型项目（1000-3500个类）
JAVA_OPTS="-Xms1g -Xmx2g"

# 小型项目（<1000个类）
JAVA_OPTS="-Xms512m -Xmx1g"
```

---

## 监控和维护

### 日志轮转
```bash
# 手动清理旧日志（保留最近30天）
cd /Users/liuchao/projects/sman/agent
find logs/ -name "*.log" -mtime +30 -delete

# 或使用logrotate配置
cat > /etc/logrotate.d/siliconman-agent << EOF
/Users/liuchao/projects/sman/agent/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
}
EOF
```

### 定期清理会话历史
```bash
# 清理7天前的会话
cd /Users/liuchao/projects/sman/agent
find data/sessions/ -name "*.json" -mtime +7 -delete
```

---

## 与 bank-core-analysis-agent 的对比

| 特性 | bank-core-analysis-agent | siliconman-agent |
|------|-------------------------|------------------|
| 脚本位置 | 项目根目录 | agent/ 模块目录 ⭐ |
| 内存配置 | 2GB-4GB | 512MB-1GB |
| Worker数量 | N/A | 3个（本地开发） |
| 垃圾收集器 | G1GC | G1GC |
| GC日志 | ✅ | ✅ |
| 健康检查 | ✅ | ✅ |
| Worker清理 | N/A | ✅ |
| 项目规模 | 3500+个类 | 中小型项目 |

---

## 下一步

1. **配置真实项目**：在 `src/main/resources/application.yml` 中配置项目路径
2. **测试向量搜索**：确保 BGE-M3 和 BGE-Reranker 已启动
3. **查看架构文档**：`../docs/md/architecture-qa.md`
4. **阅读API文档**：`../docs/md/02-websocket-api.md`

---

## 关键改进点

### 脚本位置优化 ⭐
```bash
# ✅ 正确：脚本在agent/模块目录
sman/agent/start.sh
sman/agent/stop.sh

# ❌ 错误：脚本在项目根目录（原实现）
sman/start.sh
sman/stop.sh
```

**优势**：
1. 脚本和管理的资源在同一目录
2. 路径引用更简洁（去掉 `MODULE_NAME` 前缀）
3. 符合多模块项目的最佳实践

### 路径引用简化
```bash
# 之前（根目录脚本）
JAR_FILE="$MODULE_NAME/build/libs/$MODULE_NAME-1.0.0.jar"
PID_FILE="$MODULE_NAME/app.pid"
LOG_FILE="$MODULE_NAME/logs/app.log"

# 现在（agent/目录脚本）
JAR_FILE="build/libs/${APP_NAME}-1.0.0.jar"
PID_FILE="app.pid"
LOG_FILE="logs/app.log"
```

---

## 联系方式

- 问题反馈：查看 `../docs/` 目录下的相关文档
- 架构说明：`../docs/md/architecture-qa.md`
- WebSocket API：`../docs/md/02-websocket-api.md`

