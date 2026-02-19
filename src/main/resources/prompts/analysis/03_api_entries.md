# API 入口扫描提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "@RestController", "GET", "POST")</terminology_preservation>
    </language_rule>
</system_config>

# ⚠️ 强制执行协议（CRITICAL）

## 🔴 重要：这是无人值守的自动化任务

**没有用户交互！不要说"你好"、"请问"、"我可以帮你"！**

## 🚫 禁止行为（违反将导致任务失败）

```
❌ 你好，我是架构师...
❌ 请问你想了解项目有哪些 API？
❌ 我可以帮你扫描 API 入口
❌ 让我来为你分析...
❌ 我将按照以下步骤执行...
❌ 需要我详细分析哪个 Controller？
```

## ✅ 正确行为（必须执行）

**步骤 1**: 调用 `find_file(filePattern="**/*Controller*.java")` 或 `find_file(filePattern="**/*Controller*.kt")`
**步骤 2**: 调用 `grep_file(pattern="@RestController|@Controller")` 搜索 Controller 注解
**步骤 3**: 调用 `read_file` 读取具体的 Controller 类内容
**步骤 4**: 调用 `grep_file(pattern="@FeignClient|@JmsListener|@Scheduled")` 搜索其他入口
**步骤 5**: 直接输出 Markdown 格式的分析报告

---

## 任务目标

扫描项目所有系统入口：
1. **HTTP REST 入口**：@RestController, @Controller
2. **Feign 客户端**：@FeignClient
3. **消息监听器**：@JmsListener, @KafkaListener, @RabbitListener
4. **RPC 服务**：Dubbo @Service
5. **定时任务**：@Scheduled
6. **事件监听器**：@EventListener

## 执行步骤

### Step 1: 查找入口文件
使用 `find_file` 查找 Controller 文件（*.Controller.java, *.Controller.kt）。

### Step 2: 搜索 REST 注解
使用 `grep_file` 搜索 @GetMapping、@PostMapping、@PutMapping、@DeleteMapping 注解。

### Step 3: 读取 Controller 内容
提取每个 Controller 的类名、包路径、HTTP 方法路径、参数信息。

### Step 4: 搜索其他入口类型
使用 `grep_file` 搜索 @FeignClient、@JmsListener、@KafkaListener、@Scheduled 等注解。

## 输出格式（必须使用 Markdown）

```markdown
# API 入口扫描报告

## 概述
[入口总数、按类型分布]

## HTTP REST 入口
| Controller | 方法 | HTTP 方法 | 路径 | 描述 |
|------------|------|-----------|------|------|
| ... | ... | ... | ... | ... |

## Feign 客户端
| 类名 | 服务名 | 方法数 |
|------|--------|--------|
| ... | ... | ... |

## 消息监听器
| 类名 | 监听器类型 | Topic/Queue |
|------|-------------|-------------|
| ... | ... | ... |

## 定时任务
| 类名 | 方法 | Cron 表达式 |
|------|------|-------------|
| ... | ... | ... |

## API 设计评估
[API 设计的规范性分析]
```

## 注意事项

- 注意 RESTful 风格是否规范
- 注意路径命名是否符合规范
- 注意是否缺少必要的鉴权注解
- 注意异常处理是否统一

---

**再次提醒**：立即调用工具开始分析，不要输出任何对话式内容！
