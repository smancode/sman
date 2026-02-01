#!/bin/bash

##############################################################################
# 分析 autoloop 项目并验证结果
#
# 功能：
#   1. 执行完整的项目分析（9 个步骤）
#   2. 验证每个步骤的分析结果
#   3. 测试向量化和语义搜索
#
# 前置条件：
#   1. BGE 服务运行在 http://localhost:8000
#   2. Reranker 服务运行在 http://localhost:8001
#   3. autoloop 项目位于 ../autoloop
##############################################################################

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# 项目路径
AUTOLOOP_PROJECT="../autoloop"
PROJECT_KEY="autoloop"
VECTOR_DB_DIR="$HOME/.smanunion/$PROJECT_KEY"

# 检查前置条件
check_prerequisites() {
    log_step "检查前置条件..."

    # 检查 autoloop 项目
    if [ ! -d "$AUTOLOOP_PROJECT" ]; then
        log_error "autoloop 项目不存在: $AUTOLOOP_PROJECT"
        exit 1
    fi
    log_info "✓ autoloop 项目存在"

    # 检查 BGE 服务
    if ! curl -s http://localhost:8000/health >/dev/null 2>&1; then
        log_error "BGE Embedding 服务未运行"
        exit 1
    fi
    log_info "✓ BGE Embedding 服务运行中"

    # 检查 Reranker 服务
    if ! curl -s http://localhost:8001/health >/dev/null 2>&1; then
        log_error "BGE Reranker 服务未运行"
        exit 1
    fi
    log_info "✓ BGE Reranker 服务运行中"

    echo ""
}

# 清理旧数据
cleanup_old_data() {
    log_step "清理旧分析数据..."

    if [ -d "$VECTOR_DB_DIR" ]; then
        rm -rf "$VECTOR_DB_DIR"
        log_info "✓ 已清理旧向量数据库"
    fi

    echo ""
}

# 运行项目分析
run_analysis() {
    log_step "运行项目分析..."

    # TODO: 这里需要在 IDEA 中运行，暂时跳过
    log_warn "项目分析需要在 IDEA 中手动运行"
    log_info "请按以下步骤操作："
    log_info "  1. 在 IDEA 中打开 autoloop 项目"
    log_info "  2. 点击 '项目分析' 按钮"
    log_info "  3. 等待分析完成"

    echo ""
}

# 验证分析结果
verify_analysis_results() {
    log_step "验证分析结果..."

    local db_file="$VECTOR_DB_DIR/analysis.mv.db"

    if [ ! -f "$db_file" ]; then
        log_error "分析数据库不存在: $db_file"
        log_info "请先在 IDEA 中运行项目分析"
        exit 1
    fi

    log_info "✓ 分析数据库存在"

    # 使用 H2 Shell 查询分析结果
    # 这里我们直接使用 SQL 查询

    echo ""
}

# 测试向量化和搜索
test_vectorization_and_search() {
    log_step "测试向量化和语义搜索..."

    # TODO: 启动验证服务后测试
    log_warn "语义搜索测试需要 VerificationWebService 运行"

    echo ""
}

# 生成测试报告
generate_report() {
    log_step "生成测试报告..."

    local report_file="docs/ANALYSIS_VERIFICATION_REPORT_$(date +%Y%m%d_%H%M%S).md"

    cat > "$report_file" <<EOF
# Autoloop 项目分析验证报告

**测试时间**: $(date '+%Y-%m-%d %H:%M:%S')
**项目路径**: $AUTOLOOP_PROJECT
**项目 Key**: $PROJECT_KEY

## 前置条件检查

| 检查项 | 状态 |
|--------|------|
| autoloop 项目存在 | ✅ |
| BGE 服务 (8000) | ✅ |
| Reranker 服务 (8001) | ✅ |

## 分析步骤验证

| 步骤 | 描述 | 状态 |
|------|------|------|
| 1 | 项目结构扫描 | ⏳ 待验证 |
| 2 | 技术栈检测 | ⏳ 待验证 |
| 3 | AST 扫描 | ⏳ 待验证 |
| 4 | 数据库实体检测 | ⏳ 待验证 |
| 5 | API 入口扫描 | ⏳ 待验证 |
| 6 | 外调接口扫描 | ⏳ 待验证 |
| 7 | 枚举扫描 | ⏳ 待验证 |
| 8 | 公共类扫描 | ⏳ 待验证 |
| 9 | XML 代码扫描 | ⏳ 待验证 |

## 向量化验证

| 检查项 | 状态 |
|--------|------|
| 向量数据库 | ⏳ 待验证 |
| 向量索引 | ⏳ 待验证 |

## 语义搜索测试

| 查询 | 预期结果 | 实际结果 | 状态 |
|------|---------|---------|------|
| "还款入口是哪个" | 找到 RepaymentController | - | ⏳ 待测试 |
| "用户登录验证" | 找到 LoginController | - | ⏳ 待测试 |
| "放款流程和入口" | 找到放款相关接口 | - | ⏳ 待测试 |

## 手动验证步骤

由于项目分析需要在 IntelliJ IDEA 中运行，请按以下步骤验证：

### 1. 在 IDEA 中运行项目分析

\`\`\`bash
# 1. 打开 IDEA
# 2. 打开 autoloop 项目
# 3. 点击 "项目分析" 按钮
# 4. 等待 9 个步骤全部完成
\`\`\`

### 2. 验证分析结果

\`\`\`bash
# 连接到 H2 数据库
java -cp ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar org.h2.tools.Shell \\
    -url "jdbc:h2:$HOME/.smanunion/autoloop/analysis" \\
    -user sa \\
    -password ""

# 查询分析结果
SELECT * FROM project_analysis WHERE project_key = 'autoloop';

# 查询向量片段
SELECT COUNT(*) FROM vector_fragments WHERE project_key = 'autoloop';
\`\`\`

### 3. 启动验证服务

\`\`\`bash
# 在 IDEA 中启动 VerificationWebService
# 主类: com.smancode.smanagent.verification.VerificationWebService
# VM options: -Dserver.port=8080
\`\`\`

### 4. 运行 HTTP 测试

打开 \`http/rest.http\`，执行以下测试：

\`\`\`http
# 健康检查
GET http://localhost:8080/actuator/health

# 查询 API 入口
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "api_entries",
  "projectKey": "autoloop"
}

# 语义搜索：还款入口
POST http://localhost:8080/api/verify/semantic_search
Content-Type: application/json

{
  "query": "还款入口是哪个",
  "projectKey": "autoloop",
  "topK": 10,
  "enableRerank": true
}
\`\`\`

## 总结

⏳ **待完成**: 请在 IDEA 中手动运行项目分析和验证服务

EOF

    log_info "✓ 报告已生成: $report_file"
    echo ""
}

# 主函数
main() {
    cd "$(dirname "$0")/.."

    echo "=========================================="
    echo "  Autoloop 项目分析验证"
    echo "=========================================="
    echo ""

    # 检查前置条件
    check_prerequisites

    # 清理旧数据
    cleanup_old_data

    # 运行分析（需要手动）
    run_analysis

    # 验证结果
    verify_analysis_results

    # 测试向量化和搜索
    test_vectorization_and_search

    # 生成报告
    generate_report

    echo "=========================================="
    log_info "验证完成！"
    echo "=========================================="
}

main "$@"
