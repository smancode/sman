# Web Access — 企业网站操作能力设计

> 日期：2026-03-30
> 状态：已确认，待实施

## 1. 背景与目标

Sman 需要操作企业内部的 DevOps 网站（Jira、Confluence、GitLab、Jenkins 等），融入已有的企业工作流。

**核心价值**：用户在聊天中说"帮我创建个 Jira 需求"，AI 自动完成网页操作。

**设计原则**：
- 优先复用用户已有登录态（不碰密码）
- Agent 不感知底层浏览器引擎差异
- 聊天自然语言触发，结果在聊天中反馈

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    Sman Electron App                     │
│                                                          │
│  Frontend (React SPA)                                    │
│    └── 聊天界面：用户说 "帮我创建个 Jira 需求"             │
│         ↓ WebSocket chat.send                            │
│                                                          │
│  Server (Node.js)                                        │
│    └── Claude Agent SDK                                  │
│         └── MCP Tool: web_access_*                       │
│              ↓ (进程内函数调用)                            │
│         WebAccessService (统一服务层)                     │
│              ├── CdpEngine (优先) ← 直连用户 Chrome       │
│              └── EmbeddedEngine (兜底) ← BrowserView     │
│                                                          │
│  结果通过 chat.delta / chat.done 流式返回给前端            │
└─────────────────────────────────────────────────────────┘
```

### 三层设计

| 层 | 职责 | 关键文件 |
|---|------|---------|
| **MCP Tool 层** | 暴露给 Claude Agent 的工具接口 | `mcp-server.ts` |
| **服务层** | 引擎选择、会话管理、Cookie 来源决策 | `web-access-service.ts` |
| **引擎层** | 实际的浏览器操作执行 | `cdp-engine.ts` / `embedded-engine.ts` |

## 3. 双引擎设计

### 3.1 引擎选择策略

| 场景 | 引擎 | 登录态来源 |
|------|------|-----------|
| 桌面端（BYOD） | CDP 优先，Embedded 兜底 | CDP: 用户 Chrome；Embedded: Sman 内登录 |
| 桌面端（VDI） | Embedded | Sman 内登录 |
| 企业微信 | 仅 CDP | 用户电脑 Chrome 必须在线 |

**启动时探测**：

```
1. 尝试 CDP 连接
   ├── 成功 → activeEngine = CdpEngine
   └── 失败 → 检查是否在 Electron 内
              ├── 是 → activeEngine = EmbeddedEngine
              └── 否 → activeEngine = null
                   企业微信：提示用户开启 Chrome 远程调试
```

### 3.2 BrowserEngine 统一接口

```typescript
interface BrowserEngine {
  isAvailable(): Promise<boolean>
  newTab(url: string): Promise<string>
  navigate(tabId: string, url: string): Promise<void>
  evaluate(tabId: string, js: string): Promise<any>
  click(tabId: string, selector: string): Promise<void>
  fill(tabId: string, selector: string, value: string): Promise<void>
  screenshot(tabId: string): Promise<Buffer>
  closeTab(tabId: string): Promise<void>
}
```

### 3.3 CdpEngine — 直连用户 Chrome

从 web-access 项目借鉴 CDP 直连思路：

```
CdpEngine
  ├── discoverChrome()    — 读 DevToolsActivePort 或扫描 9222/9229/9333
  ├── connect()           — WebSocket 连接 Chrome CDP
  ├── newTab(url)         — Target.createTarget（后台 tab）
  ├── navigate(tabId, url) — Page.navigate
  ├── evaluate(tabId, js) — Runtime.evaluate
  ├── click(tabId, sel)   — JS el.click()，反自动化检测时降级 Input.dispatchMouseEvent
  ├── fill(tabId, sel, v) — JS input.value + dispatchEvent
  ├── screenshot(tabId)   — Page.captureScreenshot
  ├── closeTab(tabId)     — Target.closeTarget
  └── isConnected()       — 心跳检测
```

**核心优势**：天然复用用户 Chrome 已有的所有 Cookie/Session。

**前置条件**：用户需开启 Chrome 远程调试。首次使用时 Agent 通过文字提示引导。

**企业微信场景**：仅 CDP 可用。Chrome 未开启远程调试 → 返回错误，Agent 告知用户。

### 3.4 EmbeddedEngine — Electron 内嵌浏览器

从 hello-halo 借鉴 BrowserView + session partition：

```
EmbeddedEngine
  ├── init()              — 创建隐藏 BrowserWindow
  │     session partition: 'persist:web-access'
  ├── newTab(url)         — createBrowserView
  ├── navigate(viewId, url)
  ├── evaluate(viewId, js)
  ├── click(viewId, selector)
  ├── fill(viewId, selector, value)
  ├── screenshot(viewId)  — webContents.capturePage()
  ├── closeTab(viewId)
  └── openLoginWindow(url) — 弹出可见登录窗口（共享 persist:web-access）
```

**仅在 Electron 桌面端可用**。WeCom 纯后端场景无法使用。

**登录流程**：检测到需要登录时，弹出共享 session 的登录窗口，用户手动登录后 Cookie 自动同步到 AI 操作用的隐藏窗口。

## 4. MCP Tool 定义

进程内 MCP Server，直接注册给 Claude Agent SDK（非 stdio 外部进程）。

### 工具列表

```typescript
// 核心导航
web_access_navigate      // 打开/跳转网页 → { title, url, snapshot }
web_access_snapshot      // 获取页面可访问性树（文本、按钮、链接等）
web_access_screenshot    // 截图 → base64 image

// 交互操作
web_access_click         // 点击元素（selector 或文本匹配）
web_access_fill          // 填写表单字段
web_access_select        // 选择下拉框选项
web_access_press_key     // 按键（Enter、Tab 等）

// 执行
web_access_evaluate      // 执行自定义 JavaScript

// 标签管理
web_access_list_tabs     // 列出当前打开的标签
web_access_close_tab     // 关闭标签
```

### 未登录检测

当 snapshot 返回的内容是登录页（检测登录表单、SSO 跳转），MCP Tool 返回：

```json
{
  "error": "LOGIN_REQUIRED",
  "message": "该网站需要登录，请在 Sman 内嵌浏览器中登录（桌面端）",
  "loginUrl": "https://jira.example.com/login"
}
```

Agent 收到后用自然语言告知用户。用户登录后 Agent 重试。

### 密码处理

**仅通过提示词控制**：Agent 系统提示中明确禁止处理密码字段。

- 用户在聊天中提供密码 → Agent 拒绝并引导手动登录
- 不做代码层硬拦截

## 5. WebAccessService 服务层

```
WebAccessService
  ├── 引擎生命周期
  │     detectEngine()        — 启动时探测
  │     switchEngine(type)    — 手动切换
  │     getActiveEngine()     — 返回当前引擎
  │
  ├── 会话管理
  │     tabs: Map<tabId, TabContext>
  │     createSession()       — 创建浏览器会话上下文
  │     closeSession()        — 关闭所有 tab，清理资源
  │
  ├── 操作执行（被 MCP Tool 调用）
  │     navigate(url) → { title, url, snapshot }
  │     snapshot() → AccessibilityTree
  │     screenshot() → base64 image
  │     click(selector) → { snapshot }
  │     fill(selector, value) → { snapshot }
  │     evaluate(js) → { result }
  │
  └── 登录检测
        detectLoginRequired(snapshot) → boolean
```

## 6. 文件结构

```
smanbase/
  server/
    web-access/                    ← 新增模块
      web-access-service.ts        — 服务层（引擎选择、会话管理）
      browser-engine.ts            — BrowserEngine 接口定义
      cdp-engine.ts                — CDP 引擎实现
      embedded-engine.ts           — Electron 内嵌引擎实现
      mcp-server.ts                — MCP Server（注册 web_access_* 工具）
    claude-session.ts              — 修改：注入 web-access MCP Server
    index.ts                       — 修改：启动 WebAccessService
```

### 前端改动

- 聊天消息支持渲染截图（`chat.delta` 中新增 `image` 类型内容块，展示 base64 截图）
- 不新增浏览器展示组件，不新增 WebSocket 消息类型

### 不修改的部分

- Electron 主进程（单窗口策略不变）
- WebSocket 协议基础框架不变

## 7. 分阶段实施路线

| 阶段 | 内容 | 覆盖场景 |
|------|------|---------|
| **P0** | CdpEngine + MCP Server | BYOD 桌面端（最快见效） |
| **P1** | EmbeddedEngine | VDI 桌面端 |
| **P2** | 站点经验机制 | 效率优化（从 web-access 的 site-patterns 借鉴） |

## 8. 参考项目

| 项目 | 借鉴点 |
|------|--------|
| **web-access** (`../web-access`) | CDP 直连 Chrome 的模式、cdp-proxy 的端口发现和连接逻辑 |
| **hello-halo** (`../hello-halo`) | BrowserView 管理、session partition、AI Browser MCP Server、反指纹 |
