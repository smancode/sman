# Sman Hub 系统设计

> 日期: 2026-05-08
> 状态: 已确认

## 目标

1. 了解用户使用情况（谁在用、频率如何）
2. 支持从 server 向 client 广播消息
3. 保护隐私（不上传会话内容）
4. 容错设计（server/client 离线互不影响）

## 整体架构

```
Sman Client (Electron)                     sman-server (独立项目)
┌──────────────────────┐                  ┌─────────────────────────┐
│  hub.ts        │                  │  Express + SQLite       │
│  ├─ 启动上报          │──POST /report──→│  ├─ clients 表           │
│  ├─ 每小时心跳        │──POST /report──→│  ├─ reports 表           │
│  ├─ 拉广播           │──GET /broadcasts─→│  ├─ broadcasts 表       │
│  └─ 确认已读          │──POST /ack──────→│  └─ admin API            │
│                      │                  │                         │
│  AES-256-GCM 加解密   │←────PSK─────────→│  AES-256-GCM 加解密      │
└──────────────────────┘                  └─────────────────────────┘
```

- 通讯方式: HTTP REST
- 加密: AES-256-GCM 预共享密钥 (PSK)
- 用户标识: hostname@ip
- 轮询周期: 1 小时
- 容错: 所有请求 try-catch，失败静默

## 通讯协议

### 加密方案

```
明文 JSON → JSON.stringify → AES-256-GCM(PSK, random_iv) → base64(iv + ciphertext + authTag)
```

- PSK: 32 字节，client 硬编码 + server 环境变量
- PSK 版本号: 包含在请求中，支持未来密钥轮换
- IV: 每次请求随机 12 字节
- 报文体: `base64(iv[12] + ciphertext[N] + authTag[16])`
- 防重放: timestamp 与 server 当前时间偏差超过 5 分钟则拒绝

> **安全限制**: PSK 硬编码在 Electron 中，理论上可被反编译提取。当前阶段可接受，后续可升级为密钥交换方案。

### POST /api/report — 上报

请求:
```json
{
  "payload": "<encrypted-base64>",
  "timestamp": 1715116800,
  "pskVersion": 1
}
```

解密后 payload:
```json
{
  "clientId": "MacBook-Pro-nasakim@192.168.1.100",
  "version": "26.4.21",
  "hostname": "MacBook-Pro-nasakim",
  "ip": "192.168.1.100",
  "reportTime": "2026-05-08T14:30:00Z",
  "activeSessions": 3
}
```

### POST /api/broadcasts — 拉取广播

改为 POST 以支持加密 body（避免 query string 传加密数据）。

请求:
```json
{
  "payload": "<encrypted-base64>",
  "timestamp": 1715116800,
  "pskVersion": 1
}
```

解密后:
```json
{
  "clientId": "MacBook-Pro-nasakim@192.168.1.100",
  "since": "2026-05-08T13:00:00Z"
}
```

响应（同样加密）:
```json
{
  "payload": "<encrypted-base64>"
}
```

解密后:
```json
{
  "messages": [
    {
      "id": "bc_001",
      "title": "v26.5 发布通知",
      "body": "Sman v26.5 已发布...",
      "createdAt": "2026-05-08T10:00:00Z"
    }
  ],
  "hasMore": false
}
```

### POST /api/ack — 确认已读

请求:
```json
{
  "payload": "<encrypted-base64>",
  "timestamp": 1715116800,
  "pskVersion": 1
}
```

解密后:
```json
{
  "clientId": "MacBook-Pro-nasakim@192.168.1.100",
  "broadcastIds": ["bc_001", "bc_002"]
}
```

## sman-server 项目结构

```
sman-server/
├── package.json
├── tsconfig.json
├── .env                 # PSK, PORT, ADMIN_TOKEN
├── src/
│   ├── index.ts         # 入口
│   ├── crypto.ts        # AES-256-GCM 加解密
│   ├── db.ts            # SQLite 初始化 + 查询
│   ├── routes/
│   │   ├── report.ts    # POST /api/report
│   │   ├── broadcast.ts # GET /api/broadcasts + POST /api/ack
│   │   └── admin.ts     # 管理接口
│   └── types.ts
└── data/                # SQLite 数据（gitignore）
    └── hub.db
```

### 数据库表

```sql
CREATE TABLE clients (
  client_id TEXT PRIMARY KEY,
  version TEXT,
  hostname TEXT,
  ip TEXT,
  first_seen TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  active_sessions INTEGER DEFAULT 0
);

CREATE TABLE reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id TEXT NOT NULL,
  report_time TEXT NOT NULL,
  active_sessions INTEGER DEFAULT 0,
  FOREIGN KEY (client_id) REFERENCES clients(client_id)
);

CREATE TABLE broadcasts (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  created_at TEXT NOT NULL,
  active INTEGER DEFAULT 1
);

CREATE TABLE read_log (
  client_id TEXT NOT NULL,
  broadcast_id TEXT NOT NULL,
  read_at TEXT NOT NULL,
  PRIMARY KEY (client_id, broadcast_id)
);
```

### 管理接口

```
POST   /admin/broadcast      — 发布广播
DELETE /admin/broadcast/:id   — 撤回广播
GET    /admin/stats           — 查看统计
GET    /admin/clients         — 查看客户端列表
```

admin 用 `ADMIN_TOKEN` 环境变量做 Bearer 认证。

## Client 端集成

### 配置注入

`electron/main.ts` 启动时，若 config.json 无 hub 字段，写入:
```json
{
  "hub": {
    "serverUrl": "https://your-server.com",
    "enabled": true
  }
}
```

### Hub 模块

拆分为 `server/hub/` 目录（遵循 500 行限制）:

```
server/hub/
├── index.ts       # initHub 入口 + 定时调度
├── client.ts      # reportHeartbeat, fetchBroadcasts, ackBroadcasts
├── crypto.ts      # encrypt/decrypt (AES-256-GCM)
└── types.ts       # 类型定义
```

活跃会话数:
```sql
SELECT COUNT(*) FROM sessions
WHERE last_active_at > datetime('now', '-1 hour')
AND deleted_at IS NULL
```

### 广播展示

1. Hub 模块拉取到广播后，通过 WebSocket 发送给前端:
   ```json
   { "type": "hub:broadcast", "data": { "id": "bc_001", "title": "...", "body": "..." } }
   ```
2. 前端 MainLayout 监听 `hub:broadcast`，以 Toast 通知展示
3. 用户关闭 Toast 后，自动调用 `ackBroadcasts([id])` 确认已读
4. 前端维护已展示广播 ID 集合，防止重复展示

## 整合自动更新服务

sman-server 同时承担**更新文件托管**职责，与Hub共用同一个部署实例。

### 更新文件服务

```
GET /updates/sman/latest.yml       — 版本元数据（electron-updater 格式）
GET /updates/sman/Sman-{ver}.dmg   — macOS 安装包
GET /updates/sman/Sman-Setup-{ver}.exe — Windows 安装包
```

- 更新文件存储在 `data/updates/sman/` 目录
- `latest.yml` 由 electron-builder 自动生成，上传到 server 即可
- Express 静态文件中间件挂载 `/updates` 路径

### 配置整合

`~/.sman/config.json` 的 hub 字段扩展：
```json
{
  "hub": {
    "serverUrl": "https://your-server.com",
    "enabled": true,
    "updateUrl": "https://your-server.com/updates/sman"
  }
}
```

- `serverUrl`: Hub上报地址（`/api/report`、`/api/broadcasts`、`/api/ack`）
- `updateUrl`: electron-updater 的 feed URL
- 两者共享同一个域名，分别在不同路径

### Electron 启动流程变更

1. `electron/main.ts` 启动时读取 config.json
2. 若 `hub.updateUrl` 存在，调用 `autoUpdater.setFeedURL({ provider: 'generic', url: hub.updateUrl })`
3. 这样 runtime override 就持久化了，不再每次重启丢失

### Admin 接口

```
POST /admin/upload — 上传更新包（multipart form, 需 admin token）
```

上传后自动解压到 `data/updates/sman/`，更新 `latest.yml`。

## 后续扩展

- 下发 Skill
- 下发 Capability
- 配置同步

API 设计预留扩展空间，broadcast 表和 admin 接口可扩展新的消息类型。
