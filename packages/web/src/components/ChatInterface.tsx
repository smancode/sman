/**
 * Chat Interface Component
 */

import { createSignal, onMount, onCleanup, For, Show, createEffect } from "solid-js"
import type { Component } from "solid-js"
import type { Message, Part } from "@smancode/core"
import { chatStore } from "../stores/chat"
import type { WebSocketClient } from "../utils/websocket"
import { MessageItem } from "./MessageItem"
import { PermissionDialog } from "./PermissionDialog"

interface ChatInterfaceProps {
  sessionId: string | null
  wsClient: WebSocketClient | null
  onCreateSession: () => Promise<string>
}

export const ChatInterface: Component<ChatInterfaceProps> = (props) => {
  const [input, setInput] = createSignal("")
  const [isLoading, setIsLoading] = createSignal(false)
  let messagesEndRef: HTMLDivElement | undefined
  let inputRef: HTMLTextAreaElement | undefined

  // Subscribe to WebSocket messages
  onMount(() => {
    if (props.wsClient) {
      const unsubscribe = props.wsClient.onMessage((message) => {
        switch (message.type) {
          case "stream_event":
            chatStore.handleStreamEvent(message.event)
            break
          case "permission_request":
            chatStore.setPendingPermission(message.request)
            break
          case "error":
            chatStore.setError(message.message)
            break
        }
      })

      onCleanup(unsubscribe)
    }
  })

  // Load messages when session changes
  createEffect(() => {
    const sessionId = props.sessionId
    if (sessionId) {
      props.wsClient?.subscribe(sessionId)
      loadMessages(sessionId)
    }
  })

  const loadMessages = async (sessionId: string) => {
    try {
      const response = await fetch(`/api/sessions/${sessionId}/messages`)
      const data = await response.json()
      chatStore.setMessages(data.messages || [])
      scrollToBottom()
    } catch (error) {
      console.error("Failed to load messages:", error)
    }
  }

  const scrollToBottom = () => {
    setTimeout(() => {
      messagesEndRef?.scrollIntoView({ behavior: "smooth" })
    }, 100)
  }

  const handleSubmit = async (e: Event) => {
    e.preventDefault()
    const text = input().trim()
    if (!text || chatStore.isStreaming()) return

    let sessionId = props.sessionId
    if (!sessionId) {
      sessionId = await props.onCreateSession()
    }

    setInput("")
    setIsLoading(true)

    // Add user message to UI immediately
    const userMessage: Message = {
      id: `temp-${Date.now()}`,
      sessionId,
      role: "user",
      parts: [{ type: "text", text }],
      timestamp: { created: Date.now(), updated: Date.now() },
    }
    chatStore.addMessage(userMessage)
    scrollToBottom()

    // Send to backend
    props.wsClient?.sendMessage(sessionId, text)

    setIsLoading(false)
  }

  const handleKeyDown = (e: KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault()
      handleSubmit(e)
    }
  }

  const handleAbort = () => {
    if (props.sessionId) {
      props.wsClient?.abort(props.sessionId)
    }
  }

  return (
    <div class="flex-1 flex flex-col min-h-0">
      {/* Messages Area */}
      <div class="flex-1 overflow-y-auto px-4 py-6">
        <Show when={!props.sessionId}>
          <div class="flex flex-col items-center justify-center h-full text-center">
            <div class="w-16 h-16 bg-gradient-to-br from-blue-500 to-purple-600 rounded-2xl flex items-center justify-center mb-6">
              <svg class="w-10 h-10 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
              </svg>
            </div>
            <h2 class="text-2xl font-semibold mb-2">欢迎使用 SmanCode</h2>
            <p class="text-gray-400 max-w-md">
              智能编程助手，帮助你更高效地编写代码。
              输入你的问题开始对话。
            </p>
          </div>
        </Show>

        <Show when={props.sessionId}>
          <div class="max-w-4xl mx-auto space-y-4">
            <For each={chatStore.messages()}>
              {(message) => (
                <MessageItem message={message} />
              )}
            </For>

            {/* Streaming response */}
            <Show when={chatStore.isStreaming() && chatStore.streamingText()}>
              <div class="flex gap-3 message-enter">
                <div class="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500 to-purple-600 flex-shrink-0 flex items-center justify-center">
                  <svg class="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
                  </svg>
                </div>
                <div class="flex-1 min-w-0">
                  <div class="prose prose-invert max-w-none">
                    <div class="whitespace-pre-wrap typing-cursor">
                      {chatStore.streamingText()}
                    </div>
                  </div>
                </div>
              </div>
            </Show>

            <div ref={messagesEndRef} />
          </div>
        </Show>
      </div>

      {/* Input Area */}
      <div class="border-t border-gray-800 bg-gray-900 p-4">
        <form onSubmit={handleSubmit} class="max-w-4xl mx-auto">
          <div class="relative">
            <textarea
              ref={inputRef}
              value={input()}
              onInput={(e) => setInput(e.currentTarget.value)}
              onKeyDown={handleKeyDown}
              placeholder="输入消息... (Shift+Enter 换行)"
              class="w-full bg-gray-800 border border-gray-700 rounded-xl px-4 py-3 pr-12 resize-none focus:outline-none focus:border-blue-500 transition-colors"
              rows={1}
              disabled={chatStore.isStreaming()}
            />

            <div class="absolute right-2 bottom-2 flex items-center gap-2">
              <Show when={chatStore.isStreaming()}>
                <button
                  type="button"
                  onClick={handleAbort}
                  class="p-2 bg-red-600 hover:bg-red-700 rounded-lg transition-colors"
                  title="停止生成"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 10a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1h-4a1 1 0 01-1-1v-4z" />
                  </svg>
                </button>
              </Show>

              <Show when={!chatStore.isStreaming()}>
                <button
                  type="submit"
                  disabled={!input().trim()}
                  class="p-2 bg-blue-600 hover:bg-blue-700 disabled:bg-gray-700 disabled:cursor-not-allowed rounded-lg transition-colors"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
                  </svg>
                </button>
              </Show>
            </div>
          </div>

          <div class="mt-2 text-xs text-gray-500 text-center">
            按 Enter 发送，Shift+Enter 换行
          </div>
        </form>
      </div>

      {/* Permission Dialog */}
      <Show when={chatStore.pendingPermission()}>
        <PermissionDialog
          request={chatStore.pendingPermission()!}
          onConfirm={(allow) => {
            const request = chatStore.pendingPermission()
            if (request && props.wsClient) {
              props.wsClient.confirmPermission(request.id, allow)
              chatStore.clearPendingPermission()
            }
          }}
        />
      </Show>

      {/* Error Toast */}
      <Show when={chatStore.error()}>
        <div class="fixed bottom-20 left-1/2 -translate-x-1/2 bg-red-600 text-white px-4 py-2 rounded-lg shadow-lg">
          {chatStore.error()}
          <button
            onClick={() => chatStore.clearError()}
            class="ml-2 hover:text-red-200"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      </Show>
    </div>
  )
}
