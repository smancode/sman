#!/bin/bash
# SiliconMan 项目打包脚本
# 用途: 打包源码用于企业内部部署

set -e  # 遇到错误立即退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 项目根目录
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${PROJECT_ROOT}/dist"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
PACKAGE_NAME="siliconman-src-${TIMESTAMP}.zip"
PACKAGE_PATH="${DIST_DIR}/${PACKAGE_NAME}"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  SiliconMan 项目打包工具${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 1. 清理旧的打包目录
echo -e "${YELLOW}[1/5] 清理旧的打包目录...${NC}"
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"
echo -e "${GREEN}✓ 清理完成${NC}"
echo ""

# 2. 创建临时打包目录
echo -e "${YELLOW}[2/5] 创建临时打包目录...${NC}"
TEMP_DIR="${DIST_DIR}/temp_package"
rm -rf "${TEMP_DIR}"
mkdir -p "${TEMP_DIR}"
echo -e "${GREEN}✓ 临时目录创建完成: ${TEMP_DIR}${NC}"
echo ""

# 3. 复制项目文件（排除不需要的内容）
echo -e "${YELLOW}[3/5] 复制项目文件...${NC}"

# 复制根目录文件
cp "${PROJECT_ROOT}/README.md" "${TEMP_DIR}/" 2>/dev/null || true
cp "${PROJECT_ROOT}/LICENSE" "${TEMP_DIR}/" 2>/dev/null || true
cp "${PROJECT_ROOT}/CLAUDE.md" "${TEMP_DIR}/" 2>/dev/null || true
cp "${PROJECT_ROOT}/API-QUICK-REF.md" "${TEMP_DIR}/" 2>/dev/null || true
cp "${PROJECT_ROOT}/build.gradle.kts" "${TEMP_DIR}/" 2>/dev/null || true
cp "${PROJECT_ROOT}/settings.gradle.kts" "${TEMP_DIR}/" 2>/dev/null || true
cp "${PROJECT_ROOT}/gradlew" "${TEMP_DIR}/" 2>/dev/null || true
cp "${PROJECT_ROOT}/gradlew.bat" "${TEMP_DIR}/" 2>/dev/null || true
cp "${PROJECT_ROOT}/.gitignore" "${TEMP_DIR}/" 2>/dev/null || true

# 复制 gradle wrapper
mkdir -p "${TEMP_DIR}/gradle"
cp -r "${PROJECT_ROOT}/gradle/wrapper" "${TEMP_DIR}/gradle/" 2>/dev/null || true

# 复制 agent 目录
echo -e "  - 复制 agent/ 目录..."
rsync -av --exclude='build/' \
         --exclude='logs/' \
         --exclude='.gradle/' \
         --exclude='data/vector-index/' \
         --exclude='data/sessions/' \
         --exclude='data/claude-code-workspaces/' \
         --exclude='__pycache__' \
         "${PROJECT_ROOT}/agent/" "${TEMP_DIR}/agent/" > /dev/null

# 复制 ide-plugin 目录
echo -e "  - 复制 ide-plugin/ 目录..."
rsync -av --exclude='build/' \
         --exclude='.gradle/' \
         --exclude='.idea/' \
         "${PROJECT_ROOT}/ide-plugin/" "${TEMP_DIR}/ide-plugin/" > /dev/null

# 复制 docs 目录
echo -e "  - 复制 docs/ 目录..."
cp -r "${PROJECT_ROOT}/docs" "${TEMP_DIR}/" 2>/dev/null || true

# 创建空的 data 目录结构（用于运行时）
echo -e "  - 创建运行时目录结构..."
mkdir -p "${TEMP_DIR}/agent/data/vector-index"
mkdir -p "${TEMP_DIR}/agent/data/sessions"
mkdir -p "${TEMP_DIR}/agent/data/claude-code-workspaces"
mkdir -p "${TEMP_DIR}/agent/logs"

echo -e "${GREEN}✓ 文件复制完成${NC}"
echo ""

# 4. 创建部署说明文档
echo -e "${YELLOW}[4/5] 创建部署说明文档...${NC}"
cat > "${TEMP_DIR}/DEPLOY.md" << 'EOF'
# SiliconMan 部署指南

## 包内容说明

```
siliconman-src-YYYYMMDD_HHMMSS.zip
├── agent/              # Spring Boot 后端服务（源码）
├── ide-plugin/         # IntelliJ IDEA 插件（源码）
├── docs/               # 项目文档
├── gradle/             # Gradle Wrapper
├── README.md           # 项目说明
├── LICENSE             # 许可证
├── DEPLOY.md           # 本部署指南
├── gradlew             # Unix/Mac 构建脚本
└── gradlew.bat         # Windows 构建脚本
```

## 部署前准备

### 1. 环境要求

#### Agent 后端
- Java 21 或更高版本
- Gradle 8.5+（已包含，无需单独安装）
- 至少 2GB 可用内存
- 端口 8080 可用

#### IDE Plugin
- IntelliJ IDEA 2024.1 或更高版本
- Kotlin 1.9.20+（IDE 内置）

### 2. 可选依赖

如果需要使用完整功能：
- **Claude Code CLI**: [安装指南](https://claude.ai/code)
- **Git**: 用于版本控制集成

## 部署步骤

### 步骤 1: 解压包

```bash
# Linux/Mac
unzip siliconman-src-YYYYMMDD_HHMMSS.zip
cd siliconman-src-YYYYMMDD_HHMMSS

# Windows (PowerShell)
Expand-Archive -Path siliconman-src-YYYYMMDD_HHMMSS.zip -DestinationPath .
cd siliconman-src-YYYYMMDD_HHMMSS
```

### 步骤 2: 配置后端

编辑 `agent/src/main/resources/application.yml`:

```yaml
agent:
  projects:
    your-project-key:
      project-path: /path/to/your/project
      description: "项目描述"
      language: "java"
```

### 步骤 3: 构建后端

```bash
cd agent
../gradlew build
```

构建产物位于: `agent/build/libs/sman-agent-1.0.0.jar`

### 步骤 4: 启动后端服务

```bash
# 开发模式
cd agent
../gradlew bootRun

# 生产模式
java -jar build/libs/sman-agent-1.0.0.jar
```

验证服务是否启动成功:
```bash
curl http://localhost:8080/api/test/health
```

### 步骤 5: 构建 IDE 插件

```bash
cd ide-plugin
../gradlew buildPlugin
```

插件产物位于: `ide-plugin/build/distributions/intellij-siliconman-*.zip`

### 步骤 6: 安装 IDE 插件

1. 打开 IntelliJ IDEA
2. 进入 `File` → `Settings` → `Plugins`
3. 点击齿轮图标 → `Install Plugin from Disk...`
4. 选择步骤 5 生成的 ZIP 文件
5. 重启 IDE

## 运行时目录结构

后端首次运行后会创建以下目录：

```
agent/
├── data/
│   ├── vector-index/          # 向量索引文件
│   ├── sessions/              # 会话数据
│   └── claude-code-workspaces/ # Claude Code 工作空间
└── logs/                      # 日志文件
```

## 配置说明

### 端口配置

默认端口 8080，可在 `application.yml` 中修改：

```yaml
server:
  port: 8080
```

### 项目路径映射

在 `application.yml` 中配置项目路径映射：

```yaml
agent:
  projects:
    autoloop:
      project-path: /absolute/path/to/autoloop
      description: "AutoLoop 项目"
      language: "java"
```

### 日志级别

```yaml
logging:
  level:
    ai.smancode.sman: DEBUG
```

## 健康检查

```bash
# 检查后端状态
curl http://localhost:8080/api/test/health

# 检查 Claude Code 进程池
curl http://localhost:8080/api/claude-code/pool/status
```

## 常见问题

### 1. 端口冲突

**错误**: `Port 8080 is already in use`

**解决**: 修改 `application.yml` 中的端口配置

### 2. Java 版本不匹配

**错误**: `Unsupported class file major version`

**解决**: 安装 Java 21 或更高版本

### 3. 权限问题

**错误**: `Permission denied`

**解决**:
```bash
chmod +x gradlew
```

### 4. 内存不足

**错误**: `Java heap space`

**解决**: 增加 JVM 内存
```bash
java -Xmx2g -jar build/libs/sman-agent-1.0.0.jar
```

## 生产环境建议

1. **使用进程管理工具**: systemd, supervisor 等
2. **配置反向代理**: Nginx, Apache
3. **启用 HTTPS**: 使用 SSL 证书
4. **定期备份**: 备份 `data/` 目录
5. **监控**: 配置日志监控和告警

## 技术支持

- 项目文档: `docs/` 目录
- API 文档: `docs/md/02-websocket-api.md`
- 架构说明: `docs/md/01-architecture.md`

## 版本信息

- 打包时间: TIMESTAMP
- 项目版本: 1.0.0
EOF

echo -e "${GREEN}✓ 部署说明创建完成${NC}"
echo ""

# 5. 打包 ZIP
echo -e "${YELLOW}[5/5] 创建 ZIP 包...${NC}"
cd "${DIST_DIR}"
zip -r "${PACKAGE_NAME}" "temp_package" > /dev/null

# 计算文件大小和哈希
FILE_SIZE=$(du -h "${PACKAGE_PATH}" | cut -f1)
FILE_HASH=$(shasum -a 256 "${PACKAGE_PATH}" | cut -d' ' -f1)

# 清理临时目录
rm -rf "${TEMP_DIR}"

echo -e "${GREEN}✓ ZIP 包创建完成${NC}"
echo ""

# 6. 输出结果
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  打包完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "包文件: ${PACKAGE_PATH}"
echo -e "文件大小: ${FILE_SIZE}"
echo -e "SHA256: ${FILE_HASH}"
echo ""
echo -e "使用方法:"
echo -e "  1. 将 ZIP 包传输到目标服务器"
echo -e "  2. 解压: unzip ${PACKAGE_NAME}"
echo -e "  3. 阅读 DEPLOY.md 了解部署步骤"
echo ""
