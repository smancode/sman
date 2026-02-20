---
name: java-external-call-scanner
description: 扫描项目对外部系统的调用，包括 HTTP、RPC、第三方 API 等，记录调用方式和目标服务。
license: MIT
---

# Java 外调接口扫描器（元 Skill）

> **这是元 Skill**：提供扫描指导原则，边扫描边学习，发现项目特有模式后生成项目专属 Skill。

## 作用
扫描项目对外部系统的调用，包括 HTTP、RPC、第三方API等。

## 目标
找出所有外部依赖调用，记录调用方式、目标服务、接口地址。

## 边扫描边学习（重要！）

扫描过程中，**发现任何项目特有的模式都要记录**：

### 需要关注的发现
1. **内部服务调用方式** - 公司内部 RPC 框架、服务发现机制
2. **统一的外调封装** - 如统一的 HttpClient 封装、Feign 配置
3. **第三方服务清单** - 支付、短信、地图等第三方服务
4. **服务地址配置位置** - 服务地址配置在哪些配置文件
5. **调用链路追踪方式** - 如何在调用间传递 traceId

### 发现后立即写入项目 Skill
在项目目录下创建或更新 `project-external-calls.md`，记录：
- 该项目依赖的外部服务清单
- 内部 RPC 框架使用方式
- 第三方服务配置位置
- 调用约定和最佳实践

## 执行策略（避免 Token 爆炸）

> **重要**：存量 Java 项目类成千上万，LLM 上下文窗口有限，必须分批执行！

### 分批扫描原则
1. **按调用类型分批** - Feign → RestTemplate → Dubbo → 第三方SDK
2. **按模块分批** - 每次只扫描一个模块的外调
3. **增量记录** - 每批扫描完立即写入项目 Skill

### 推荐执行方式
```
第1轮：扫描 @FeignClient 注解 → 记录 Feign 调用
第2轮：扫描 RestTemplate 使用 → 记录 HTTP 调用
第3轮：扫描 @DubboReference 注解 → 记录 Dubbo 调用
第4轮：扫描第三方 SDK 初始化 → 记录第三方服务
...以此类推
```

### 优先级
1. 先找接口定义（Feign/Dubbo 接口）- 清晰明确
2. 再找配置文件中的服务地址
3. 最后找实际调用点

## 扫描规则

### 1. Feign 客户端
查找：
- `@FeignClient` 注解的接口
- 提取 `name` 或 `url` 属性

```java
@FeignClient(name = "order-service", url = "${order.service.url}")
public interface OrderClient { ... }
```

### 2. RestTemplate
查找：
- `RestTemplate` 的实例化和 `exchange`、`getForObject` 等调用
- 提取 URL 或服务名

```java
restTemplate.getForObject("http://user-service/api/user/{id}", User.class, id);
```

### 3. HttpClient / OkHttp
查找：
- `CloseableHttpClient`、`OkHttpClient` 的使用
- 提取请求 URL

### 4. WebClient (WebFlux)
查找：
- `WebClient` 的 `uri`、`retrieve` 调用

### 5. Dubbo / RPC
查找：
- `@DubboReference`
- `@Reference`
- 配置中心的服务引用

### 6. JDBC / 数据库直连
查找：
- `DataSource`、`Connection` 的使用
- 原始 SQL 语句（注意安全）

### 7. 第三方SDK
查找：
- 阿里云 SDK、腾讯云 SDK
- 短信、支付、地图等第三方服务客户端

## 输出格式

```markdown
## 外部调用清单

### Feign 客户端
| 服务名 | 接口 | 用途 | 所在类 |
|--------|------|------|--------|
| user-service | /api/user/* | 用户服务 | UserClient.java |
| order-service | /api/order/* | 订单服务 | OrderClient.java |

### HTTP 调用
| 目标地址 | 方法 | 用途 | 所在类 |
|----------|------|------|--------|
| https://api.example.com/v1/xxx | GET | 第三方数据 | XxxService.java |

### RPC 调用
| 服务名 | 接口 | 协议 | 所在类 |
|--------|------|------|--------|
| order-service | com.xxx.OrderService | Dubbo | OrderRpcClient.java |

### 第三方SDK
| SDK | 用途 | 所在类 |
|-----|------|--------|
| Alibaba SMS | 短信发送 | SmsService.java |
| WeChat Pay | 支付 | PaymentService.java |
```

## 注意事项
- 优先找接口定义（Feign/Dubbo），其次是实际调用点
- 记录配置中的服务地址（application.yml 中的 spring.cloud.nacos.discovery 等）
- 忽略内部模块间的调用（同一项目内）
- 注意敏感信息（API Key、Token）不要输出
