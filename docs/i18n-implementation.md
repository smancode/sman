# 国际化 (i18n) 实现文档

## 实现概述

为 Sman 添加完整的中英文双语支持，用户可在设置页面实时切换语言，无需重启应用。

**实现日期**: 2026-05-07  
**语言支持**: 简体中文 (zh-CN)、English (en-US)

---

## 核心功能

### 1. 实时语言切换
- 用户在设置页选择语言后立即生效
- 使用 Zustand store + WebSocket 持久化
- UI 文本通过 `t()` 函数动态获取

### 2. 自动语言检测
- 首次安装时根据系统环境变量自动选择：
  - 中文系统 → zh-CN
  - 其他系统 → en-US
- 检测逻辑：`LANG`/`LC_ALL` 环境变量

### 3. LLM 语言注入
- 用户消息自动添加语言提示（非 system prompt）
- zh-CN: `[请用中文回复]`
- en-US: `[Please respond in English]`
- 实现：`src/stores/chat.ts` 的 `sendMessage()` 方法

---

## 技术架构

### 翻译系统设计

```
UI 占位符 (t('key')) 
    ↓
JSON 映射 (zh-CN.json / en-US.json)
    ↓
内存存储 (currentLocale)
    ↓
实时切换 (无刷新)
```

### 核心文件

| 文件 | 用途 |
|------|------|
| `src/locales/index.ts` | 翻译函数实现 |
| `src/locales/zh-CN.json` | 中文翻译 |
| `src/locales/en-US.json` | 英文翻译 |
| `src/hooks/useLanguage.ts` | 语言切换 Hook |
| `src/stores/settings.ts` | 语言持久化 |
| `server/settings-manager.ts` | 后端语言检测 |

### 翻译键组织

每个翻译键包含：
```json
{
  "key.path": {
    "text": "显示文本",
    "context": "上下文说明（确保翻译准确）"
  }
}
```

---

## 已完成组件

### 核心页面
- ✅ `src/app/App.tsx` - 连接状态提示
- ✅ `src/components/layout/Sidebar.tsx` - 侧边栏菜单
- ✅ `src/components/SessionTree.tsx` - 会话列表
- ✅ `src/features/chat/ChatInput.tsx` - 聊天输入框

### 设置页面
- ✅ `src/features/settings/index.tsx` - 设置页导航
- ✅ `src/features/settings/LLMSettings.tsx` - 模型配置
- ✅ `src/features/settings/WebSearchSettings.tsx` - 网络搜索
- ✅ `src/features/settings/ChatbotSettings.tsx` - Bot 机器人
- ✅ `src/features/settings/BackendSettings.tsx` - 后端连接
- ✅ `src/features/settings/LanguageSettings.tsx` - 语言设置

### 翻译文件
- ✅ 约 200+ 个翻译键
- ✅ 覆盖所有主要 UI 文本
- ✅ 包含 Git 和 CodeViewer 翻译键（组件未替换）

---

## 未完成工作

### Git 页面
**文件**: `src/features/git/GitPanel.tsx`  
**硬编码数量**: 约 40 处  
**示例**:
- "本地变更" → `t('git.localChanges')`
- "工作区干净" → `t('git.clean')`
- "加载差异..." → `t('git.loadingDiff')`

### CodeViewer 页面
**文件**:
- `src/features/code-viewer/CodePanel.tsx` (约 30 处)
- `src/features/code-viewer/FileTree.tsx` (约 10 处)
- `src/features/code-viewer/CodeNavigator.tsx` (约 5 处)

**示例**:
- "加载文件中..." → `t('codeviewer.loading')`
- "保存" → `t('codeviewer.save')`
- "搜索文件..." → `t('codeviewer.searchFiles')`

### 其他页面
- Cron 任务页面
- Stardom 页面
- Smart Paths 页面

---

## 类型定义变更

### 前端
```typescript
// src/types/settings.ts
export interface SmanSettings {
  // ...
  language: string;  // 新增
}
```

### 后端
```typescript
// server/types.ts
export interface SmanConfig {
  // ...
  language: string;  // 新增
}
```

---

## 遇到的问题与解决

### 问题 1: 翻译文件损坏
**现象**: JSON 语法错误，编译失败  
**原因**: 手动追加 JSON 格式错误  
**解决**: 从 en-US.json 重建 zh-CN.json，保留必要翻译键

### 问题 2: 常量硬编码中文
**现象**: `WEB_SEARCH_PROVIDER_OPTIONS` 常量包含中文  
**解决**: 在组件中动态映射，不使用常量的 label/description

### 问题 3: 翻译文本不当
**现象**: "Interface Language" 翻译成"接口语言"  
**解决**: 改为简洁的"Language"

---

## 使用方法

### 添加新翻译

1. 在 `zh-CN.json` 和 `en-US.json` 添加键：
```json
{
  "module.feature.label": {
    "text": "功能名称",
    "context": "上下文说明"
  }
}
```

2. 在组件中使用：
```tsx
import { t } from '@/locales';

<span>{t('module.feature.label')}</span>
```

### 切换语言

```typescript
import { useSettingsStore } from '@/stores/settings';

const { updateLanguage } = useSettingsStore();
updateLanguage('en-US'); // 立即生效
```

---

## 构建验证

```bash
# 编译检查
pnpm build

# 预期输出
✓ built in X.XXs
```

---

## 后续优化建议

1. **完成剩余组件**: Git、CodeViewer、Cron 等
2. **翻译质量审校**: 确保 en-US 翻译自然
3. **提取硬编码字符串**: 全局搜索 `[\u4e00-\u9fa5]+` 正则
4. **单元测试**: 为翻译函数添加测试
5. **性能优化**: 考虑按需加载翻译文件

---

## 参考文档

- [i18n 最佳实践](https://www.i18next.com/principles/best-practices)
- [React 国际化](https://react.i18next.com/)
