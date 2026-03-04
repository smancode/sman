<script lang="ts">
  import { selectedProject } from '../../lib/stores/projects';
  import { tasksStore, activeTask, activeSubtasks, activeOrchestrationProgress, activeParallelGroups } from '../../lib/stores/tasks';
  import MessageBubble from './MessageBubble.svelte';
  import InputArea from './InputArea.svelte';
  import TaskProgress from '../task/TaskProgress.svelte';
  import SubTaskProgress from '../task/SubTaskProgress.svelte';
  import FileTree from '../task/FileTree.svelte';
  import type { Message } from '../../lib/types';
  import { onMount } from 'svelte';

  let messages = $state<Message[]>([]);
  let messagesContainer: HTMLDivElement = $state()!;

  // Mock messages for demo
  const demoMessages: Message[] = [
    {
      id: '1',
      role: 'assistant',
      content: 'Welcome to SmanClaw! Select a project and describe what you want to build or modify.',
      timestamp: Date.now() - 60000
    }
  ];

  // Initialize store event listeners on mount
  onMount(() => {
    tasksStore.initialize();

    return () => {
      tasksStore.destroy();
    };
  });

  $effect(() => {
    if ($selectedProject) {
      // Load history for selected project
      messages = [...demoMessages];
    }
  });

  $effect(() => {
    // Auto-scroll when messages change
    if (messagesContainer) {
      messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }
  });

  async function handleSubmit(prompt: string) {
    if (!$selectedProject) return;

    // Add user message
    const userMessage: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      content: prompt,
      timestamp: Date.now()
    };
    messages = [...messages, userMessage];

    // Execute orchestrated task (uses automatic decomposition)
    const taskId = await tasksStore.executeOrchestratedTask($selectedProject.id, prompt);

    if (taskId) {
      // Add assistant message placeholder
      const assistantMessage: Message = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: 'Analyzing your request and orchestrating subtasks...',
        timestamp: Date.now(),
        taskId
      };
      messages = [...messages, assistantMessage];
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

        <!-- Show subtask progress if we have orchestrated subtasks -->
        {#if $activeSubtasks.length > 0}
          <SubTaskProgress
            subtasks={$activeSubtasks}
            progress={$activeOrchestrationProgress}
            parallelGroups={$activeParallelGroups}
          />
        {/if}

        {#if $activeTask.fileChanges.length > 0}
          <FileTree fileChanges={$activeTask.fileChanges} />
        {/if}
      </div>
    {/if}
  </div>

  <InputArea
    disabled={!$selectedProject || $activeTask?.status === 'running'}
    placeholder={$selectedProject ? 'Describe what you want to build...' : 'Select a project first...'}
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
