# 测试表格渲染

## 表格 1：简单表格

| 测试用例 | 验证内容 | 状态 |
|---------|---------|------|
| 测试1 | 第一次就成功 | 通过 |

## 表格 2：带代码的表格

| 测试用例 | 验证内容 | 状态 |
|---------|---------|------|
| `testExecute` | 第一次成功 | ✅ 通过 |
| `testRetry` | 重试成功 | ✅ 通过 |

## 表格 3：用户原始表格

| 测试用例 | 验证内容 | 状态 |
|---------|---------|------|
| `testExecuteWithRetry_SuccessOnFirstAttempt` | 第一次就成功 | ✅ 通过 |
| `testExecuteWithRetry_SuccessAfterRetry` | 失败后重试成功 | ✅ 通过 |
| `testExecuteWithRetry_AllAttemptsFail` | 所有重试都失败（验证总共3次） | ✅ 通过 |
| `testExecuteWithRetryForIO_Success` | IO重试成功场景 | ✅ 通过 |
| `testExecuteWithRetryForIO_Failure` | IO重试失败场景 | ✅ 通过 |
| `testExecuteWithRetryForIO_NonIOException` | 非IO异常包装 | ✅ 通过 |
