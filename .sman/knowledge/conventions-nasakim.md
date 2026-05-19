# Conventions — nasakim

> Last extracted: 2026-05-19T03:35:56.749Z

## 开发流程：必须 TDD
<!-- hash: 7g8h9i -->
- 用户期望先写测试再写代码（TDD 流程），直接写代码后补测试不被认可
- 补测试不如一开始就 TDD，但最底线是交付前测试必须全部通过
<!-- end: 7g8h9i -->

## 测试规范
<!-- hash: 0j1k2l -->
- 后端测试：数据存储 CRUD、核心分析逻辑、错误处理
- 前端测试：组件渲染、用户交互、表单验证、消息发送
- 测试文件位置：与源文件同目录，后端 `server/*.test.ts`，前端 `src/**/*.test.tsx`
<!-- end: 0j1k2l -->

## WebSocket 返回数据必须做类型转换
<!-- hash: 5v6w7x -->
- SQLite 存储的 JSON 字段（如 workspaceIds）实际为字符串，前端 schema 期望数组
- WebSocket handler 返回数据前必须将 JSON 字符串字段 parse 为对应类型，不可直接透传原始 row
- `group.create` 广播时同样需要解析，否则前端收到的数据格式错误导致不显示
<!-- end: 5v6w7x -->

## 不要使用 Zustand store 实例上不存在的方法
<!-- hash: 1f2e3d -->
- `client.listeners()` 不是 Zustand store 的有效 API，调用会抛运行时错误导致前端崩溃
- 在 store 中注册/管理监听器时，只使用 Zustand 提供的 `subscribe`、`getState`、`setState` 等标准方法
<!-- end: 1f2e3d -->

## useEffect 依赖数组不要包含函数引用
<!-- hash: m3n4o5 -->
- Zustand store 的 action（如 `loadGroups`）每次渲染返回新引用，放入 useEffect 依赖数组会导致无限循环或时序问题
- 应只依赖状态值（如 WebSocket `status`），在 status 变为 connected 时执行一次性初始化
- 否则可能出现初始化时 client 为 null 导致 "No WebSocket client available" 的错误
<!-- end: m3n4o5 -->