# API 入口扫描提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "@RestController", "GET", "POST")</terminology_preservation>
    </language_rule>
</system_config>

## 任务目标

扫描项目所有系统入口：
1. **HTTP REST 入口**：@RestController, @Controller
2. **Feign 客户端**：@FeignClient
3. **消息监听器**：@JmsListener, @KafkaListener, @RabbitListener
4. **RPC 服务**：Dubbo @Service
5. **定时任务**：@Scheduled
6. **事件监听器**：@EventListener

## 可用工具

- `find_file`: 查找 Controller 文件
- `read_file`: 读取具体 Controller 类
- `grep_file`: 搜索注解

## 执行步骤

### Step 1: 查找入口文件

使用 `find_file` 查找 Controller 文件（*.Controller.java, *.Controller.kt）。

### Step 2: 搜索 REST 注解

使用 `grep_file` 搜索 @GetMapping、@PostMapping、@PutMapping、@DeleteMapping 注解。

### Step 3: 读取 Controller 内容

提取每个 Controller 的类名、包路径、HTTP 方法路径、参数信息。

### Step 4: 搜索其他入口类型

使用 `grep_file` 搜索 @FeignClient、@JmsListener、@KafkaListener、@Scheduled 等注解。

## 输出格式

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

## 元数据
- 分析时间: {timestamp}
- 项目路径: {project_path}
- 入口总数: {count}
```

## 注意事项

- 注意 RESTful 风格是否规范
- 注意路径命名是否符合规范
- 注意是否缺少必要的鉴权注解
- 注意异常处理是否统一
