---
name: java-common-class-scanner
description: 扫描项目中的公共组件，包括工具类、通用组件、基础服务，找出可复用的公共代码。
license: MIT
---

# Java 公共类扫描器（元 Skill）

> **这是元 Skill**：提供扫描指导原则，边扫描边学习，发现项目特有模式后生成项目专属 Skill。

## 作用
扫描项目中的公共组件：工具类、通用组件、基础服务。

## 目标
找出可复用的公共代码，理解项目的公共能力。

## 边扫描边学习（重要！）

扫描过程中，**发现任何项目特有的模式都要记录**：

### 需要关注的发现
1. **内部工具库** - 公司自研的 common 库、工具类
2. **统一响应封装** - Result、Response 等统一返回格式
3. **基础服务封装** - 缓存、分布式锁、ID生成等基础能力
4. **公共常量定义** - 业务常量、配置常量的位置
5. **拦截器/过滤器** - 统一的处理逻辑

### 发现后立即写入项目 Skill
在项目目录下创建或更新 `project-common-components.md`，记录：
- 该项目的工具类清单及用法
- 统一响应格式和异常处理
- 基础服务使用方式
- 公共组件最佳实践

## 执行策略（避免 Token 爆炸）

> **重要**：存量 Java 项目类成千上万，LLM 上下文窗口有限，必须分批执行！

### 分批扫描原则
1. **按包分批** - 每次只扫描 `*.util.*`、`*.common.*` 等一个包
2. **按类型分批** - 工具类 → 基础组件 → 配置类 → 扩展组件
3. **增量记录** - 每批扫描完立即写入项目 Skill

### 推荐执行方式
```
第1轮：扫描 *.util.* 包 → 记录工具类
第2轮：扫描 *.common.* 包 → 记录公共组件
第3轮：扫描 *.config.* 包 → 记录配置类
第4轮：扫描拦截器/过滤器 → 记录扩展组件
...以此类推
```

### 优先级
1. 先找被多处引用的类（高复用）
2. 再找统一封装类（Result、Response）
3. 最后找业务相关的公共组件

## 扫描规则

### 1. 工具类 (Util)
查找包含以下特征的类：
- 包名包含: util、common、helper、tool
- 类名包含: Util、Helper、Utils、Builder
- 方法多为 static
- 无状态

常见位置：
- `com.xxx.util.*`
- `com.xxx.common.*`
- `com.xxx.helper.*`

### 2. 通用组件
- 基础 Service/Manager（如 BaseService）
- 抽象类、模板类
- 全局配置类
- 序列化/反序列化工具
- 响应封装（Result、Response）

### 3. 常量类
- 类名包含: Constants、Const
- 字段全为 static final
- 配置前缀常量

### 4. 扩展类
- 实现 JDK  SPI 的类
- Spring  @Component、@Bean 定义
- 拦截器、过滤器、监听器

### 5. 分类维度
- **工具类**: 字符串处理、日期处理、JSON序列化、加密解密
- **基础组件**: 基础Service、基类、模板
- **配置类**: Spring配置、Web配置、Security配置
- **扩展点**: 拦截器、过滤器、监听器、插件

## 输出格式

```markdown
## 公共类清单

### 工具类
| 类名 | 包路径 | 功能 | 方法数 |
|------|--------|------|--------|
| JsonUtil | com.xxx.util.json | JSON序列化 | 23 |
| DateUtil | com.xxx.util.date | 日期处理 | 18 |
| Md5Util | com.xxx.util.crypto | MD5加密 | 5 |
| AssertUtil | com.xxx.util.assert | 参数校验 | 12 |

### 基础组件
| 类名 | 包路径 | 功能 | 继承 |
|------|--------|------|------|
| BaseService | com.xxx.service.base | 基础Service | IService |
| AbstractEntity | com.xxx.domain.base | 基础实体 | Serializable |
| ResultWrapper | com.xxx.common.result | 响应封装 | - |

### 配置类
| 类名 | 包路径 | 功能 |
|------|--------|------|
| WebMvcConfig | com.xxx.config.web | Web配置 |
| SecurityConfig | com.xxx.config.security | 安全配置 |
| RedisConfig | com.xxx.config.redis | Redis配置 |

### 扩展组件
| 类名 | 包路径 | 功能 |
|------|--------|------|
| LoginInterceptor | com.xxx.web.interceptor | 登录拦截 |
| TraceFilter | com.xxx.web.filter | 链路追踪 |
| ApplicationReadyListener | com.xxx.listener | 启动监听 |

## 核心工具详情

### JsonUtil
```java
public class JsonUtil {
    public static String toJson(Object obj) { ... }
    public static <T> T fromJson(String json, Class<T> clazz) { ... }
    public static <T> T parseObject(String json, TypeReference<T> type) { ... }
}
```

## 注意事项
- 重点关注被多处引用的类
- 区分自研和第三方（spring、apache、guava）
- 统计引用次数（如果容易获取）
- 优先展示业务相关的公共组件
