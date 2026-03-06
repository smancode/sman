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
    import { conversationApi } from "../../lib/api/tauri";
    import type { ProgressEvent, HistoryEntryRecord } from "../../lib/types";
    import MessageBubble from "./MessageBubble.svelte";
    import InputArea from "./InputArea.svelte";
    import TaskProgress from "../task/TaskProgress.svelte";
    import SubTaskProgress from "../task/SubTaskProgress.svelte";
    import FileTree from "../task/FileTree.svelte";
    import type { Message } from "../../lib/types";
    import { onMount } from "svelte";

    // Messages per project (projectId -> messages)
    let messagesByProject = $state<Record<string, Message[]>>({});
    let conversationByProject = $state<Record<string, string>>({});
    let isSending = $state(false);
    let initializedProjectId = $state<string | null>(null);
    let messagesContainer: HTMLDivElement = $state()!;

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
                content: `Welcome to ${projectName}! Describe what you want to build or modify.`,
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
            content: entry.content,
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
        const maxAttempts = 90;
        for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
            await new Promise((resolve) => setTimeout(resolve, 1000));
            const response = await conversationApi.getMessages(conversationId);
            if (!response.success || !response.data) {
                continue;
            }

            const mappedMessages =
                response.data.length > 0
                    ? mapHistoryEntries(response.data)
                    : getDemoMessages(projectName);

            if (!userEntryId) {
                const hasAssistant = response.data.some(
                    (entry) => normalizeRole(entry.role) === "assistant",
                );
                if (hasAssistant) {
                    messagesByProject[projectId] = mappedMessages;
                    isSending = false;
                    return;
                }
                messagesByProject[projectId] = thinkingMessage
                    ? withThinkingMessage(mappedMessages, thinkingMessage)
                    : mappedMessages;
                continue;
            }

            const userIndex = response.data.findIndex(
                (entry) => entry.id === userEntryId,
            );
            if (userIndex < 0) {
                continue;
            }

            const hasAssistantAfterUser = response.data
                .slice(userIndex + 1)
                .some((entry) => normalizeRole(entry.role) === "assistant");

            if (hasAssistantAfterUser) {
                messagesByProject[projectId] = mappedMessages;
                isSending = false;
                return;
            }

            messagesByProject[projectId] = thinkingMessage
                ? withThinkingMessage(mappedMessages, thinkingMessage)
                : mappedMessages;
        }

        isSending = false;
    }

    onMount(() => {
        tasksStore.initialize();
        let unlistenProgress: (() => void) | null = null;

        void (async () => {
            unlistenProgress = await listen<ProgressEvent>(
                "progress",
                (event) => {
                    const payload = event.payload;

                    if (payload.type === "task_completed" && $selectedProject) {
                        if (isSending) {
                            isSending = false;
                            void loadConversationMessages(
                                $selectedProject.id,
                                $selectedProject.name,
                            );
                        }
                    }

                    if (payload.type === "task_failed" && $selectedProject) {
                        isSending = false;
                        const projectId = $selectedProject.id;
                        const projectMessages = messagesByProject[projectId];
                        if (!projectMessages) return;
                        const error = payload.error;
                        let updated = false;
                        const updatedMessages = projectMessages.map((msg) => {
                            if (
                                !updated &&
                                msg.taskId &&
                                msg.content === "Thinking..."
                            ) {
                                updated = true;
                                return { ...msg, content: `Error: ${error}` };
                            }
                            return msg;
                        });

                        if (updated) {
                            messagesByProject[projectId] = updatedMessages;
                        }
                    }
                },
            );
        })();

        return () => {
            unlistenProgress?.();
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
            content: "Thinking...",
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
                        content: `Error: ${response.error || "Failed to send message"}`,
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
                    content: `Error: ${error instanceof Error ? error.message : "Failed to send message"}`,
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
        disabled={!$selectedProject || isSending}
        placeholder={$selectedProject
            ? "Describe what you want to build..."
            : "Select a project first..."}
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
        padding: 1rem;
        display: flex;
        flex-direction: column;
        gap: 1rem;
    }

    .task-panel {
        display: flex;
        flex-direction: column;
        gap: 1rem;
        padding: 1rem;
        background-color: var(--surface);
        border-radius: 8px;
        border: 1px solid var(--border);
    }
</style>
