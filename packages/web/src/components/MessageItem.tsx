/**
 * Message Item Component
 */

import { For, Show } from "solid-js"
import type { Component } from "solid-js"
import type { Message, Part } from "@smancode/core"

interface MessageItemProps {
  message: Message
}

export const MessageItem: Component<MessageItemProps> = (props) => {
  const isUser = () => props.message.role === "user"

  const renderPart = (part: Part, index: number) => {
    switch (part.type) {
      case "text":
        return (
          <div class="prose prose-invert max-w-none">
            <div class="whitespace-pre-wrap">{part.text}</div>
          </div>
        )

      case "reasoning":
        return (
          <details class="bg-gray-800/50 rounded-lg p-3 text-sm">
            <summary class="cursor-pointer text-gray-400 hover:text-gray-300">
              推理过程
            </summary>
            <div class="mt-2 text-gray-300 whitespace-pre-wrap">
              {part.text}
            </div>
          </details>
        )

      case "tool_call":
        return (
          <div class="bg-gray-800 rounded-lg p-3 text-sm">
            <div class="flex items-center gap-2 text-yellow-400 mb-2">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
              <span class="font-medium">{part.name}</span>
            </div>
            <pre class="text-xs text-gray-400 overflow-x-auto">
              {JSON.stringify(part.arguments, null, 2)}
            </pre>
          </div>
        )

      case "tool_result":
        return (
          <div class={`rounded-lg p-3 text-sm ${part.error ? "bg-red-900/30" : "bg-green-900/30"}`}>
            <div class={`flex items-center gap-2 mb-2 ${part.error ? "text-red-400" : "text-green-400"}`}>
              <Show when={part.error}>
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </Show>
              <Show when={!part.error}>
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                </svg>
              </Show>
              <span class="font-medium">{part.name}</span>
            </div>
            <pre class="text-xs text-gray-300 overflow-x-auto max-h-40">
              {part.result}
            </pre>
          </div>
        )

      case "compaction":
        return (
          <div class="bg-blue-900/30 rounded-lg p-3 text-sm">
            <div class="flex items-center gap-2 text-blue-400 mb-2">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4" />
              </svg>
              <span class="font-medium">上下文压缩</span>
            </div>
            <p class="text-gray-300">{part.summary}</p>
            <p class="text-xs text-gray-500 mt-1">
              {part.originalTokens} → {part.compressedTokens} tokens
            </p>
          </div>
        )

      case "file":
        return (
          <div class="bg-gray-800 rounded-lg p-3 text-sm">
            <div class="flex items-center gap-2 text-gray-400">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
              </svg>
              <span>{part.name}</span>
            </div>
          </div>
        )

      default:
        return null
    }
  }

  return (
    <div class={`flex gap-3 message-enter ${isUser() ? "flex-row-reverse" : ""}`}>
      {/* Avatar */}
      <Show when={isUser()}>
        <div class="w-8 h-8 rounded-lg bg-gray-700 flex-shrink-0 flex items-center justify-center">
          <svg class="w-5 h-5 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
          </svg>
        </div>
      </Show>
      <Show when={!isUser()}>
        <div class="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500 to-purple-600 flex-shrink-0 flex items-center justify-center">
          <svg class="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
          </svg>
        </div>
      </Show>

      {/* Content */}
      <div class={`flex-1 min-w-0 ${isUser() ? "text-right" : ""}`}>
        <div class={`inline-block max-w-full ${isUser() ? "bg-blue-600" : "bg-gray-800"} rounded-xl px-4 py-3`}>
          <For each={props.message.parts}>
            {(part, index) => (
              <Show when={index() > 0}>
                <div class="mt-3" />
              </Show>
            )}
            {(part) => renderPart(part, 0)}
          </For>
        </div>

        {/* Timestamp */}
        <div class={`text-xs text-gray-500 mt-1 ${isUser() ? "text-right" : ""}`}>
          {new Date(props.message.timestamp.created).toLocaleTimeString()}
        </div>
      </div>
    </div>
  )
}
