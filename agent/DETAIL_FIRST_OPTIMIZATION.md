# 细节前置优化总结

## 问题回顾

用户提出的关键问题：
> "比如分析的时候说要修改 ReqDto 增加 totalAmount，那么编码的时候，是要重新读取 ReqDto 吗？发现 ReqDto 里面有项目自己的注解 @AmountCheck，那么会去读这个注解看是否需要用吗？我意思是，在分析阶段是否分析了所有细节，如果没有编码阶段会不会深入，我理解细节应该尽量前置到出方案的里面，看是不是这样"

## 核心问题

**原流程的缺陷**：
```
Phase 1: 分析（业务层面）
    ↓ 理解调用链、定位文件
Phase 2: 方案（用户确认）
    ↓ 列出改动点（可能只是"增加字段"）
用户确认
    ↓
Phase 3: 上下文学习（发现细节）
    ↓ 发现 @AmountCheck 等注解
Phase 4: 编码
    ↓ 但方案已经确认了！
```

**问题**：细节发现太晚，用户确认的方案可能不准确

## 优化后的流程

```
Phase 1: 分析 + 细节学习（合二为一）⭐ 关键优化
    ↓ 业务分析 + 技术分析 + 细节学习
    ↓ expert_consult + call_chain + read_file(完整)
    ↓ 读取注解定义、查找相似模式
    ↓ 理解 @AmountCheck、@NotNull 等细节
    ↓ 不确定时询问用户
Phase 2: 方案（基于详细分析）
    ↓ 方案包含具体注解和代码
    ↓ "增加字段：
    ↓    @NotNull
    ↓    @AmountCheck(min=0, max=1000000)
    ↓    private BigDecimal mergePaymentAmount;"
    ↓ 基于分析的证据：现有 amount 字段使用了 @AmountCheck
用户确认
    ↓
Phase 3: 快速验证（轻量级）
    ↓ 快速扫描，确认无重大遗漏
    ↓ 如果发现新细节，及时询问用户
Phase 4: 编码（执行确认的方案）
    ↓ 按方案执行，不再深入分析
```

## 关键改进点

### 1. Phase 1 增加"细节学习"（Detail Learning）

**新增内容**：

```markdown
### 1.2 What Details You Must Learn

**Field-Level Details**:
- [ ] Target class structure (all fields, types, annotations)
- [ ] Validation annotations on each field (@NotNull, @Min, @Max, custom)
- [ ] Custom annotations (e.g., @AmountCheck, @BusinessRule)
- [ ] How similar fields are defined in the same class

**Validation Patterns**:
- [ ] Where validation happens (method-level vs field-level vs separate validator)
- [ ] Validation framework (JSR-303, custom, Spring Validator)
- [ ] Error handling patterns when validation fails

**Code Patterns**:
- [ ] How DTO fields map to Entity fields
- [ ] How Entity fields map to database columns
- [ ] Transaction boundaries
- [ ] Cascading patterns
```

**新增步骤**：

```markdown
### 1.3 Detail Learning Steps

**Step 1: Read Target Classes Completely**
使用 endLine=999999 完整读取

**Step 2: Read Annotation Definitions (if custom)**
如果看到 @AmountCheck，读取其定义

**Step 3: Find Similar Patterns**
使用 grep_file 查找相同注解的使用模式

**Step 4: Understand Validation Flow**
使用 call_chain 理解校验流程
```

### 2. Phase 2 方案必须包含具体细节

**新增要求**：

```markdown
### 2.2 What Details Must Be in the Solution

**Each solution MUST include**:

**Field-Level Details**:
- Complete field declaration with ALL annotations
- Example: `@NotNull @AmountCheck(min = 0, max = 1000000) private BigDecimal mergePaymentAmount;`

**Annotation Details**:
- Which annotations to use (based on Phase 1 observations)
- Annotation parameters (min, max, message, etc.)
- Why these annotations are needed

**Evidence from Analysis**:
- Reference to observed patterns (e.g., "Similar to existing `amount` field")
- Reference to annotation usage (e.g., "All amount fields use @AmountCheck")
```

**方案模板更新**：

```markdown
### 方案 A：在 ValidParamService 增加校验（推荐）

**改动清单**：
1. `ReqDto.java` - 增加 mergePaymentAmount 字段
   ```java
   // 基于分析：现有 amount 字段使用了 @AmountCheck 注解
   @NotNull
   @AmountCheck(min = 0, max = 1000000, message = "合并支付金额必须在0-100万之间")
   private BigDecimal mergePaymentAmount;
   ```

**基于分析的证据**：
- ReqDto 中现有 `amount` 字段使用了 `@AmountCheck` 注解
- ValidParamService 中已有金额校验逻辑
- 因此新增字段也应使用相同的注解模式
```

### 3. Phase 3 简化为"快速验证"

**原 Phase 3（上下文学习）**：需要完整读取文件、查找模式
**新 Phase 3（快速验证）**：快速扫描，确认无重大遗漏

```markdown
## Phase 3: Quick Verification Phase

**NOTE**: This phase is LIGHTWEIGHT since detailed analysis was done in Phase 1.

### 3.1 What to Verify
- [ ] Re-read target files (quick scan, not full read)
- [ ] Verify annotations match plan
- [ ] Verify patterns match observations
- [ ] Final sanity check

### 3.3 What If New Details Found?

**Option A: Minor details** (logging style, import order)
- Proceed with coding, use observed patterns
- No need to re-confirm with user

**Option B: Major details** (new annotation type, different validation approach)
- MUST pause and inform user
- Explain the new finding
- Ask if solution needs adjustment
```

## 实际案例对比

### 场景：放款接口增加合并支付字段

**原流程**：

```
Round 1: 分析
- expert_consult("放款接口 合并支付字段")
- call_chain("DisburseService.execute")
- 输出：找到 Handler → Service → Mapper

Round 2: 方案
- 方案：在 ReqDto 增加 mergePaymentAmount
- 用户确认

Round 3: 上下文学习（这时候才发现！）
- read_file(ReqDto) → 发现 @AmountCheck
- read_file(AmountCheck) → 了解注解参数
- grep_file("@AmountCheck") → 发现模式
- 输出："哦，原来需要用 @AmountCheck"

Round 4: 编码
- 但是用户已经确认方案了！
- 尴尬：是否需要重新确认？
```

**优化后流程**：

```
Round 1: 分析 + 细节学习
- expert_consult("放款接口 合并支付字段")
- call_chain("DisburseService.execute")
- read_file(ReqDto, endLine=999999) → 发现 @AmountCheck ⭐
- read_file(AmountCheck) → 了解注解参数 ⭐
- grep_file("@AmountCheck") → 发现模式 ⭐
- 输出：分析完成，发现所有细节

Round 2: 方案（基于细节）
- 方案：在 ReqDto 增加 mergePaymentAmount
- 具体代码：
  ```java
  @NotNull
  @AmountCheck(min = 0, max = 1000000)
  private BigDecimal mergePaymentAmount;
  ```
- 基于证据："现有 amount 字段使用了相同注解"
- 用户确认（确认时已看到完整方案）

Round 3: 快速验证
- 快速扫描 ReqDto
- 确认：与 Phase 1 分析一致
- 输出：验证完成，开始编码

Round 4: 编码
- 按确认的方案执行
- 不再有意外
```

## 关键优势

| 对比项 | 原流程 | 优化后流程 |
|--------|--------|------------|
| 细节发现时机 | Phase 3（方案确认后） | Phase 1（方案前） |
| 方案准确性 | 可能不准确（遗漏细节） | 准确（基于完整细节） |
| 用户确认质量 | 确认的是"增加字段" | 确认的是"完整代码+注解" |
| 编码阶段 | 可能需要调整方案 | 直接执行，无调整 |
| 总轮次 | 4轮（可能有反复） | 4轮（一次过） |

## Prompt 文件更新

1. **complex-task-workflow.md**
   - Phase 1 重命名为 "Analysis & Detail Learning Phase"
   - 新增 "What Details You Must Learn"
   - 新增 "Detail Learning Steps"
   - Phase 2 新增 "What Details Must Be in the Solution"
   - Phase 3 简化为 "Quick Verification Phase"

2. **system-header.md**
   - 新增 "Dynamic Prompt Loading" 说明按需加载机制

3. **DynamicPromptInjector.java**
   - 实现按需加载 Prompt 的检测和注入

## 总结

**核心理念**：**Details First, Details Early, Details in Plan**

- ✅ 细节学习前置到 Phase 1
- ✅ 方案包含具体代码和注解
- ✅ 用户确认的是完整方案
- ✅ 编码阶段直接执行
- ✅ 避免反复确认

你的理解完全正确！细节必须尽量前置到方案里。
