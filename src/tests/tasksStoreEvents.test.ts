import { beforeEach, describe, expect, it, vi } from "vitest";

type EventCallback = (event: { payload: any }) => void;

const eventCallbacks: Record<string, EventCallback> = {};
let onTaskDagCallback: ((event: any) => void) | null = null;
let onSubTaskStartedCallback: ((event: any) => void) | null = null;
let onSubTaskCompletedCallback: ((event: any) => void) | null = null;
let onOrchestrationProgressCallback: ((event: any) => void) | null = null;
let onTestResultCallback: ((event: any) => void) | null = null;

vi.mock("@tauri-apps/api/event", () => ({
  listen: vi.fn(async (eventName: string, callback: EventCallback) => {
    eventCallbacks[eventName] = callback;
    return () => {};
  }),
}));

vi.mock("$lib/api/tauri", () => ({
  taskApi: {
    getStatus: vi.fn(async () => ({ success: true, data: null })),
    list: vi.fn(async () => ({ success: true, data: [] })),
    execute: vi.fn(),
    cancel: vi.fn(),
  },
  orchestrationApi: {
    executeTask: vi.fn(),
    getTaskDag: vi.fn(),
    getStatus: vi.fn(),
  },
  onTaskDag: vi.fn(async (callback: (event: any) => void) => {
    onTaskDagCallback = callback;
    return () => {};
  }),
  onSubTaskStarted: vi.fn(async (callback: (event: any) => void) => {
    onSubTaskStartedCallback = callback;
    return () => {};
  }),
  onSubTaskCompleted: vi.fn(async (callback: (event: any) => void) => {
    onSubTaskCompletedCallback = callback;
    return () => {};
  }),
  onOrchestrationProgress: vi.fn(async (callback: (event: any) => void) => {
    onOrchestrationProgressCallback = callback;
    return () => {};
  }),
  onTestResult: vi.fn(async (callback: (event: any) => void) => {
    onTestResultCallback = callback;
    return () => {};
  }),
}));

describe("tasksStore event flow", () => {
  beforeEach(() => {
    vi.resetModules();
    Object.keys(eventCallbacks).forEach((key) => delete eventCallbacks[key]);
    onTaskDagCallback = null;
    onSubTaskStartedCallback = null;
    onSubTaskCompletedCallback = null;
    onOrchestrationProgressCallback = null;
    onTestResultCallback = null;
  });

  it("接收 task-status 后创建活跃任务", async () => {
    const { tasksStore } = await import("$lib/stores/tasks");
    let currentState: any;
    const unsubscribe = tasksStore.subscribe((state: any) => {
      currentState = state;
    });

    await tasksStore.initialize();
    eventCallbacks["task-status"]({
      payload: {
        task_id: "task-1",
        status: "running",
        message: "主 Claw 已开始",
      },
    });

    expect(currentState.tasks[0].id).toBe("task-1");
    expect(currentState.tasks[0].status).toBe("running");
    expect(currentState.activeTaskId).toBe("task-1");

    unsubscribe();
    tasksStore.destroy();
  });

  it("接收编排事件后更新子任务、进度和验收结果", async () => {
    const { tasksStore } = await import("$lib/stores/tasks");
    let currentState: any;
    const unsubscribe = tasksStore.subscribe((state: any) => {
      currentState = state;
    });

    await tasksStore.initialize();
    eventCallbacks["task-status"]({
      payload: {
        task_id: "task-2",
        status: "running",
        message: "开始执行",
      },
    });

    onTaskDagCallback?.({
      task_id: "task-2",
      tasks: [
        {
          id: "sub-1",
          description: "分析需求",
          status: "pending",
          depends_on: [],
        },
      ],
      parallel_groups: [["sub-1"]],
    });

    onSubTaskStartedCallback?.({
      task_id: "task-2",
      subtask_id: "sub-1",
      description: "分析需求",
    });

    onOrchestrationProgressCallback?.({
      task_id: "task-2",
      completed: 1,
      total: 1,
      percent: 1,
    });

    onSubTaskCompletedCallback?.({
      task_id: "task-2",
      subtask_id: "sub-1",
      success: true,
      output: "done",
    });

    onTestResultCallback?.({
      task_id: "task-2",
      subtask_id: "acceptance",
      passed: true,
      output: "Acceptance for request: passed",
      tests_run: 4,
      tests_passed: 4,
    });

    expect(currentState.activeTaskId).toBe("task-2");
    expect(currentState.subtasks.get("task-2")[0].status).toBe("completed");
    expect(currentState.orchestrationProgress.get("task-2").percent).toBe(1);
    expect(currentState.tasks[0].output).toContain("Acceptance for request");

    unsubscribe();
    tasksStore.destroy();
  });
});
