#!/bin/bash
#
# SmanAgent 后端源码打包脚本
# 仅打包后端源码和配置文件
#

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 项目信息
PROJECT_NAME="smanagent"
VERSION=$(date +%Y%m%d-%H%M%S)
PACKAGE_NAME="${PROJECT_NAME}-src-${VERSION}"
TEMP_DIR="/tmp/${PACKAGE_NAME}"
OUTPUT_DIR="/Users/liuchao/projects/smanagent/packages"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  SmanAgent 后端源码打包工具${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 1. 创建临时目录
echo -e "${YELLOW}[1/5] 创建临时目录...${NC}"
rm -rf "${TEMP_DIR}"
mkdir -p "${TEMP_DIR}"
mkdir -p "${OUTPUT_DIR}"

# 2. 复制后端源码和配置
echo -e "${YELLOW}[2/5] 复制后端源码...${NC}"

# 使用 rsync 复制源码
rsync -av \
    --exclude='build/' \
    --exclude='.gradle/' \
    --exclude='logs/' \
    --exclude='bin/' \
    --exclude='data/' \
    --exclude='*.pid' \
    --exclude='*.bak' \
    . "${TEMP_DIR}/"

# 确保 gradle-wrapper.jar 存在
mkdir -p "${TEMP_DIR}/gradle/wrapper"
cp gradle/wrapper/gradle-wrapper.jar "${TEMP_DIR}/gradle/wrapper/" 2>/dev/null || true
cp gradle/wrapper/gradle-wrapper.properties "${TEMP_DIR}/gradle/wrapper/" 2>/dev/null || true

# 3. 复制项目文档（从根目录）
echo -e "${YELLOW}[3/5] 复制项目文档...${NC}"
cp ../README.md "${TEMP_DIR}/" 2>/dev/null || true
cp ../CLAUDE.md "${TEMP_DIR}/" 2>/dev/null || true
cp ../ARCHITECTURE.md "${TEMP_DIR}/" 2>/dev/null || true

# 4. 创建部署文档
echo -e "${YELLOW}[4/5] 生成部署文档...${NC}"
cat > "${TEMP_DIR}/DEPLOYMENT.md" << 'EOF'
# SmanAgent 后端部署指南

## 环境要求

- Java 21 或更高版本
- Gradle 8.x（项目已包含 Gradle Wrapper，无需单独安装）
- 至少 2GB 可用内存

## 快速开始

### 1. 解压源码包

将压缩包内容解压到目标目录（注意：压缩包内容会直接解压到当前目录，不会创建子目录）：

```bash
cd /path/to/your/target/dir
unzip smanagent-src-*.zip
```

### 2. 配置环境变量

```bash
# GLM API 密钥（必填）
export GLM_API_KEY="your-api-key-here"

# 项目路径（必填，指定要分析的项目路径）
export PROJECT_PATH="/path/to/your/project"

# 项目标识（可选，默认为 default）
export PROJECT_KEY="default"

# Knowledge 服务地址（可选，默认为 http://localhost:8081）
export KNOWLEDGE_SERVICE_BASE_URL="http://localhost:8081"
```

### 3. 构建项目

```bash
./gradlew clean build -x test
```

### 4. 运行服务

```bash
# 前台运行
./start.sh

# 后台运行
nohup ./start.sh > /dev/null 2>&1 &

# 停止服务
./stop.sh
```

### 5. 验证服务

```bash
curl http://localhost:8080/actuator/health
```

## 常见问题

### Gradle Wrapper 报错 "Invalid or corrupt jarfile"

检查文件是否存在：

```bash
ls -lh gradle/wrapper/gradle-wrapper.jar
# 应该显示约 40-60KB 的文件大小
```

如果文件缺失，从官方下载：

```bash
wget -P gradle/wrapper/ https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar
```
EOF

cat > "${TEMP_DIR}/README.txt" << 'EOF'
====================================
  SmanAgent 后端源码包
====================================

快速开始:
1. 配置环境变量（GLM_API_KEY, PROJECT_PATH）
2. 运行 ./gradlew bootRun 启动服务
3. 访问 http://localhost:8080/actuator/health 验证

环境要求:
- Java 21+
- Gradle 8.x（已包含）
- 2GB+ 内存
====================================
EOF

# 5. 打包
echo -e "${YELLOW}[5/5] 生成 ZIP 压缩包...${NC}"
cd "${TEMP_DIR}"
zip -rq "${PACKAGE_NAME}.zip" .
mv "${PACKAGE_NAME}.zip" "${OUTPUT_DIR}/"
cd - > /dev/null

# 6. 完成和验证
PACKAGE_PATH="${OUTPUT_DIR}/${PACKAGE_NAME}.zip"
PACKAGE_SIZE=$(du -h "${PACKAGE_PATH}" | cut -f1)
FILE_COUNT=$(find "${TEMP_DIR}" -type f | wc -l | tr -d ' ')

# 验证 gradle-wrapper.jar
WRAPPER_JAR_SIZE=$(unzip -p "${PACKAGE_PATH}" gradle/wrapper/gradle-wrapper.jar 2>/dev/null | wc -c | tr -d ' ')

# 清理临时目录
rm -rf "${TEMP_DIR}"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  打包完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "包名: ${YELLOW}${PACKAGE_NAME}.zip${NC}"
echo -e "位置: ${YELLOW}${PACKAGE_PATH}${NC}"
echo -e "大小: ${YELLOW}${PACKAGE_SIZE}${NC}"
echo -e "文件数: ${YELLOW}${FILE_COUNT}${NC}"
echo -e "gradle-wrapper.jar: ${YELLOW}${WRAPPER_JAR_SIZE} bytes${NC}"
echo ""
echo -e "使用方法:"
echo -e "  unzip ${PACKAGE_NAME}.zip"
echo ""
