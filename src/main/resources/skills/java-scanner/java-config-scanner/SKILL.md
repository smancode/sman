---
name: java-config-scanner
description: 扫描项目中的配置文件，理解项目配置和环境差异，列出所有配置文件及其关键配置项。
license: MIT
---

# Java 配置文件扫描器（元 Skill）

> **这是元 Skill**：提供扫描指导原则，边扫描边学习，发现项目特有模式后生成项目专属 Skill。

## 作用
扫描项目中的配置文件，理解项目配置和环境差异。

## 目标
列出所有配置文件，说明其用途和关键配置项。

## 边扫描边学习（重要！）

扫描过程中，**发现任何项目特有的模式都要记录**：

### 需要关注的发现
1. **配置中心** - Nacos、Apollo 等配置中心的使用
2. **多环境配置** - dev/test/prod 环境配置差异
3. **敏感配置管理** - 密码、密钥等如何管理
4. **自定义配置项** - 业务相关的配置项
5. **配置加密** - 是否使用 Jasypt 等加密

### 发现后立即写入项目 Skill
在项目目录下创建或更新 `project-config-guide.md`，记录：
- 该项目的配置文件结构
- 关键配置项说明
- 多环境配置差异
- 配置修改注意事项

## 执行策略（避免 Token 爆炸）

> **重要**：存量 Java 项目类成千上万，LLM 上下文窗口有限，必须分批执行！

### 分批扫描原则
1. **按文件类型分批** - application.yml → bootstrap.yml → logback.xml → 其他
2. **按环境分批** - 先看主配置，再看各环境配置
3. **增量记录** - 每批扫描完立即写入项目 Skill

### 推荐执行方式
```
第1轮：扫描 application.yml → 记录主配置
第2轮：扫描 application-{env}.yml → 记录环境差异
第3轮：扫描 bootstrap.yml → 记录配置中心配置
第4轮：扫描 logback.xml → 记录日志配置
第5轮：扫描 pom.xml → 记录依赖版本
...以此类推
```

### 优先级
1. 先看主配置文件 - 了解核心配置
2. 再看环境配置差异 - 了解部署差异
3. 最后看其他配置文件

## 扫描规则

### 1. 配置文件类型

#### Java/Web配置
- `application.yml` / `application.yaml`
- `application.properties`
- `application-dev.yml` / `application-prod.yml` (多环境)

#### Spring Cloud
- `bootstrap.yml` / `bootstrap.properties`
- `nacos.yml` / `nacos.properties`
- `spring-cloud.yml`

#### 数据库
- `dbcp.properties` (Druid)
- `mybatis-config.xml`
- `mapper/*.xml` (MyBatis映射文件)

#### 消息队列
- `rabbitmq.properties`
- `kafka.properties`
- `rocketmq.properties`

#### 日志
- `logback.xml` / `logback-spring.xml`
- `log4j.properties` / `log4j.xml`

#### 安全
- `application-oauth.yml`
- `keystore.jks` / `keystore.p12`

#### 其他
- `pom.xml` / `build.gradle` (依赖配置)
- `.mvn/` (Maven wrapper配置)
- `Dockerfile`
- `docker-compose.yml`
- `nginx.conf`

### 2. 提取配置信息
- 文件路径
- 包含的配置项（不要求全部，提取关键的）
- 配置文件间的继承/引用关系

### 3. 关键配置项
- 数据源配置 (spring.datasource.*)
- Redis配置 (spring.redis.*)
- 日志级别 (logging.level.*)
- 服务端口 (server.port)
- 上下文路径 (server.servlet.context-path)
- 第三方服务地址

## 输出格式

```markdown
## 配置文件清单

### 应用配置
| 文件 | 路径 | 用途 | 关键配置 |
|------|------|------|----------|
| application.yml | src/main/resources/ | 主配置 | server.port=8080 |
| application-dev.yml | src/main/resources/ | 开发环境 | spring.datasource.url=jdbc:mysql://localhost:3306/xxx |
| application-prod.yml | src/main/resources/ | 生产环境 | spring.datasource.url=jdbc:mysql://prod-db:3306/xxx |

### 数据源配置
| 配置项 | dev值 | prod值 | 说明 |
|--------|-------|--------|------|
| spring.datasource.url | localhost:3306 | prod-db:3306 | 数据库地址 |
| spring.datasource.username | root | xxx | 用户名 |
| spring.datasource.driver-class-name | com.mysql.cj.jdbc.Driver | com.mysql.cj.jdbc.Driver | 驱动 |

### Redis配置
| 配置项 | 值 | 说明 |
|--------|-----|------|
| spring.redis.host | localhost | Redis地址 |
| spring.redis.port | 6379 | 端口 |
| spring.redis.database | 0 | 库号 |

### MyBatis配置
| 配置项 | 值 | 说明 |
|--------|-----|------|
| mybatis.mapper-locations | classpath:mapper/**/*.xml | Mapper路径 |
| mybatis.type-aliases-package | com.xxx.domain | 别名包 |

### 日志配置
| 文件 | 级别 | 输出 |
|------|------|------|
| logback-spring.xml | DEBUG | file + console |

## 配置文件结构图

```
src/main/resources/
├── application.yml          # 主配置
├── application-dev.yml      # 开发
├── application-prod.yml     # 生产
├── application-test.yml     # 测试
├── bootstrap.yml            # 引导配置
├── logback-spring.xml       # 日志
├── mapper/
│   ├── UserMapper.xml
│   └── OrderMapper.xml
└── static/                  # 静态资源
```
