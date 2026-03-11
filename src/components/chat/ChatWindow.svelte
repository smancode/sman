<script lang="ts">
  import { selectedProject } from "../../lib/stores/projects";
  import {
    tasksStore,
    activeTask,
    activeSubtasks,
    activeOrchestrationProgress,
    activeParallelGroups,
  } from "../../lib/stores/tasks";
  import { conversationApi, appSettingsApi, openclawApi } from "../../lib/api/tauri";
  import {
    initializeOpenClaw,
    connectionError,
    isConnected,
    sendChatMessage,
    subscribeToChatEvents,
    disconnectOpenClaw,
  } from "../../lib/api/openclaw";
  import type { ChatEventPayload } from "../../core/openclaw/types";
  import type { HistoryEntryRecord } from "../../lib/types";
  import MessageBubble from "./MessageBubble.svelte";
  import InputArea from "./InputArea.svelte";
  import TaskProgress from "../task/TaskProgress.svelte";
  import SubTaskProgress from "../task/SubTaskProgress.svelte";
  import FileTree from "../task/FileTree.svelte";
  import type { Message } from "../../lib/types";
  import { onMount } from "svelte";
  import { get } from "svelte/store";
  import { projectsStore } from "../../lib/stores/projects";
  import { sanitizeAssistantContent } from "../../lib/chat/assistantContent";

  // State
  let messagesByProject = $state<Record<string, Message[]>>({});
  let conversationByProject = $state<Record<string, string>>({});
  let isSending = $state(false);
  let sendingProjectId = $state<string | null>(null);
  let initializedProjectId = $state<string | null>(null);
  let messagesContainer: HTMLDivElement = $state()!;
  let llmConfigured = $state(false);
  let isInitializing = $state(true);
  let initError = $state<string | null>(null);

  // Derived
  let messages = $derived<Message[]>(
    $selectedProject
      ? messagesByProject[$selectedProject.id] ||
          getDemoMessages($selectedProject.name)
      : [],
  );

  function getDemoMessages(projectName: string): Message[] {
    return [
      {
        id: "1",
        role: "assistant",
        content: `欢迎来到 ${projectName}！请描述你希望构建或修改的内容。`,
        timestamp: Date.now() - 60000,
      },
    ];
  }

  function normalizeRole(role: HistoryEntryRecord["role"]): Message["role"] {
    const normalized = role.toLowerCase();
    if (normalized === "assistant") return "assistant";
    if (normalized === "system") return "system";
    return "user";
  }

  function mapHistoryEntries(entries: HistoryEntryRecord[]): Message[] {
    return entries.map((entry) => ({
      id: entry.id,
      role: normalizeRole(entry.role),
      content:
        normalizeRole(entry.role) === "assistant"
          ? sanitizeAssistantContent(entry.content)
          : entry.content,
      timestamp: new Date(entry.timestamp).getTime(),
    }));
  }

  function createLocalMessageId(): string {
    return (
      globalThis.crypto?.randomUUID?.() ??
      `msg_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`
    );
  }

  function getInputPlaceholder(): string {
    if (isInitializing) return "正在初始化 AI 服务...";
    if (initError) return initError;
    if (!$selectedProject) return "请先添加项目";
    if (!llmConfigured) return "请先在设置中配置 LLM API Key";
    return "输入 / 使用命令";
  }

  function isInputDisabled(): boolean {
    return isSending || !$selectedProject || !$isConnected || !llmConfigured || isInitializing;
  }

  function updateLatestThinkingMessage(projectId: string, content: string) {
    const projectMessages = messagesByProject[projectId];
    if (!projectMessages || projectMessages.length === 0) return;

    let updated = false;
    const nextMessages = projectMessages.map((msg, index) => {
      if (updated) return msg;
      if (
        index === projectMessages.length - 1 &&
        msg.role === "assistant" &&
        msg.taskId &&
        (msg.content === "思考中..." || msg.content.startsWith("处理中") || msg.content.startsWith("思考"))
      ) {
        updated = true;
        return { ...msg, content };
      }
      return msg;
    });
    if (updated) {
      messagesByProject[projectId] = nextMessages;
    }
  }

  function stopSending(projectId: string) {
    isSending = false;
    if (sendingProjectId === projectId) {
      sendingProjectId = null;
    }
  }

  async function ensureProjectConversation(
    projectId: string,
    projectName: string,
  ): Promise<string> {
    if (conversationByProject[projectId]) {
      return conversationByProject[projectId];
    }

    const listResponse = await conversationApi.list(projectId);
    if (listResponse.success && listResponse.data && listResponse.data.length > 0) {
      conversationByProject[projectId] = listResponse.data[0].id;
      return listResponse.data[0].id;
    }
    if (!listResponse.success) {
      throw new Error(`加载会话失败: ${listResponse.error || "未知错误"}`);
    }

    const createResponse = await conversationApi.create(projectId, `${projectName} 对话`);
    if (createResponse.success && createResponse.data) {
      conversationByProject[projectId] = createResponse.data.id;
      return createResponse.data.id;
    }

    throw new Error(`创建会话失败: ${createResponse.error || "未知错误"}`);
  }

  async function loadConversationMessages(projectId: string, projectName: string) {
    const existingMessages = messagesByProject[projectId] || [];
    if (isSending && sendingProjectId === projectId && existingMessages.length > 0) {
      return;
    }

    const conversationId = await ensureProjectConversation(projectId, projectName);
    if (!conversationId) {
      messagesByProject[projectId] =
        existingMessages.length > 0 ? existingMessages : getDemoMessages(projectName);
      return;
    }

    const response = await conversationApi.getMessages(conversationId);
    if (response.success && response.data) {
      if (response.data.length > 0) {
        messagesByProject[projectId] = mapHistoryEntries(response.data);
      } else {
        messagesByProject[projectId] =
          existingMessages.length > 0 ? existingMessages : getDemoMessages(projectName);
      }
      return;
    }

    messagesByProject[projectId] =
      existingMessages.length > 0 ? existingMessages : getDemoMessages(projectName);
  }

  onMount(() => {
    tasksStore.initialize();
    let unsubscribeOpenClaw: (() => void) | null = null;

    void (async () => {
      // Step 1: Check if LLM is configured
      const configResponse = await appSettingsApi.isLlmConfigured();
      if (!configResponse.success || !configResponse.data) {
        console.log("[Chat] LLM not configured");
        llmConfigured = false;
        initError = "请先在设置中配置 LLM API Key";
        return;
      }

      llmConfigured = true;

      // Step 2: Start OpenClaw sidecar
      const startResponse = await openclawApi.start();
      if (!startResponse.success) {
        console.error("[Chat] Failed to start OpenClaw:", startResponse.error);
        initError = startResponse.error || "启动 AI 服务失败";
        return;
      }

      console.log("[Chat] OpenClaw sidecar started:", startResponse.data);

      // Step 3: Connect WebSocket
      try {
        await initializeOpenClaw();
        console.log("[Chat] OpenClaw WebSocket connected");

        // Subscribe to chat events from OpenClaw
        unsubscribeOpenClaw = subscribeToChatEvents((event: ChatEventPayload) => {
          console.log("[Chat] Received OpenClaw event:", event);
          const projectId = event.sessionKey;

          if (event.state === "delta" && event.message?.content) {
            updateLatestThinkingMessage(projectId, event.message.content);
          } else if (event.state === "final") {
            if (event.message?.content) {
              updateLatestThinkingMessage(projectId, event.message.content);
            }
            stopSending(projectId);
          } else if (event.state === "error") {
            updateLatestThinkingMessage(projectId, `错误：${event.errorMessage || "未知错误"}`);
            stopSending(projectId);
          } else if (event.state === "aborted") {
            updateLatestThinkingMessage(projectId, "对话已中止");
            stopSending(projectId);
          }
        });
      } catch (err) {
        console.error("[Chat] Failed to initialize OpenClaw:", err);
        connectionError.set(err instanceof Error ? err.message : "Connection failed");
        initError = err instanceof Error ? err.message : "连接 AI 服务失败";
      } finally {
        isInitializing = false;
      }
    })();

    // Initialize conversation for first project
    void (async () => {
      await new Promise((resolve) => setTimeout(resolve, 500));
      const store = get(projectsStore);
      if (store.projects.length > 0) {
        const pid = store.selectedProjectId || store.projects[0].id;
        if (!conversationByProject[pid]) {
          const result = await conversationApi.create(
            pid,
            `${store.projects.find((p) => p.id === pid)?.name || "项目"} 对话`,
          );
          if (result.success && result.data) {
            conversationByProject[pid] = result.data.id;
          }
        }
      }
    })();

    return () => {
      unsubscribeOpenClaw?.();
      disconnectOpenClaw();
      tasksStore.destroy();
    };
  });

  $effect(() => {
    if (!$selectedProject) {
      initializedProjectId = null;
      return;
    }
    if (initializedProjectId === $selectedProject.id) return;

    initializedProjectId = $selectedProject.id;
    tasksStore.setActiveTask(null);
    void loadConversationMessages($selectedProject.id, $selectedProject.name);
  });

  $effect(() => {
    const _ = messages;
    if (messagesContainer) {
      messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }
  });

  async function handleSubmit(prompt: string) {
    if (!$selectedProject) return;

    const projectId = $selectedProject.id;
    const currentMessages =
      messagesByProject[projectId] || getDemoMessages($selectedProject.name);

    // Add user message
    const userMessage: Message = {
      id: createLocalMessageId(),
      role: "user",
      content: prompt,
      timestamp: Date.now(),
    };
    messagesByProject[projectId] = [...currentMessages, userMessage];

    // Add thinking message
    const assistantMessage: Message = {
      id: createLocalMessageId(),
      role: "assistant",
      content: "思考中...",
      timestamp: Date.now(),
    };
    messagesByProject[projectId] = [...currentMessages, userMessage, assistantMessage];

    isSending = true;
    sendingProjectId = projectId;

    try {
      const conversationId = await ensureProjectConversation(projectId, $selectedProject.name);
      messagesByProject[projectId] = [
        ...currentMessages,
        userMessage,
        { ...assistantMessage, taskId: conversationId },
      ];

      // Send via OpenClaw WebSocket
      const result = await sendChatMessage(conversationId, prompt);
      console.log("[Chat] Message sent, runId:", result.runId);
    } catch (error) {
      stopSending(projectId);
      messagesByProject[projectId] = [
        ...currentMessages,
        userMessage,
        {
          ...assistantMessage,
          content: `错误：${error instanceof Error ? error.message : "发送消息失败"}`,
        },
      ];
    }
  }
</script>

<div class="chat-window">
  <div class="messages-container" bind:this={messagesContainer}>
    {#each messages as message (message.id)}
      <MessageBubble {message} />
    {/each}

    {#if $activeTask}
      <div class="task-panel">
        <TaskProgress task={$activeTask} />

        {#if $activeSubtasks.length > 0}
          <SubTaskProgress
            subtasks={$activeSubtasks}
            progress={$activeOrchestrationProgress}
            parallelGroups={$activeParallelGroups}
          />
        {/if}

        {#if $activeTask.fileChanges && $activeTask.fileChanges.length > 0}
          <FileTree fileChanges={$activeTask.fileChanges} />
        {/if}
      </div>
    {/if}
  </div>

  <InputArea
    disabled={isInputDisabled()}
    placeholder={getInputPlaceholder()}
    onSubmit={handleSubmit}
  />
</div>

<style>
  .chat-window {
    display: flex;
    flex-direction: column;
    height: 100%;
    background-color: var(--background);
  }

  .messages-container {
    flex: 1;
    overflow-y: auto;
    padding: 0.5rem 1.25rem 0.75rem;
    display: flex;
    flex-direction: column;
    gap: 1rem;
  }

  .task-panel {
    display: flex;
    flex-direction: column;
    gap: 1rem;
    padding: 0.75rem 0;
    background-color: transparent;
  }
</style>
