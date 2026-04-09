# Sman 核心设计亮点

> 基于代码分析的架构特色总结

---

## 1. Claude Session 持久化 + 进程保活

V2 Session 不是一次性 API 调用，而是**长期驻留的 Claude CLI 子进程**。

- Session ID 持久化到 SQLite，**进程重启后能恢复会话上下文**
- 用 `process.kill(pid, 0)` 探测进程存活，死进程透明重建
- **预热机制**：用户开始输入时就提前创建 session，消除冷启动延迟
- 中止时保存已流式的部分消息，不丢失已生成内容
- 两级卡死检测：3 分钟无数据超时 + 2 小时硬上限
- 配置版本号（`configGeneration`）：配置变更自动重建 session

```
用户输入 → 预热 session（提前创建子进程）
       → sendMessage → 复用已有子进程
       → 进程死亡？ → 透明重建
       → 服务器重启？ → 从 SQLite 恢复 session_id → resume
```

---

## 2. 三端统一会话模型

桌面端 / WeCom Bot / 飞书 Bot 共享同一套 Claude Session 基础设施。

- **WeCom 上开始的对话，桌面端可以继续**——无缝衔接
- 三条消息路径（`sendMessage` / `sendMessageForCron` / `sendMessageForChatbot`）共享 V2 Session，仅输出方式不同
- Chatbot 会话使用 `chatbot-` 前缀 ID，出现在桌面端侧边栏

| 端 | 连接方式 | 流式输出 |
|---|---------|---------|
| 桌面端 | WebSocket `ws://localhost:5880/ws` | 实时 delta |
| WeCom Bot | WebSocket `wss://openws.work.weixin.qq.com` | 1 秒节流 stream |
| 飞书 Bot | Lark SDK WSClient | 3 秒节流 + 3900 字符分片 |

---

## 3. Web Access 浏览器自动化（CDP）

通过 Chrome DevTools Protocol 赋予 Claude 浏览器操控能力。

- **Chrome Profile 复制**：自动拷贝用户 Chrome 的 Cookie/Login Data，**继承 SSO 登录态**（Windows 下用 PowerShell 绕过文件锁）
- 两级连接策略：优先接管已运行的 Chrome，失败则启动新实例
- **DOM 稳定性检测**：MutationObserver（800ms 无变化）+ Network 空闲（200ms）双层判断
- 登录页自动检测：URL/标题/表单三级判断，返回 `LoginRequiredError`
- URL 经验库：记住 `"ITSM" → https://...` 的映射，Claude 不需要完整 URL

```
Claude 调用 web_access_navigate("ITSM 系统")
       → URL 经验库匹配 → 命中历史 URL
       → 连接 Chrome（接管或新建）
       → 导航 → DOM 稳定检测 → 返回页面快照
       → 检测到登录页？ → 提示用户手动登录
```

---

## 4. AI 驱动的批量任务引擎

给 Claude 一个 markdown 配置，AI 自动完成"生成脚本 → 测试 → 执行"全流程。

- **AI 生成代码管道**：batch.md → Claude `query` API → 提取代码块 → 生成可执行脚本
- 自研 Semaphore：支持**暂停/恢复/停止**三种状态控制（不只是 acquire/release）
- 崩溃恢复：启动时自动重置卡在 `running` 状态的孤儿任务
- 选择性重试：只重试失败项，不动成功项
- 锁文件协调：`crontab.lock` JSON 记录所有触发事件，防止重叠执行

```
用户编写 batch.md（任务描述 + 数据源）
       → Claude 生成数据抓取脚本
       → 自动测试脚本
       → Semaphore 控制并发执行
       → 可暂停/恢复/取消
       → 失败项可选择性重试
```

---

## 5. Cron + crontab.md 自动发现

Skill 声明式定义定时任务，零配置自动注册。

- Skill 目录下放一个 `crontab.md` 就能自动注册定时任务
- 删除文件自动禁用任务，每 30 分钟扫描同步
- 僵尸检测：每 5 分钟扫描活跃任务，30 分钟无活动强制终止
- 内置 6 小时知识刷新 cron（`projectScanner.nightlyRefresh()`）

```
workspace/.claude/skills/my-skill/crontab.md
       → scanner 每 30 分钟扫描
       → 自动创建 cron 任务
       → 删除 crontab.md → 任务自动禁用
```

---

## 6. Capability Gateway（动态能力注入）

运行时按需加载能力，避免一次性注入过多工具。

- 三步懒加载：`capability_list` → `capability_load` → `capability_run`
- 关键词匹配 + LLM 语义搜索两级检索
- **运行时动态注入 MCP Server** 到活跃 session（`session.setMcpServers()`）
- 使用频率追踪，高频能力优先展示

| 执行模式 | 机制 | 适用场景 |
|---------|------|---------|
| `mcp-dynamic` | 动态注入新 MCP 工具 | 需要专用工具的能力 |
| `instruction-inject` | 返回 SKILL.md 指令 | 用现有工具即可完成 |
| `cli-subprocess` | 委托外部命令 | 需要独立进程的能力 |

---

## 7. 用户画像自学习

通过 LLM 自我反思持续积累用户偏好。

- 每轮对话后排队消息（用户 2000 字 / 助手 3000 字截断）
- 每 10 分钟、所有 session 空闲时，批量发送给 LLM 重写画像
- 画像注入到每条用户消息**前缀**（不是 system prompt），Claude 获得持久化上下文
- 保存前验证：拒绝重复标题/段落（幻觉检测）
- 串行队列保证并发安全

```
每轮对话 → 排队 user+assistant 消息
       → 10 分钟定时器 + session 全部空闲
       → LLM 分析已有画像 + 新对话 → 重写画像
       → 验证（去重、幻觉检测）→ 保存
       → 下次用户消息前缀注入画像
```

---

## 8. Chatbot 多媒体 + 命令系统

企业 IM 端不只支持文字，还有完整的命令体系。

- **WeCom**：AES 解密加密图片、语音转文字、文件/视频/混合消息
- **飞书**：SDK 下载文件 + MIME 头字节嗅测
- 命令系统：`//cd <项目名>` 切换目录、`//pwd` 显示当前、`//workspaces` 列出桌面端项目
- Session 自动恢复：桌面端删了会话，Chatbot 侧透明重建

| 命令 | 说明 |
|------|------|
| `//cd <项目名/路径/序号>` | 切换工作目录 |
| `//pwd` | 显示当前工作目录 |
| `//workspaces` / `//wss` | 列出桌面端已打开项目 |
| `//status` / `//sts` | 显示连接状态 |
| `//help` | 帮助信息 |

---

## 9. 工程级细节

### Root/Sudo 兼容
检测 `process.getuid() === 0`，从 CLI `--permission-mode bypassPermissions` 切换到环境变量 `CLAUDE_BYPASS_PERMISSIONS`（root 用户下 CLI 参数被拒绝）。

### WebSocket 认证
随机 token 首次运行生成并存储在 `config.json`。客户端 5 秒内必须 `auth.verify`，否则断开。Token 仅 loopback 地址可通过 HTTP 获取。

### 双模式服务器
自动检测独立 Node 进程（dev）还是 Electron 导入。Dev 模式自动启动 HTTP 服务；Electron 模式由 `main.ts` 控制生命周期。

### Claude CLI 路径发现
搜索 7 个候选位置覆盖 dev / Electron 打包 / 远程部署场景，确保总能找到 `claude` 可执行文件。

### Stall Detection
活跃工具/子代理使用时，每 30 秒检查 V2 子进程 PID 存活，2 小时硬上限保护。
