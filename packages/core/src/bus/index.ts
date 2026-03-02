/**
 * SmanCode Core - Event Bus 实现
 */

import type { EventBus, Event, EventListener, SubscribeOptions } from "./interface"

/** 监听器条目 */
interface ListenerEntry {
  listener: EventListener
  options: SubscribeOptions
  id: symbol
}

/** 事件总线实现 */
export class EventBusImpl implements EventBus {
  private listeners = new Map<string, Set<ListenerEntry>>()
  private globalListeners = new Set<EventListener>()

  async publish<T = unknown>(type: string, payload: T, metadata?: Record<string, unknown>): Promise<void> {
    const event: Event<T> = {
      type,
      payload,
      timestamp: Date.now(),
      metadata,
    }

    // 触发特定事件监听器
    const typeListeners = this.listeners.get(type)
    if (typeListeners) {
      const sortedListeners = Array.from(typeListeners).sort(
        (a, b) => (b.options.priority ?? 0) - (a.options.priority ?? 0)
      )

      const toRemove: ListenerEntry[] = []

      for (const entry of sortedListeners) {
        try {
          await entry.listener(event)
        } catch (error) {
          console.error(`Error in event listener for ${type}:`, error)
        }
        if (entry.options.once) {
          toRemove.push(entry)
        }
      }

      // 延迟删除，避免在迭代过程中修改集合
      for (const entry of toRemove) {
        typeListeners.delete(entry)
      }
    }

    // 触发全局监听器
    for (const listener of this.globalListeners) {
      try {
        await listener(event)
      } catch (error) {
        console.error("Error in global event listener:", error)
      }
    }
  }

  subscribe<T = unknown>(
    type: string,
    listener: EventListener<T>,
    options?: SubscribeOptions
  ): () => void {
    const opts: SubscribeOptions = {
      once: options?.once ?? false,
      priority: options?.priority ?? 0,
    }

    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set())
    }

    const wrappedListener = listener as EventListener
    const entry: ListenerEntry = {
      listener: wrappedListener,
      options: opts,
      id: Symbol(),
    }
    const listenerSet = this.listeners.get(type)!
    listenerSet.add(entry)

    return () => {
      listenerSet.delete(entry)
    }
  }

  unsubscribe(type: string, listener: EventListener): void {
    const typeListeners = this.listeners.get(type)
    if (typeListeners) {
      for (const item of typeListeners) {
        if (item.listener === listener) {
          typeListeners.delete(item)
        }
      }
    }
  }

  subscribeAll(listener: EventListener): () => void {
    this.globalListeners.add(listener)
    return () => this.globalListeners.delete(listener)
  }

  hasListeners(type: string): boolean {
    return (this.listeners.get(type)?.size ?? 0) > 0
  }

  listenerCount(type: string): number {
    return this.listeners.get(type)?.size ?? 0
  }

  clear(): void {
    this.listeners.clear()
    this.globalListeners.clear()
  }
}

/** 创建事件总线 */
export function createEventBus(): EventBus {
  return new EventBusImpl()
}
