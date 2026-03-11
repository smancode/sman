import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface TaskProgressEvent {
  taskId: string;
  status: "pending" | "running" | "succeeded" | "failed";
  progress?: number;
  message?: string;
}

export interface ChatStreamEvent {
  conversationId: string;
  content: string;
  isComplete: boolean;
}

export type SmanEvent =
  | { type: "task:progress"; payload: TaskProgressEvent }
  | { type: "chat:stream"; payload: ChatStreamEvent };

export function subscribeToSmanEvents(
  handler: (event: SmanEvent) => void,
): Promise<UnlistenFn> {
  return listen<TaskProgressEvent | ChatStreamEvent>("sman-event", (event) => {
    // Cast the payload to SmanEvent
    handler(event.payload as unknown as SmanEvent);
  });
}
