# Gradle Wrapper 说明

## 什么是 Gradle Wrapper？

Gradle Wrapper 是 Gradle 的一个脚本，它允许你在没有安装 Gradle 的机器上运行 Gradle 构建。

## 为什么使用 Gradle Wrapper？

1. **版本一致性**: 确保所有开发者使用相同的 Gradle 版本（8.5）
2. **无需预装**: 不需要事先安装 Gradle
3. **跨平台**: 自动适配操作系统（Windows/Linux/macOS）
4. **最佳实践**: 这是 Gradle 官方推荐的项目构建方式

## 文件说明

### 提交到 Git 的文件：
- ✅ `gradlew` - Unix/Linux/macOS 脚本
- ✅ `gradlew.bat` - Windows 脚本
- ✅ `gradle/wrapper/gradle-wrapper.properties` - Wrapper 配置

### 不提交到 Git 的文件（在 .gitignore 中）：
- ❌ `gradle/wrapper/gradle-wrapper.jar` - 会自动下载

## 使用方法

### Linux/macOS:
```bash
./gradlew buildPlugin
./gradlew runIde
./gradlew test
```

### Windows:
```bash
gradlew.bat buildPlugin
gradlew.bat runIde
gradlew.bat test
```

## 首次使用

首次运行时，Wrapper 会自动下载 Gradle 8.5 到：
- **Linux/macOS**: `~/.gradle/wrapper/dists/gradle-8.5-bin/`
- **Windows**: `C:\Users\<用户名>\.gradle\wrapper\dists\gradle-8.5-bin\`

## 验证安装

```bash
# 检查 Wrapper 版本
./gradlew --version

# 输出应该显示：
# Gradle 8.5
```

## 常见问题

### Q: 为什么要用 `./gradlew` 而不是 `gradle`？
A: `./gradlew` 使用项目指定的 Gradle 版本（8.5），而 `gradle` 使用系统安装的版本，可能不一致。

### Q: 可以同时使用吗？
A: 可以，但推荐使用 `./gradlew` 以确保构建的一致性。

### Q: Wrapper 下载失败怎么办？
A: 检查网络连接，或手动下载 Gradle 8.5 并放到 `~/.gradle/wrapper/dists/` 目录。

### Q: 如何更新 Gradle 版本？
A: 修改 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl`，然后运行 `./gradlew wrapper --gradle-version=<新版本>`。

## 相关链接

- [Gradle Wrapper 官方文档](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
- [Gradle 8.5 发布说明](https://gradle.org/release/2023/11/06/gradle-85-is-released)
