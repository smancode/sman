# DB 实体分析提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "@Entity", "@Column", "JPA")</terminology_preservation>
    </language_rule>
</system_config>

## 任务目标

扫描数据库实体：
1. **实体识别**：@Entity, @Table 注解的类
2. **字段映射**：@Column, @Id, @GeneratedValue
3. **关系映射**：@OneToMany, @ManyToOne, @ManyToMany
4. **索引信息**：@Index, 联合索引
5. **枚举映射**：@Enumerated

## 可用工具

- `find_file`: 查找 Entity 文件
- `read_file`: 读取实体类
- `grep_file`: 搜索注解

## 执行步骤

### Step 1: 查找实体文件

使用 `find_file` 查找 entity 目录下的文件，或使用 `grep_file` 搜索 @Entity 注解。

### Step 2: 读取实体内容

提取每个实体的类名、表名、主键类型、字段列表、关系映射。

### Step 3: 分析关系

识别实体间的关系：一对多、多对一、多对多。

### Step 4: 识别索引

使用 `grep_file` 搜索 @Index 注解。

## 输出格式

```markdown
# DB 实体分析报告

## 概述
[实体数量、关系统计]

## 实体列表
| 实体类 | 表名 | 字段数 | 主键类型 | 描述 |
|--------|------|--------|----------|------|
| ... | ... | ... | ... | ... |

## 关系映射
| 源实体 | 关系类型 | 目标实体 | 级联操作 |
|--------|----------|----------|----------|
| ... | ... | ... | ... |

## 字段统计
[按类型分组的字段统计]

## 索引信息
| 表名 | 索引名 | 字段 | 唯一 |
|------|--------|------|------|
| ... | ... | ... | ... |

## 数据模型评估
[数据模型的合理性分析]

## 元数据
- 分析时间: {timestamp}
- 项目路径: {project_path}
- 实体总数: {count}
```

## 注意事项

- 注意是否缺少必要的索引
- 注意关系映射是否合理
- 注意字段类型是否匹配数据库
- 注意是否使用 Lombok @Data 导致 JPA 问题
- 注意枚举类型的存储方式
