# 会话切换即时响应 — 性能优化设计

> 日期：2026-04-11

## 核心定位

消除 sman 桌面端切换聊天会话时的等待体验，实现"即时切换"：点击即展示，后台静默同步。

## 问题分析

### 当前链路（切换一次会话）

```
用户点击会话 → switchSession() 清空 messages[]
              → set({ loading: true }) 显示转圈
              → 发 session.history WebSocket 请求
              → 等待远程后端响应（RTT 50-500ms + 数据传输）
              → 收到 1000 条消息
              → messages.map() 全量渲染
              → 每条消息创建 Streamdown 实例（Markdown 解析）
              → Shiki 代码高亮（异步但不流畅）
              → 总耗时：0.5s - 3s+
```

### 三个瓶颈

1. **网络等待**：每次切换都请求远端，无缓存
2. **全量渲染**：1000 条消息全部 DOM 化 + Markdown 解析，主线程阻塞 1-2s
3. **无预加载**：访问过的会话没有保留在前端

---

## 架构

```
┌──────────────────────────────────────────────────┐
│  Chat Component (index.tsx)                      │
│  ┌────────────────────────────────────────────┐  │
│  │ Virtuoso (仅管理历史消息的虚拟滚动)          │  │
│  │  ┌──────────────────────────────────────┐  │  │
│  │  │ ChatMessage (content-visibility:auto)│  │  │
│  │  │  - 视口内: Streamdown 完整渲染        │  │  │
│  │  │  - 视口外: 跳过布局/渲染              │  │  │
│  │  └──────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────┐  │
│  │ StreamingBlocksRenderer (Virtuoso 外部)     │  │
│  │  - 实时流式内容，始终挂载                    │  │
│  └────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────┐  │
│  │ 同步状态指示器                               │  │
│  │  "已同步 ✓" / "同步中..." / "离线缓存"       │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│  SessionCache (src/lib/session-cache.ts)          │
│  - Map<sessionId, { messages, syncedAt }>         │
│  - 独立于 Zustand store，可单独测试               │
│  - 会话删除时清理对应条目                         │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│  Chat Store (src/stores/chat.ts) — 改造           │
│  switchSession:                                   │
│    1. 冻结 streamingBlocks → 存入 cache           │
│    2. 从 cache 读取新会话消息                     │
│    3. 立即 set({ messages: cached })              │
│    4. 后台 loadHistory() 静默同步                 │
└──────────────────────────────────────────────────┘
```

---

## 关键机制设计

### 1. 消息缓存层 — SessionCache

**文件**：`src/lib/session-cache.ts`（新建）

```typescript
interface CacheEntry {
  messages: Message[];
  syncedAt: number; // Date.now()
}

class SessionCache {
  private cache = new Map<string, CacheEntry>();

  get(sessionId: string): Message[] | null;
  set(sessionId: string, messages: Message[]): void;
  has(sessionId: string): boolean;
  invalidate(sessionId: string): void;
  getSyncAge(sessionId: string): number | null; // ms since last sync
}
```

**设计决策**：
- 独立类，不在 Zustand store 里，避免 store 职责过重（chat.ts 已 635 行）
- `syncedAt` 用于计算缓存新鲜度，驱动 UI 指示器
- 不设 LRU 上限（聊天消息文本为主，实际 < 50MB），但 base64 图片只缓存引用不缓存 data
- 会话删除时 `invalidate()` 清理

### 2. 切换会话流程改造

**改造 `switchSession`**：

```
switchSession(targetId):
  // Step 1: 保存当前会话到缓存
  if streamingBlocks 非空:
    frozen = freezeLiveText(streamingBlocks)
    将 frozen 追加到 messages 末尾
  if currentSessionId 存在:
    sessionCache.set(currentSessionId, messages)

  // Step 2: 从缓存读取新会话
  cached = sessionCache.get(targetId)

  // Step 3: 立即更新 UI
  set({
    currentSessionId: targetId,
    messages: cached || [],           // 缓存命中直接展示
    streamingBlocks: [],
    loading: !cached,                 // 无缓存才显示 loading
    sending: false,
    syncStatus: cached ? 'syncing' : 'loading',  // 新状态字段
  })

  // Step 4: 后台静默同步（无论是否有缓存都执行）
  loadHistory()
```

### 3. 后台静默同步 — loadHistory 改造

**解决竞态条件**：引入请求序列号

```typescript
let historySeq = 0;

loadHistory: async () => {
  const seq = ++historySeq;
  const { currentSessionId } = get();
  if (!currentSessionId) return;

  // 有缓存时不显示 loading
  const cached = sessionCache.get(currentSessionId);
  if (!cached) set({ loading: true });

  const unsub = wrapHandler(client, 'session.history', (data) => {
    if (seq !== historySeq) { unsub(); return; } // 过期请求，忽略
    unsub();

    const serverMsgs = parseMessages(data);

    // 同步策略：数量对比 + 追加
    if (cached && serverMsgs.length === cached.length) {
      // 数量一致，大概率没变化，不触发 UI 更新
      sessionCache.set(currentSessionId, serverMsgs);
      set({ syncStatus: 'synced', loading: false });
      return;
    }

    // 有差异：替换为服务端数据
    sessionCache.set(currentSessionId, serverMsgs);
    set({
      messages: serverMsgs,
      loading: false,
      syncStatus: 'synced',
    });
  });

  client.send({ type: 'session.history', sessionId: currentSessionId });
}
```

**为什么不做精确 diff**：客户端消息 ID（`crypto.randomUUID()`）与服务端 ID（SQLite 自增）不一致，精确 diff 无法匹配。数量对比足够——如果服务端返回的消息数量和缓存一致，内容大概率没变。如果数量不同，直接替换，视觉上几乎无感知（因为缓存已经先展示了）。

### 4. 渲染性能优化 — content-visibility

**不使用 react-virtuoso**。理由：
- ChatMessage 高度差异巨大（30px - 2000px），虚拟滚动的高度不确定性会导致严重 layout shift
- 流式内容在 `messages.map()` 外部单独渲染，虚拟滚动管不到
- `Streamdown` 的异步代码高亮会二次改变高度

**改用 CSS `content-visibility: auto`**：

```css
/* 应用到每条 ChatMessage 的外层容器 */
.chat-message {
  content-visibility: auto;
  contain-intrinsic-size: auto 200px;
}
```

**效果**：
- 视口外的消息浏览器跳过布局和渲染，只占 200px 预估高度
- `auto` 关键字让浏览器记住上次渲染的真实高度，二次滚动时精确
- 对现有代码改动极小（加 CSS class），不需要重构渲染逻辑
- Streamdown 内部的代码块已有 `content-visibility: auto`，整条消息再加一层形成双层保护

**兼容性**：Chrome 85+, Firefox 125+, Safari 18+。均满足 Electron 环境要求。

### 5. 图片懒加载

给所有聊天中的 `<img>` 标签添加 `loading="lazy"` 属性。当前代码中 `ImageThumbnail`、`ImagePreviewCard` 的 `<img>` 标签都没有此属性。

### 6. memo 优化

**问题**：`buildContent()` 每次渲染返回新对象，导致 `memo` 浅比较永远失败。

**解决**：在 store 层预处理 `resolvedContent`，避免每次渲染重建：

```typescript
// loadHistory 中预处理
const msgs = data.messages.map(m => ({
  ...m,
  resolvedContent: buildContent(m.content, m.contentBlocks),
}));
```

Chat 组件直接使用 `msg.resolvedContent` 而不是每次调用 `buildContent`。

### 7. 同步状态指示器

在聊天区域顶部（或底部输入框上方）显示轻量同步状态：

```
synced:  无显示（默认状态，不占用视觉空间）
syncing: 微小的 "·" 脉冲动画（不显眼，但在）
loading: Loader2 转圈（仅首次加载，无缓存时）
offline: "离线模式" 标记
```

只在 `syncing` 超过 2 秒或 `offline` 时显示文字提示。

---

## 深度设计：关键问题与解决方案

### 问题 1：缓存应独立于 Zustand store

**发现者**：架构师

**问题**：chat.ts 已 635 行 6 个职责，再加缓存逻辑会更难维护。Zustand `set()` 是同步替换，而缓存同步需要精细控制。

**解法**：抽取 `SessionCache` 类到 `src/lib/session-cache.ts`，store 只存当前会话的 UI 状态。缓存类可独立单元测试。

### 问题 2：快速连续切换的竞态条件

**发现者**：架构师

**问题**：`session.history` 事件没有请求 ID，多个并发请求的响应会交叉触发。

**解法**：引入递增序列号 `historySeq`，每次 `loadHistory` 递增，handler 中检查 `seq !== historySeq` 过期则忽略。

### 问题 3：流式输出期间切换会话丢数据

**发现者**：架构师

**问题**：`streamingBlocks` 是未完成的流式内容，当前 `switchSession` 直接清空，不保存。

**解法**：切换前调用 `freezeLiveText()` 冻结流式内容为文本块，追加到 `messages` 末尾，然后存入 cache。同时发 `chat.abort` 通知后端中止。

### 问题 4：消息 ID 不一致导致 diff 失效

**发现者**：架构师

**问题**：客户端用 `crypto.randomUUID()`，服务端用 SQLite 自增 ID，无法精确匹配。

**解法**：放弃精确 diff，改用「数量对比 + 替换」策略。数量一致不更新，数量不同直接替换。

### 问题 5：Markdown 高度不确定

**发现者**：架构师 + 前端性能专家

**问题**：ChatMessage 高度从 30px 到 2000px 不等，react-virtuoso 无法有效预测。

**解法**：不使用 react-virtuoso，改用 CSS `content-visibility: auto` + `contain-intrinsic-size: auto 200px`。浏览器原生跳过视口外渲染。

### 问题 6：1000 条 Streamdown 同步解析阻塞主线程

**发现者**：前端性能专家

**问题**：Streamdown 的 static 模式用 `runSync()` 同步解析 Markdown，1000 条消息阻塞 1-2s。

**解法**：`content-visibility: auto` 让浏览器跳过视口外元素的渲染，实际上只有 10-20 条可见消息会被 Streamdown 解析。首屏解析 < 50ms。

### 问题 7：缓存数据新鲜度无指示

**发现者**：用户画像（小王、老张、小刘）

**问题**：用户无法区分"看到的是缓存还是最新数据"，可能导致基于过期数据做决策。

**解法**：新增 `syncStatus` 状态字段，UI 上轻量展示。默认不显示（已同步），同步中显示微小脉冲，离线显示标记。

### 问题 8：图片 loading="lazy" 缺失

**发现者**：前端性能专家

**问题**：切换到有大量图片的会话时，所有图片同时加载。

**解法**：给所有 `<img>` 添加 `loading="lazy"` 属性。

### 问题 9：buildContent 导致 memo 失效

**发现者**：架构师

**问题**：`buildContent()` 每次渲染返回新对象，`memo` 浅比较永远失败。

**解法**：在 store 层预处理 `resolvedContent`，组件直接使用稳定引用。

### 问题 10：base64 图片缓存内存

**发现者**：架构师

**问题**：contentBlocks 中可能包含 1-5MB 的 base64 图片数据。

**解法**：缓存时保留完整的 contentBlocks（含 base64），但 SessionCache 的 `set()` 中检查单条消息大小，超过 500KB 的消息只缓存文本部分，标记 `imageTruncated: true`。下次加载时这些消息会从服务端重新拉取。或者更简单——不特殊处理，实测监控内存使用，如果成为问题再加限制。

---

## 用户画像驱动的设计补全

### 小王（后端开发，高频切换）
- **需求**：切换像浏览器标签一样快
- **覆盖**：缓存即时切换 + 同步指示器 ✓
- **未覆盖**：多会话并行提问（A 会话后台流式输出中切到 B）— 本次不处理，但 `streamingBlocks` 切换时 freeze + abort 不会丢失数据

### 小李（测试工程师，长消息）
- **需求**：快速定位目标消息
- **覆盖**：`content-visibility` 优化长会话渲染 ✓
- **未覆盖**：消息搜索/定位能力 — 超出本次范围，记录 TODO

### 老张（技术主管，信任数据完整性）
- **需求**：确认看到的是完整数据
- **覆盖**：同步状态指示器 ✓
- **未覆盖**：会话摘要视图 — 超出本次范围

### 小陈（前端开发，代码+图片）
- **需求**：代码高亮和图片正确显示
- **覆盖**：`content-visibility` 不影响视口内渲染，图片 `loading="lazy"` ✓

### 小赵（运维，弱网）
- **需求**：离线可用
- **覆盖**：缓存即时展示 + 离线标记 ✓
- **未覆盖**：Bot 端缓存方案 — 超出本次范围

### 小刘（产品经理，表格数据）
- **需求**：数据准确性
- **覆盖**：同步状态指示器 ✓

### 小黄（实习生，简单易用）
- **需求**：无技术概念负担
- **覆盖**：同步状态默认不显示文字，只有脉冲动画 ✓

---

## MVP 范围定义

### 本次实施（P0）

| 改动 | 文件 | 说明 |
|------|------|------|
| SessionCache 类 | `src/lib/session-cache.ts`（新建） | 消息缓存层 |
| switchSession 改造 | `src/stores/chat.ts` | 缓存读取 + 静默同步 |
| loadHistory 改造 | `src/stores/chat.ts` | 竞态保护 + 数量对比 |
| content-visibility | `src/features/chat/ChatMessage.tsx` | CSS 渲染优化 |
| 流式切换保护 | `src/stores/chat.ts` | freeze + abort |
| 图片 lazy loading | `src/features/chat/ChatMessage.tsx` | `loading="lazy"` |
| syncStatus 状态 | `src/stores/chat.ts` + `src/features/chat/index.tsx` | 同步指示器 |
| memo 优化 | `src/stores/chat.ts` + `src/features/chat/index.tsx` | resolvedContent 预处理 |

### 不改后端

所有优化纯前端实现，后端 `session.history` 接口不变。

### 超出本次范围（TODO）

- 消息搜索/定位能力
- 会话摘要/总览视图
- 跨会话内容引用/拖拽
- Bot 端（飞书/企微）缓存方案
- IndexedDB 持久化缓存（跨会话保留）
- 长消息内部懒渲染
