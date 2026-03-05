import { invoke } from '@tauri-apps/api/core';

interface Task {
  id: string;
  projectId: string;
  input: string;
  status: string;
  output?: string;
  error?: string;
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
}

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
}

async function testExecuteTask() {
  console.log('=== Testing execute_task ===');
  
  try {
    // 测试发送消息
    const result = await invoke<Task>('execute_task', {
      project_id: 'test-project',
      input: 'hi'
    });
    
    console.log('Response:', JSON.stringify(result, null, 2));
    console.log('Task ID:', result.id);
    console.log('Task Status:', result.status);
    console.log('Task Input:', result.input);
    
    // 等待一段时间让任务执行
    await new Promise(resolve => setTimeout(resolve, 5000));
    
    // 查询任务状态
    const taskStatus = await invoke<Task | null>('get_task', {
      task_id: result.id
    });
    
    console.log('Task Status Result:', JSON.stringify(taskStatus, null, 2));
    
    if (taskStatus) {
      console.log('✅ Task found');
      console.log('Status:', taskStatus.status);
      console.log('Output:', taskStatus.output);
    } else {
      console.log('❌ Task not found');
    }
  } catch (error) {
    console.error('❌ Error:', error);
  }
}

// 运行测试
testExecuteTask().then(() => {
  console.log('=== Test Complete ===');
}).catch(console.error);
