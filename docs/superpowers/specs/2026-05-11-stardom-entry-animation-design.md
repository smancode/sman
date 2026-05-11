# 协作星图入口动画设计

> 日期: 2026-05-11

## 目标

协作星图尚在开发中，需要一个赛博风格的入口动画作为占位。用户点击侧边栏"协作星图"后：

- 开发开关关闭（默认）→ 循环播放星际穿越动画 + 3 秒后显示"星际航道探索中，敬请期待"提示
- 开发开关打开 → 播放 3 秒动画 → 2 秒渐隐 → 进入 StardomDashboard

## 架构

方案 A：独立路由包装。新增 `StardomEntry` 组件包装动画层，与业务 Dashboard 完全解耦。

```
路由 /stardom → StardomEntry
                  ├── Canvas 星际穿越动画（始终先显示）
                  ├── fetch 开关（并行，3 秒超时）
                  └── 开关=true → 3s → 2s 渐隐 → StardomDashboard
```

## 改动清单

### 一、sman-server（3 处）

1. **`src/db.ts`** — `initHubDB()` 新增 `hub_settings` 表

```sql
CREATE TABLE IF NOT EXISTS hub_settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT DEFAULT (datetime('now', 'localtime'))
);
INSERT OR IGNORE INTO hub_settings (key, value) VALUES ('stardom_dev_mode', '0');
```

提供 `getSetting(key)` / `setSetting(key, value)`。

2. **`src/routes/hub-api.ts`** — 新增端点

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/hub/stardom-dev-mode` | 客户端拉取开关值，返回 `{ enabled: boolean }` |

3. **`src/routes/admin.ts`** — 新增管理端点

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/stardom-dev-mode` | 管理后台读取开关 |
| PUT | `/admin/stardom-dev-mode` | 管理后台切换开关 |

4. **`web/src/components/StardomToggle.tsx`** — 管理后台开关组件

嵌入 Dashboard Tab，一个 Toggle 开关。

### 二、sman 客户端后端代理（1 处）

**`server/index.ts`** — `handleHubProxy()` 新增 `/stardom-dev-mode` 路径代理

### 三、sman 前端（3 处）

1. **`src/features/stardom/StardomEntry.tsx`** — 新文件

独立动画入口组件：
- Canvas 2D 星际穿越动画
- 并行 fetch 开关（复用 hubFetch，3 秒超时）
- 状态机：`checking → locked | unlocking → dashboard`
- locked 状态：3 秒后显示"星际航道探索中，敬请期待"文字（带呼吸动画）
- unlocking 状态：3 秒后启动 2 秒渐隐过渡

2. **`src/app/routes.tsx`** — 路由修改

```tsx
{ path: 'stardom', element: <StardomEntry /> }
```

3. **`src/queries/use-hub.ts`** — 新增 `useStardomDevMode()` hook

### 四、动画视觉参数

| 参数 | 值 |
|------|-----|
| 星星数量 | 300 |
| 颜色分布 | 80% 青色系 + 20% 品红/白色 |
| 背景 | `#050510` 深蓝黑 |
| 移动速度 | z 每帧 -0.02 |
| 星星大小 | 0.5px（远）→ 3px（近） |
| 运动模糊 | 每帧 alpha 0.15 半透明覆盖 |
| 中心引擎 | 青色径向渐变，微弱脉动 |
| 提示文字 | "星际航道探索中，敬请期待" |
| 文字样式 | 青色发光，呼吸动画（opacity 0.4~0.8） |

### 五、i18n

提示文字使用 `t('stardom.entry.comingSoon')` key：
- zh-CN: `星际航道探索中，敬请期待`
- en-US: `Exploring new starlanes... Stay tuned`
