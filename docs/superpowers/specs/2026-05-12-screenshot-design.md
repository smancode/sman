# 截图功能设计

## 需求

在 ChatInput 输入框增加截图按钮，调用操作系统原生截图工具完成选区截图。

### 核心交互

1. 剪刀图标（开口朝上）+ 小下拉箭头
2. 点击剪刀 → 立即开始截图
3. 下拉菜单 → "隐藏窗口截图"（先隐藏 Sman 窗口再截图）
4. 截图完成后：图片进 StagedMedia 预览 + 复制到系统剪贴板
5. 用户在任意位置可粘贴截图

### 技术方案

**macOS**: `screencapture -i <tmpfile>`
- `-i` = 交互式选区（用户框选区域）
- 截完写入临时 PNG 文件
- 用 Electron `clipboard.writeImage()` 写入剪贴板
- 读文件为 base64 → 发送给渲染进程 → StagedMedia

**Windows**: `SnippingTool.exe` 或 PowerShell 调 Win32 API
- Windows 11: 直接启动 SnippingTool.exe（系统自带截图工具）
- 截图结果在剪贴板中，用 Electron `clipboard.readImage()` 读取
- 需要等待截图完成（轮询剪贴板或监听）

### 涉及文件

| 文件 | 改动 |
|------|------|
| `electron/main.ts` | 添加 IPC handler `screen:startCapture`，用 execFile 调系统命令 |
| `electron/preload.ts` | 简化截图 API：`startCapture` + `onCaptureResult` |
| `src/features/chat/ChatInput.tsx` | 截图按钮 UI（已实现，不需要改） |
| `src/locales/zh-CN.json` | 翻译 key（已存在） |
| `src/locales/en-US.json` | 翻译 key（已存在） |

### 代码约束

- 所有用户可见文本用 `t()` i18n
- main.ts 不超过 500 行
- 不需要 `desktopCapturer` 权限
- 不需要 overlay 窗口
- 不需要 Screen Recording 权限
