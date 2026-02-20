---
name: java-api-scanner
description: 全面扫描项目中所有可能的 API 接口，生成完整清单。找出所有对外暴露的接口，包括 HTTP/REST API、RPC 接口、消息队列接口、定时任务接口等。
license: MIT
---

# Java API 接口扫描器（元 Skill）

> **这是元 Skill**：提供扫描指导原则，边扫描边学习，发现项目特有模式后生成项目专属 Skill。

## 作用
全面扫描项目中所有可能的 API 接口，生成完整清单。

## 目标
找出所有对外暴露的接口，包括但不限于：
- HTTP/REST API（Spring MVC、JAX-RS 等）
- RPC 接口（Dubbo、gRPC、Thrift、Hessian 等）
- 消息队列接口（Kafka、RabbitMQ、RocketMQ 消费者）
- 定时任务接口（Scheduled、Quartz、XXL-Job 等）
- 企业自研中间件接口（内部 RPC 框架、服务网关等）
- WebSocket 接口
- GraphQL 接口

输出路径、方法、参数、说明。

## 边扫描边学习（重要！）

扫描过程中，**发现任何项目特有的模式都要记录**：

### 需要关注的发现
1. **自研框架注解** - 如 `@SoaService`、`@OpenApi`、`@BizService` 等公司内部注解
2. **统一基类** - 如 `BaseController`、`AbstractApiController`、`BaseSoaService`
3. **包结构约定** - API 类统一放在哪些包下
4. **命名约定** - 如 `*ServiceImpl` 暴露 RPC，`*Facade` 暴露 HTTP
5. **配置约定** - 如统一路径前缀、统一响应格式
6. **中间件使用** - 项目使用了哪些消息队列、定时任务框架

### 发现后立即写入项目 Skill
在项目目录下创建或更新 `project-api-entry.md`，记录：
- 该项目特有的入口类型
- 入口所在位置
- 命名和编码约定
- 示例代码

## 执行策略（避免 Token 爆炸）

> **重要**：存量 Java 项目类成千上万，LLM 上下文窗口有限，必须分批执行！

### 分批扫描原则
1. **按模块分批** - 每次只扫描一个模块/子项目
2. **按包分批** - 每次只扫描一个包路径（如 `com.xxx.user.controller`）
3. **按类型分批** - 先扫描注解类，再扫描类名类，最后扫描包名类
4. **增量记录** - 每批扫描完立即写入项目 Skill，不要等全部完成

### 推荐执行方式
```
第1轮：扫描 @RestController 注解类 → 记录到 project-api-entry.md
第2轮：扫描 @Path 注解类 → 追加到 project-api-entry.md  
第3轮：扫描 *Controller 命名类 → 追加到 project-api-entry.md
第4轮：扫描 *.controller.* 包下类 → 追加到 project-api-entry.md
...以此类推
```

### Token 估算参考
- 一个典型 Controller 类：约 500-1000 tokens
- 扫描 50 个类约需：25000-50000 tokens
- 建议每批不超过 30-50 个类

### 遇到以下情况立即停止并记录
- 上下文接近窗口限制
- 发现大量重复模式（可归纳后跳过）
- 发现项目特有框架（先记录框架，再针对性扫描）

## 扫描规则

### 1. 从注解入手
扫描以下注解标记的类和方法：
- `@RestController`、`@Controller`
- `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、`@PatchMapping`
- `@ResponseBody`、`@ResponseBody` 类级别标注
- JAX-RS 注解：`@Path`、`@GET`、`@POST`、`@PUT`、`@DELETE`、`@Produces`、`@Consumes`
- Feign Client：`@FeignClient`、`@RequestMapping`
- GraphQL：`@QueryMapping`、`@MutationMapping`、`@SubscriptionMapping`、`@SchemaMapping`
- gRPC：继承 `BindableService` 或标注 `@GrpcService`

### 2. 从类名入手
扫描以下命名模式的类：
- `*Controller` - Spring MVC 控制器
- `*Handler` - 请求处理器
- `*Resource` - JAX-RS 资源类
- `*Endpoint` - API 端点
- `*Api` / `*API` - API 定义类
- `*Action` - Struts/Spring WebFlux action
- `*Servlet` - HttpServlet 子类
- `*Router` - 路由配置类（如 Spring WebFlux RouterFunction）
- `*Delegate` - 可能包含接口实现的代理类
- `*Provider` - JAX-RS provider 或服务提供类

### 3. 从包名入手
扫描以下包路径下的类：
- `*.controller.*`、`*.controllers.*`
- `*.api.*`、`*.apis.*`
- `*.resource.*`、`*.resources.*`
- `*.endpoint.*`、`*.endpoints.*`
- `*.handler.*`、`*.handlers.*`
- `*.rest.*`
- `*.web.*`
- `*.service.*`（可能包含对外暴露的服务接口）
- `*.facade.*`（外观模式，可能暴露 API）
- `*.action.*`（Struts 等）
- `*.servlet.*`

### 4. 从继承关系入手
扫描继承以下基类的类：
- `HttpServlet`、`HttpServletBean`、`FrameworkServlet`
- `AbstractController`（Spring）
- `BaseController` 等自定义基类
- `RouterFunctions` 相关实现
- `WebSocketHandler`（WebSocket 接口）

### 5. 提取接口信息
从方法上提取：
- `@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、`@PatchMapping`
- `@RequestMapping` (方法级别)
- `@PathVariable`、`@RequestParam`、`@RequestBody`、`@RequestHeader` 标注的参数
- JAX-RS 参数注解：`@PathParam`、`@QueryParam`、`@FormParam`、`@HeaderParam`

### 6. 忽略规则
- 排除 test 目录
- 排除以 *ControllerTest、*Mock、Mock* 开头的类
- 排除内部类

### 7. 路径拼接规则
- 类上有 @RequestMapping("/api")，方法上有 @GetMapping("/list")
- 完整路径 = 类路径 + 方法路径
- 考虑 `spring.mvc.servlet.path` 或 `server.servlet.context-path` 配置

## 输出格式

```markdown
## API 接口清单

| 序号 | 模块 | 完整路径 | 方法 | 参数 | 说明 | 所在文件 |
|------|------|----------|------|------|------|----------|
| 1 | user | POST /api/user/create | @PostMapping("/create") | @RequestBody UserCreateDTO | 创建用户 | UserController.java:45 |
| 2 | order | GET /api/order/{id} | @GetMapping("/{id}") | @PathVariable Long id | 查询订单 | OrderController.java:32 |
```

### 参数格式说明
- `@PathVariable`: `/path/{name}`
- `@RequestParam`: `?name=value`
- `@RequestBody`: `BODY(json)`

## 扫描命令参考
```bash
# 从注解扫描
find . -name "*.java" -path "*/src/main/*" | xargs grep -lE "@(Rest)?Controller|@RequestMapping|@Path\s"
find . -name "*.java" -path "*/src/main/*" | xargs grep -lE "@(Get|Post|Put|Delete|Patch)Mapping"
find . -name "*.java" -path "*/src/main/*" | xargs grep -lE "@FeignClient|@GrpcService"

# 从类名扫描
find . -name "*.java" -path "*/src/main/*" -name "*Controller.java"
find . -name "*.java" -path "*/src/main/*" -name "*Handler.java"
find . -name "*.java" -path "*/src/main/*" -name "*Resource.java"
find . -name "*.java" -path "*/src/main/*" -name "*Endpoint.java"
find . -name "*.java" -path "*/src/main/*" -name "*Api.java"
find . -name "*.java" -path "*/src/main/*" -name "*Servlet.java"
find . -name "*.java" -path "*/src/main/*" -name "*Action.java"
find . -name "*.java" -path "*/src/main/*" -name "*Router.java"

# 从包名扫描
find . -path "*/src/main/*" -path "*/controller/*" -name "*.java"
find . -path "*/src/main/*" -path "*/api/*" -name "*.java"
find . -path "*/src/main/*" -path "*/resource/*" -name "*.java"
find . -path "*/src/main/*" -path "*/endpoint/*" -name "*.java"
find . -path "*/src/main/*" -path "*/handler/*" -name "*.java"
find . -path "*/src/main/*" -path "*/rest/*" -name "*.java"
find . -path "*/src/main/*" -path "*/web/*" -name "*.java"

# 从继承关系扫描
find . -name "*.java" -path "*/src/main/*" | xargs grep -lE "extends\s+(HttpServlet|BaseController|AbstractController)"
```

## 注意事项
- 接口路径要去重
- 同一路径不同方法的要合并展示
- 记录每个接口的说明（尝试从方法名、注释、Javadoc提取）
- 大项目可分模块输出
