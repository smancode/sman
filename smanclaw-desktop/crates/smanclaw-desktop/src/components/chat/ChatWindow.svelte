<script lang="ts">
  console.log('ChatWindow component loading...');
  import { selectedProject } from '../../lib/stores/projects';
  import { tasksStore, tasks, activeTask, activeSubtasks, activeOrchestrationProgress, activeParallelGroups } from '../../lib/stores/tasks';
  import { listen, type UnlistenFn } from '@tauri-apps/api/event';
  import type { ProgressEvent } from '../../lib/types';
  import MessageBubble from './MessageBubble.svelte';
  import InputArea from './InputArea.svelte';
  import TaskProgress from '../task/TaskProgress.svelte';
  import SubTaskProgress from '../task/SubTaskProgress.svelte';
  import FileTree from '../task/FileTree.svelte';
  import type { Message } from '../../lib/types';
  import { onMount } from 'svelte';

  // Messages per project (projectId -> messages)
  let messagesByProject = $state<Record<string, Message[]>>({});
  let messagesContainer: HTMLDivElement = $state()!;

  // Get current project's messages
  let messages = $derived<Message[]>(
    $selectedProject
      ? messagesByProject[$selectedProject.id] || getDemoMessages($selectedProject.name)
      : []
  );

  // Demo message for new projects
  function getDemoMessages(projectName: string): Message[] {
    return [
      {
        id: '1',
        role: 'assistant',
        content: `Welcome to ${projectName}! Describe what you want to build or modify.`,
        timestamp: Date.now() - 60000
      }
    ];
  }

  // Initialize store and event listeners on mount
  onMount(async () => {
    console.log('[ChatWindow] Initializing...');
    
    tasksStore.initialize();

    // TEST: Emit a test event to verify event system works
    setTimeout(() => {
      console.log('[ChatWindow] Test: Emitting test event...');
      // @ts-ignore
      if (window.__TAURI__) {
        console.log('[ChatWindow] Tauri detected');
      } else {
        console.log('[ChatWindow] Tauri NOT detected');
      }
    }, 2000);

    // Listen for progress events (TaskCompleted, TaskFailed)
    const unlistenProgress = await listen<ProgressEvent>('progress', (event) => {
      console.log('[ChatWindow] Received progress event:', event.payload);
      
      const payload = event.payload;
      
      // Handle task completion
      if (payload.type === 'task_completed' && $selectedProject) {
        const projectId = $selectedProject.id;
        const projectMessages = messagesByProject[projectId];
        if (!projectMessages) return;

        const taskId = payload.result.task_id;
        const output = payload.result.output;
        
        console.log('[ChatWindow] Task completed:', taskId, 'output:', output.substring(0, 50));

        // Update the corresponding message
        const updatedMessages = projectMessages.map(msg => {
          if (msg.taskId === taskId && msg.content === 'Thinking...') {
            return { ...msg, content: output };
          }
          return msg;
        });

        messagesByProject[projectId] = updatedMessages;
      }
      
      // Handle task failure - Note: TaskFailed doesn't include task_id in current backend
      // We need to track the active task and match it
      if (payload.type === 'task_failed' && $selectedProject) {
        const projectId = $selectedProject.id;
        const projectMessages = messagesByProject[projectId];
        if (!projectMessages) return;

        const error = payload.error;
        
        // Find the most recent "Thinking..." message and update it
        // This is a workaround since TaskFailed doesn't include task_id
        let updated = false;
        const updatedMessages = projectMessages.map(msg => {
          if (!updated && msg.taskId && msg.content === 'Thinking...') {
            updated = true;
            return { ...msg, content: `Error: ${error}` };
          }
          return msg;
        });

        if (updated) {
          console.log('[ChatWindow] Task failed, updated message with error:', error);
          messagesByProject[projectId] = updatedMessages;
        }
      }
    });

    return () => {
      console.log('[ChatWindow] Cleaning up...');
      unlistenProgress();
      tasksStore.destroy();
    };
  });

  // Clear active task when switching projects
  $effect(() => {
    if ($selectedProject) {
      tasksStore.setActiveTask(null);
      
      if (!messagesByProject[$selectedProject.id]) {
        messagesByProject[$selectedProject.id] = getDemoMessages($selectedProject.name);
      }
    }
  });

  // Auto-scroll when messages change
  $effect(() => {
    const _ = messages;
    if (messagesContainer) {
      messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }
  });

  $effect(() => {
    if (!$selectedProject) return;

    const projectId = $selectedProject.id;
    const projectMessages = messagesByProject[projectId];
    if (!projectMessages || projectMessages.length === 0) return;

    let updated = false;
    const updatedMessages = projectMessages.map((msg) => {
      if (!msg.taskId || msg.content !== 'Thinking...') return msg;

      const task = $tasks.find((t) => t.id === msg.taskId);
      if (!task) return msg;

      if (task.status === 'completed' && task.output) {
        updated = true;
        return { ...msg, content: task.output };
      }

      if (task.status === 'failed') {
        const errorMsg = task.error || task.output || 'Task failed';
        updated = true;
        return { ...msg, content: `Error: ${errorMsg}` };
      }

      return msg;
    });

    if (updated) {
      messagesByProject[projectId] = updatedMessages;
    }
  });

  async function handleSubmit(prompt: string) {
    if (!$selectedProject) return;

    const projectId = $selectedProject.id;
    const currentMessages = messagesByProject[projectId] || getDemoMessages($selectedProject.name);

    // Add user message
    const userMessage: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      content: prompt,
      timestamp: Date.now()
    };

    const updatedMessages = [...currentMessages, userMessage];
    messagesByProject[projectId] = updatedMessages;

    // Execute task
    const taskId = await tasksStore.executeTask(projectId, prompt);

    if (taskId) {
      // Add assistant message placeholder
      const assistantMessage: Message = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: 'Thinking...',
        timestamp: Date.now(),
        taskId
      };
      messagesByProject[projectId] = [...updatedMessages, assistantMessage];
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
