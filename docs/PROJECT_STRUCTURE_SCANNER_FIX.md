# ProjectStructureScanner 修复完成

## 修复内容

### 问题
原代码只扫描根目录的 `src/main/java`，无法扫描子模块（如 `loan/src/main/java`）

### 修复方案
1. **递归查找所有 src 目录**：`findAllSrcDirectories()`
2. **扫描所有发现的 src 目录**：`detectPackages()`, `detectLayers()`
3. **自动检测子模块**：根据 src 目录的父目录推断模块

### 核心变更
```kotlin
// 修复前：只扫描根目录
val srcMainJava = projectPath.resolve("src/main/java")

// 修复后：递归查找所有 src 目录
val allSrcDirs = findAllSrcDirectories(projectPath)
// 然后扫描所有 src 目录下的源代码
```

## 测试步骤

1. 在 IDEA 中重新运行 `./gradlew runIde`
2. 点击"项目分析"按钮
3. 等待分析完成
4. 调用 API 验证结果：

```bash
curl -X POST 'http://localhost:8080/api/verify/analysis_results' \
  -H 'Content-Type: application/json' \
  -d '{"module":"project_structure","projectKey":"autoloop","page":0,"size":10}'
```

## 期望结果

修复后应该能看到：
- 更多模块（loan, core, common, integration, ddl）
- 更多包结构
- 更准确的文件和行数统计

## 日志

启动后应该看到类似日志：
```
发现 5 个源代码目录: [src, loan/src, core/src, common/src, integration/src]
```
