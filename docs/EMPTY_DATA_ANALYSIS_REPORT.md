# 项目分析数据为空问题报告

**问题时间**: 2026-02-01 01:25
**严重程度**: 🔴 严重 - 核心功能完全失效

## 问题描述

点击 IDEA 中的"项目分析"后，虽然显示"分析完成"，但**除 `project_structure` 外的所有分析结果都是空数据**。

## 测试结果

### 实际 API 返回数据

| 模块 | 返回数据 | 状态 |
|------|----------|------|
| project_structure | `{"totalFiles":129,"totalLines":12831}` | ✅ 有数据 |
| tech_stack_detection | `{"frameworks":[],"languages":[],"databases":[]}` | ❌ 全空 |
| ast_scanning | `{"classes":[],"methods":[]}` | ❌ 全空 |
| db_entity_detection | `{"entities":[],"tables":[],"count":0}` | ❌ 全空 |
| api_entry_scanning | `{"controllers":[],"count":0}` | ❌ 全空 |
| external_api_scanning | `{"externalApis":[],"count":0}` | ❌ 全空 |
| enum_scanning | `{"enums":[],"constants":[],"count":0}` | ❌ 全空 |
| common_class_scanning | `[]` | ❌ 全空 |
| xml_code_scanning | `[]` | ❌ 全空 |

### autoloop 项目实际结构

```
autoloop/
├── src/main/                    # 根模块（只有配置，无源代码）
├── loan/src/main/java/com/autoloop/loan/  # ⭐ 实际源代码在这里
│   ├── config/
│   ├── handler/                  # RepayHandler, DisburseHandler 等
│   ├── integration/              # TransferService 等
│   ├── mapper/                   # AcctLoanMapper, AcctRepaymentMapper 等
│   ├── model/
│   ├── procedure/
│   └── service/
├── core/src/main/java/
├── common/src/main/java/
├── integration/src/main/java/
└── ddl/src/main/java/
```

**总计**: 129 个文件，12831 行代码（全部在子模块中）

## 根本原因

**所有分析扫描器都没有正确处理 Gradle 多模块项目**。

### 扫描器的错误逻辑

以 `ProjectStructureScanner` 为例：

```kotlin
// ❌ 错误：只扫描根目录的 src/main/java
private fun detectPackages(projectPath: Path): List<PackageInfo> {
    val srcMainKotlin = projectPath.resolve("src/main/kotlin")
    val srcMainJava = projectPath.resolve("src/main/java")

    val srcDirs = listOfNotNull(
        if (srcMainKotlin.toFile().exists()) srcMainKotlin else null,
        if (srcMainJava.toFile().exists()) srcMainJava else null
    )
    // ...
}
```

**问题**：
- 只检查 `projectPath/src/main/java`
- 没有检查 `projectPath/loan/src/main/java`
- 没有检查 `projectPath/core/src/main/java`
- 没有动态发现 Gradle 子模块

### 受影响的扫描器

1. `ProjectStructureScanner` - 只扫描根目录
2. `PsiAstScanner` - 可能基于 ProjectStructure 的结果
3. `TechStackDetector` - 只扫描根目录的 build.gradle
4. `DbEntityScanner` - 只扫描根目录源码
5. `ApiEntryScanner` - 只扫描根目录源码
6. `ExternalApiScanner` - 只扫描根目录源码
7. `EnumScanner` - 只扫描根目录源码
8. `CommonClassScanner` - 只扫描根目录源码
9. `XmlCodeScanner` - 可能只扫描根目录

## 解决方案

### 方案 1: 修复模块发现逻辑（推荐）

**修改 `ProjectStructureScanner`**：

```kotlin
private fun detectModules(projectPath: Path): List<ModuleInfo> {
    val modules = mutableListOf<ModuleInfo>()

    // 1. 检查根目录是否有源代码
    val rootSrc = projectPath.resolve("src/main/java")
    if (rootSrc.toFile().exists()) {
        modules.add(ModuleInfo(name = "root", type = ModuleType.GRADLE, path = projectPath.toString()))
    }

    // 2. 扫描子模块（loan, core, common 等）
    Files.list(projectPath)
        .filter { it.toFile().isDirectory }
        .filter { moduleDir ->
            // 检查是否是 Gradle 子模块
            val hasBuildFile = moduleDir.resolve("build.gradle").toFile().exists() ||
                             moduleDir.resolve("build.gradle.kts").toFile().exists()
            val hasSrcDir = moduleDir.resolve("src/main/java").toFile().exists() ||
                           moduleDir.resolve("src/main/kotlin").toFile().exists()
            hasBuildFile && hasSrcDir
        }
        .forEach { moduleDir ->
            modules.add(ModuleInfo(
                name = moduleDir.fileName.toString(),
                type = ModuleType.GRADLE,
                path = moduleDir.toString()
            ))
        }

    return modules
}
```

### 方案 2: 修改所有扫描器支持多模块

每个扫描器都需要：
1. 遍历所有发现的模块（包括子模块）
2. 扫描每个模块的源代码目录
3. 合并结果

### 方案 3: 使用 Gradle Tooling API（最彻底）

使用 Gradle Tooling API 获取项目的模块结构，但实现复杂度较高。

## 需要修复的文件

1. `src/main/kotlin/com/smancode/smanagent/analysis/structure/ProjectStructureScanner.kt`
2. `src/main/kotlin/com/smancode/smanagent/analysis/techstack/TechStackDetector.kt`
3. `src/main/kotlin/com/smancode/smanagent/analysis/scanner/PsiAstScanner.kt`
4. `src/main/kotlin/com/smancode/smanagent/analysis/entity/DbEntityScanner.kt`
5. `src/main/kotlin/com/smancode/smanagent/analysis/entrance/ApiEntryScanner.kt`
6. `src/main/kotlin/com/smancode/smanagent/analysis/external/ExternalApiScanner.kt`
7. `src/main/kotlin/com/smancode/smanagent/analysis/enum/EnumScanner.kt`
8. `src/main/kotlin/com/smancode/smanagent/analysis/common/CommonClassScanner.kt`
9. `src/main/kotlin/com/smancode/smanagent/analysis/xml/XmlCodeScanner.kt`

## 临时测试方法

要验证修复是否有效，可以：

```bash
# 手动测试扫描器能否找到子模块的代码
./gradlew runIde

# 在 IDEA 中点击"项目分析"
# 然后调用 API 检查结果：
curl -X POST 'http://localhost:8080/api/verify/analysis_results' \
  -H 'Content-Type: application/json' \
  -d '{"module":"ast_scanning","projectKey":"autoloop","page":0,"size":10}'

# 期望看到非空的 classes 和 methods 数据
```

## 总结

**问题严重性**: 🔴 严重

**影响范围**: 所有分析功能（除项目结构外）完全失效

**修复优先级**: P0 - 必须立即修复

**预估工作量**:
- 方案 1: 2-4 小时
- 方案 2: 4-8 小时
- 方案 3: 8-16 小时

**建议**: 先实施方案 1 修复 `ProjectStructureScanner`，然后逐步修复其他扫描器。
