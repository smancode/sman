---
name: java-enum-scanner
description: 扫描项目中的所有枚举类型，理解业务状态和常量定义，列出所有枚举类及其可选值。
license: MIT
---

# Java 枚举类扫描器（元 Skill）

> **这是元 Skill**：提供扫描指导原则，边扫描边学习，发现项目特有模式后生成项目专属 Skill。

## 作用
扫描项目中的所有枚举类型，理解业务状态和常量定义。

## 目标
列出所有枚举类，说明其用途和可选值。

## 边扫描边学习（重要！）

扫描过程中，**发现任何项目特有的模式都要记录**：

### 需要关注的发现
1. **枚举基类/接口** - 如 `BaseEnum`、`CodeEnum` 等统一接口
2. **枚举字段约定** - code、desc、name 等字段命名
3. **枚举序列化方式** - 如何在 JSON、数据库中存储
4. **状态机枚举** - 有状态流转的业务枚举
5. **错误码枚举** - 统一的错误码定义方式

### 发现后立即写入项目 Skill
在项目目录下创建或更新 `project-enums.md`，记录：
- 该项目的枚举基类/接口
- 枚举字段命名约定
- 核心业务枚举清单
- 枚举使用规范

## 执行策略（避免 Token 爆炸）

> **重要**：存量 Java 项目类成千上万，LLM 上下文窗口有限，必须分批执行！

### 分批扫描原则
1. **按包分批** - 每次只扫描 `*.enums.*` 或 `*.constant.*` 包
2. **按类型分批** - 状态枚举 → 类型枚举 → 错误码枚举
3. **增量记录** - 每批扫描完立即写入项目 Skill

### 推荐执行方式
```
第1轮：扫描枚举包位置 → 记录枚举清单
第2轮：分析枚举基类/接口 → 记录枚举约定
第3轮：分析核心业务枚举 → 记录状态、类型枚举
第4轮：分析错误码枚举 → 记录错误码定义
...以此类推
```

### 枚举数量过多时
- 只记录枚举名称和用途
- 枚举值只展示前 10-20 个
- 标注"共 N 个值"

## 扫描规则

### 1. 查找枚举类
- 文件名以 `.java` 结尾
- 类声明包含 `enum` 关键字
- 位于 src/main/java 目录下

### 2. 排除内置枚举
排除以下系统枚举（不需要业务关注）：
- `java.lang.Enum`
- `java.time.*`（DayOfWeek、Month 等）
- `java.util.concurrent.TimeUnit`（可保留如果业务常用）
- Spring 框架内部枚举

### 3. 提取枚举信息
- 枚举名称
- 所有枚举值（常量名）
- 每个值的属性（如果有）
- 枚举上的注解（@JsonValue、@EnumValue 等）

### 4. 分类
- **状态枚举**: OrderStatus、UserStatus、TaskStatus
- **类型枚举**: Gender、OrderType、PayMethod
- **错误码枚举**: ErrorCode、ResultCode、ExceptionCode
- **标志枚举**: Flag、Type（需要看业务含义）

### 5. 关联业务
- 查找枚举在哪些实体字段中使用
- 记录 `@Enumerated` 注解的配置

## 输出格式

```markdown
## 枚举清单

### 状态类
| 枚举名 | 值 | 用途 | 关联实体 |
|--------|-----|------|----------|
| OrderStatus | PENDING,PAID,SHIPPED,COMPLETED,CANCELLED | 订单状态 | OrderEntity.status |
| UserStatus | NORMAL,DISABLED,DELETED | 用户状态 | UserEntity.status |

### 类型类
| 枚举名 | 值 | 用途 |
|--------|-----|------|
| Gender | MALE,FEMALE,UNKNOWN | 性别 |
| PayMethod | WECHAT,ALIPAY,BANK_CARD,CASH | 支付方式 |

### 错误码类
| 枚举名 | 范围 | 说明 |
|--------|------|------|
| ErrorCode | 1000-1999 | 用户模块错误码 |
| BusinessError | 10000-10999 | 业务错误码 |

## 枚举详情

### OrderStatus
文件: com.xxx.enums.OrderStatus
```java
public enum OrderStatus {
    PENDING("待支付"),
    PAID("已支付"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    CANCELLED("已取消");
    
    private final String desc;
}
```
使用处: OrderEntity.status (@Enumerated(EnumType.STRING))
```

## 注意事项
- 枚举值如果很多（如100+），只展示前20个并标注总数
- 区分业务枚举和第三方库枚举
- 注意枚举值的命名规范（是否用下划线、大写等）
