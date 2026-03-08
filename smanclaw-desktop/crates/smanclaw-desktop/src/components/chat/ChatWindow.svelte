<script lang="ts">
    import { selectedProject } from "../../lib/stores/projects";
    import {
        tasksStore,
        activeTask,
        activeSubtasks,
        activeOrchestrationProgress,
        activeParallelGroups,
    } from "../../lib/stores/tasks";
    import { listen } from "@tauri-apps/api/event";
    import { conversationApi, projectApi } from "../../lib/api/tauri";
    import type { ProgressEvent, HistoryEntryRecord } from "../../lib/types";
    import MessageBubble from "./MessageBubble.svelte";
    import InputArea from "./InputArea.svelte";
    import TaskProgress from "../task/TaskProgress.svelte";
    import SubTaskProgress from "../task/SubTaskProgress.svelte";
    import FileTree from "../task/FileTree.svelte";
    import type { Message } from "../../lib/types";
    import { onMount } from "svelte";
    import { get } from "svelte/store";
    import { projectsStore } from "../../lib/stores/projects";
    import {
        extractDisplayableAssistantContent,
        latestDisplayableAssistantEntry,
        sanitizeAssistantContent,
        stripToolCallBlocks,
        shouldFinalizeAssistantReply,
    } from "../../lib/chat/assistantContent";

    // Messages per project (projectId -> messages)
    let messagesByProject = $state<Record<string, Message[]>>({});
    let conversationByProject = $state<Record<string, string>>({});
    let completionSignalAtByProject = $state<Record<string, number | null>>({});
    let progressTimelineByProject = $state<Record<string, string[]>>({});
    let isSending = $state(false);
    let initializedProjectId = $state<string | null>(null);
    let messagesContainer: HTMLDivElement = $state()!;
    const replyPollIntervalMs = 1500;
    const replyMaxWaitMs = 10 * 60 * 1000;
    const maxConsecutivePollFailures = 8;

    // Get current project's messages
    let messages = $derived<Message[]>(
        $selectedProject
            ? messagesByProject[$selectedProject.id] ||
                  getDemoMessages($selectedProject.name)
            : [],
    );

    // Demo message for new projects
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
        if (normalized === "assistant") {
            return "assistant";
        }
        if (normalized === "system") {
            return "system";
        }
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

    function withThinkingMessage(
        baseMessages: Message[],
        thinkingMessage: Message,
    ): Message[] {
        const withoutThinking = baseMessages.filter(
            (msg) => msg.id !== thinkingMessage.id,
        );
        return [...withoutThinking, thinkingMessage];
    }

    function resolveThinkingMessage(
        projectId: string,
        fallbackMessage: Message,
    ): Message {
        const projectMessages = messagesByProject[projectId];
        if (!projectMessages || projectMessages.length === 0) {
            return fallbackMessage;
        }
        const lastMessage = projectMessages[projectMessages.length - 1];
        if (
            lastMessage.role === "assistant" &&
            fallbackMessage.taskId &&
            lastMessage.taskId === fallbackMessage.taskId
        ) {
            return lastMessage;
        }
        return fallbackMessage;
    }

    function updateLatestThinkingMessage(projectId: string, content: string) {
        const projectMessages = messagesByProject[projectId];
        if (!projectMessages || projectMessages.length === 0) {
            return;
        }
        let updated = false;
        const nextMessages = projectMessages.map((msg, index) => {
            if (updated) {
                return msg;
            }
            if (
                index === projectMessages.length - 1 &&
                msg.role === "assistant" &&
                msg.taskId &&
                (msg.content === "思考中..." ||
                    msg.content.startsWith("处理中："))
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

    function truncateValue(value: string, maxLength = 80): string {
        if (value.length <= maxLength) {
            return value;
        }
        return `${value.slice(0, maxLength)}…`;
    }

    function progressTimelineLine(payload: ProgressEvent): string | null {
        if (payload.type === "task_started") {
            return "任务已开始";
        }
        if (payload.type === "progress") {
            const percent = Number.isFinite(payload.percent)
                ? Math.max(0, Math.min(100, Math.round(payload.percent * 100)))
                : 0;
            return `${payload.message}（${percent}%）`;
        }
        if (payload.type === "tool_call") {
            const argsText =
                payload.args === undefined
                    ? ""
                    : truncateValue(JSON.stringify(payload.args));
            return argsText
                ? `调用工具：${payload.tool} ${argsText}`
                : `调用工具：${payload.tool}`;
        }
        if (payload.type === "command_run") {
            return `执行命令：${truncateValue(payload.command)}`;
        }
        if (payload.type === "file_read") {
            return `读取文件：${payload.path}`;
        }
        if (payload.type === "file_written") {
            const actionLabel =
                payload.action === "created"
                    ? "创建"
                    : payload.action === "deleted"
                      ? "删除"
                      : "修改";
            return `${actionLabel}文件：${payload.path}`;
        }
        if (payload.type === "task_completed") {
            return "任务执行完成，正在整理最终答案";
        }
        if (payload.type === "task_failed") {
            return `任务失败：${payload.error}`;
        }
        return null;
    }

    function appendProgressTimeline(projectId: string, payload: ProgressEvent) {
        console.log("[Chat] appendProgressTimeline:", projectId, payload);
        const line = progressTimelineLine(payload);
        if (!line) {
            return;
        }
        const current = progressTimelineByProject[projectId] || [];
        if (current[current.length - 1] === line) {
            return;
        }
        const next = [...current, line].slice(-8);
        progressTimelineByProject = {
            ...progressTimelineByProject,
            [projectId]: next,
        };
    }

    function renderThinkingContent(
        projectId: string,
        elapsedSeconds?: number,
    ): string {
        const title =
            typeof elapsedSeconds === "number" && elapsedSeconds > 0
                ? `处理中：等待模型响应（${elapsedSeconds}s）`
                : "处理中：任务执行中";
        const timeline = progressTimelineByProject[projectId] || [];
        if (timeline.length === 0) {
            return title;
        }
        return `${title}\n\n${timeline.map((line) => `- ${line}`).join("\n")}`;
    }

    function resolveCompletedOutput(content: string): string {
        const extracted = extractDisplayableAssistantContent(content);
        if (extracted && extracted.trim().length > 0) {
            return extracted.trim();
        }
        const stripped = stripToolCallBlocks(content).trim();
        if (stripped.length > 0) {
            return stripped;
        }
        return "执行完成：未生成可展示的最终答复，你可以直接重试。";
    }

    function clearProgressTimeline(projectId: string) {
        if (!progressTimelineByProject[projectId]) {
            return;
        }
        progressTimelineByProject = {
            ...progressTimelineByProject,
            [projectId]: [],
        };
    }

    async function ensureProjectConversation(
        projectId: string,
        projectName: string,
    ): Promise<string> {
        if (conversationByProject[projectId]) {
            return conversationByProject[projectId];
        }

        const listResponse = await conversationApi.list(projectId);
        if (
            listResponse.success &&
            listResponse.data &&
            listResponse.data.length > 0
        ) {
            const currentConversationId = listResponse.data[0].id;
            conversationByProject[projectId] = currentConversationId;
            return currentConversationId;
        }
        if (!listResponse.success) {
            throw new Error(
                `加载会话失败: ${listResponse.error || "未知错误"}`,
            );
        }

        const createResponse = await conversationApi.create(
            projectId,
            `${projectName} 对话`,
        );
        if (createResponse.success && createResponse.data) {
            conversationByProject[projectId] = createResponse.data.id;
            return createResponse.data.id;
        }

        throw new Error(`创建会话失败: ${createResponse.error || "未知错误"}`);
    }

    async function loadConversationMessages(
        projectId: string,
        projectName: string,
    ) {
        const conversationId = await ensureProjectConversation(
            projectId,
            projectName,
        );
        if (!conversationId) {
            messagesByProject[projectId] = getDemoMessages(projectName);
            return;
        }

        const response = await conversationApi.getMessages(conversationId);
        if (response.success && response.data) {
            messagesByProject[projectId] =
                response.data.length > 0
                    ? mapHistoryEntries(response.data)
                    : getDemoMessages(projectName);
            return;
        }

        messagesByProject[projectId] = getDemoMessages(projectName);
    }

    async function waitForAssistantReply(
        projectId: string,
        projectName: string,
        conversationId: string,
        userEntryId?: string,
        thinkingMessage?: Message,
    ) {
        const maxAttempts = Math.ceil(replyMaxWaitMs / replyPollIntervalMs);
        const startedAt = Date.now();
        let consecutivePollFailures = 0;
        let stableAssistantPollCount = 0;
        let latestAssistantSignature: string | null = null;
        for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
            if (!isSending) {
                return;
            }
            await new Promise((resolve) =>
                setTimeout(resolve, replyPollIntervalMs),
            );
            if (thinkingMessage) {
                const elapsedSeconds = Math.max(
                    1,
                    Math.floor((Date.now() - startedAt) / 1000),
                );
                updateLatestThinkingMessage(
                    projectId,
                    renderThinkingContent(projectId, elapsedSeconds),
                );
            }
            const response = await conversationApi.getMessages(conversationId);
            if (!response.success || !response.data) {
                consecutivePollFailures += 1;
                if (consecutivePollFailures >= maxConsecutivePollFailures) {
                    if (thinkingMessage) {
                        updateLatestThinkingMessage(
                            projectId,
                            `通讯异常：${response.error || "轮询会话失败"}。已停止等待，你可以直接重试。`,
                        );
                    }
                    clearProgressTimeline(projectId);
                    isSending = false;
                    return;
                }
                continue;
            }
            consecutivePollFailures = 0;

            const mappedMessages =
                response.data.length > 0
                    ? mapHistoryEntries(response.data)
                    : getDemoMessages(projectName);

            if (!userEntryId) {
                const latestAssistant = latestDisplayableAssistantEntry(
                    response.data,
                    normalizeRole,
                );
                if (latestAssistant) {
                    messagesByProject[projectId] = mappedMessages;
                    clearProgressTimeline(projectId);
                    isSending = false;
                    return;
                }
                messagesByProject[projectId] = thinkingMessage
                    ? withThinkingMessage(
                          mappedMessages,
                          resolveThinkingMessage(projectId, thinkingMessage),
                      )
                    : mappedMessages;
                continue;
            }

            const userIndex = response.data.findIndex(
                (entry) => entry.id === userEntryId,
            );
            if (userIndex < 0) {
                continue;
            }

            const entriesAfterUser = response.data.slice(userIndex + 1);
            const latestAssistantAfterUser = latestDisplayableAssistantEntry(
                entriesAfterUser,
                normalizeRole,
            );
            const assistantEntriesAfterUser = entriesAfterUser.filter(
                (entry) => normalizeRole(entry.role) === "assistant",
            );

            if (latestAssistantAfterUser) {
                const signature = [
                    latestAssistantAfterUser.id,
                    latestAssistantAfterUser.timestamp,
                    latestAssistantAfterUser.content,
                ].join("|");
                if (latestAssistantSignature === signature) {
                    stableAssistantPollCount += 1;
                } else {
                    latestAssistantSignature = signature;
                    stableAssistantPollCount = 1;
                }
                const latestAssistantTimestampMs = new Date(
                    latestAssistantAfterUser.timestamp,
                ).getTime();
                const completionSignalAt =
                    completionSignalAtByProject[projectId] ?? null;
                const shouldFinalize = shouldFinalizeAssistantReply({
                    latestAssistantTimestampMs,
                    completionSignalAtMs: completionSignalAt,
                    stableAssistantPollCount,
                    elapsedMs: Date.now() - startedAt,
                });
                if (shouldFinalize) {
                    messagesByProject[projectId] = mappedMessages;
                    clearProgressTimeline(projectId);
                    isSending = false;
                    return;
                }
            } else {
                stableAssistantPollCount = 0;
                latestAssistantSignature = null;
            }

            const pendingAssistantIds = new Set(
                assistantEntriesAfterUser.map((entry) => entry.id),
            );
            const mappedMessagesWithoutPendingAssistants =
                mappedMessages.filter(
                    (message) => !pendingAssistantIds.has(message.id),
                );

            messagesByProject[projectId] = thinkingMessage
                ? withThinkingMessage(
                      mappedMessagesWithoutPendingAssistants,
                      resolveThinkingMessage(projectId, thinkingMessage),
                  )
                : mappedMessagesWithoutPendingAssistants;
        }

        if (thinkingMessage) {
            updateLatestThinkingMessage(
                projectId,
                "执行中断：等待最终回复超时。已停止等待，你可以直接重试。",
            );
        }
        clearProgressTimeline(projectId);
        isSending = false;
    }

    onMount(() => {
        tasksStore.initialize();
        let unlistenProgress: (() => void) | null = null;
        let unlistenChatMessage: (() => void) | null = null;

        void (async () => {
            // Listen for chat messages from backend
            unlistenChatMessage = await listen<any>(
                "chat-message",
                (event) => {
                    console.log("[ChatMessage] Received:", event.payload);
                    const payload = event.payload;
                    if (!$selectedProject) return;
                    const projectId = $selectedProject.id;
                    
                    const role = payload.role === "user" ? "user" : "assistant";
                    const newMessage: Message = {
                        id: crypto.randomUUID(),
                        role: role,
                        content: payload.content,
                        timestamp: Date.now(),
                    };
                    
                    const currentMessages = messagesByProject[projectId] || [];
                    messagesByProject[projectId] = [...currentMessages, newMessage];
                },
            );

            unlistenProgress = await listen<ProgressEvent>(
                "progress",
                (event) => {
                    const payload = event.payload;
                    console.log("[Progress] Received progress event:", JSON.stringify(payload));

                    if (payload.type === "task_completed" && $selectedProject) {
                        const projectId = $selectedProject.id;
                        appendProgressTimeline(projectId, payload);
                        const output = payload.result.output?.trim();
                        if (output && output.length > 0) {
                            updateLatestThinkingMessage(
                                projectId,
                                resolveCompletedOutput(output),
                            );
                            clearProgressTimeline(projectId);
                            isSending = false;
                        } else {
                            updateLatestThinkingMessage(
                                projectId,
                                renderThinkingContent(projectId),
                            );
                        }
                        completionSignalAtByProject[projectId] = Date.now();
                    }

                    if (payload.type === "task_failed" && $selectedProject) {
                        isSending = false;
                        const projectId = $selectedProject.id;
                        appendProgressTimeline(projectId, payload);
                        completionSignalAtByProject[projectId] = Date.now();
                        updateLatestThinkingMessage(
                            projectId,
                            `执行中断：${payload.error}`,
                        );
                        clearProgressTimeline(projectId);
                        return;
                    }

                    if (isSending && $selectedProject) {
                        const projectId = $selectedProject.id;
                        appendProgressTimeline(projectId, payload);
                        if (progressTimelineLine(payload)) {
                            updateLatestThinkingMessage(
                                projectId,
                                renderThinkingContent(projectId),
                            );
                        }
                    }
                },
            );
        })();

        // Auto-send test skill on mount
        void (async () => {
            // Wait for project to be available
            await new Promise(resolve => setTimeout(resolve, 500));
            const store = get(projectsStore);
            console.log("[Chat] Auto-send check - projects:", store.projects.length, "selected:", store.selectedProjectId);
            if (store.projects.length > 0) {
                const pid = store.selectedProjectId || store.projects[0].id;
                console.log("[Chat] Auto-sending to project:", pid);
                let convId = conversationByProject[pid];
                if (!convId) {
                    const result = await conversationApi.create(pid);
                    if (result.success && result.data) {
                        convId = result.data.id;
                        conversationByProject[pid] = convId;
                    }
                }
                // if (convId) {
                //     handleSubmit("/caijing");
                // }
            }
        })();

        return () => {
            unlistenProgress?.();
            unlistenChatMessage?.();
            tasksStore.destroy();
        };
    });

    $effect(() => {
        if (!$selectedProject) {
            initializedProjectId = null;
            return;
        }

        if (initializedProjectId === $selectedProject.id) {
            return;
        }

        initializedProjectId = $selectedProject.id;
        tasksStore.setActiveTask(null);
        void loadConversationMessages(
            $selectedProject.id,
            $selectedProject.name,
        );
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
        completionSignalAtByProject[projectId] = null;
        clearProgressTimeline(projectId);
        const currentMessages =
            messagesByProject[projectId] ||
            getDemoMessages($selectedProject.name);
        const userMessage: Message = {
            id: createLocalMessageId(),
            role: "user",
            content: prompt,
            timestamp: Date.now(),
        };
        const updatedMessages = [...currentMessages, userMessage];
        messagesByProject[projectId] = updatedMessages;
        isSending = true;

        const assistantMessage: Message = {
            id: createLocalMessageId(),
            role: "assistant",
            content: "思考中...",
            timestamp: Date.now(),
        };
        messagesByProject[projectId] = [...updatedMessages, assistantMessage];

        try {
            const conversationId = await ensureProjectConversation(
                projectId,
                $selectedProject.name,
            );
            messagesByProject[projectId] = [
                ...updatedMessages,
                { ...assistantMessage, taskId: conversationId },
            ];

            const response = await conversationApi.sendMessage(
                conversationId,
                prompt,
            );
            if (!response.success) {
                isSending = false;
                messagesByProject[projectId] = [
                    ...updatedMessages,
                    {
                        ...assistantMessage,
                        content: `错误：${response.error || "发送消息失败"}`,
                    },
                ];
                return;
            }

            void waitForAssistantReply(
                projectId,
                $selectedProject.name,
                conversationId,
                response.data?.id,
                { ...assistantMessage, taskId: conversationId },
            );
        } catch (error) {
            isSending = false;
            messagesByProject[projectId] = [
                ...updatedMessages,
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
        disabled={isSending}
        placeholder={$selectedProject
            ? "请描述你要构建的内容..."
            : "输入 / 或 \ 触发技能 (请先添加项目 D:\\work\\aipro\\H5)"}
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
