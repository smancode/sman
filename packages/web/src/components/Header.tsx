/**
 * Header Component
 */

import { Show } from "solid-js"
import type { Component } from "solid-js"

interface HeaderProps {
  connected: boolean
  sidebarOpen: boolean
  onToggleSidebar: () => void
  sessionId: string | null
}

export const Header: Component<HeaderProps> = (props) => {
  return (
    <header class="h-14 bg-gray-900 border-b border-gray-800 flex items-center px-4">
      {/* Left side */}
      <div class="flex items-center gap-3">
        <Show when={!props.sidebarOpen}>
          <button
            onClick={props.onToggleSidebar}
            class="p-2 hover:bg-gray-800 rounded transition-colors"
            title="打开侧边栏"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
        </Show>

        <div class="flex items-center gap-2">
          <div class="w-8 h-8 bg-gradient-to-br from-blue-500 to-purple-600 rounded-lg flex items-center justify-center">
            <svg class="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
            </svg>
          </div>
          <span class="font-semibold text-lg">SmanCode</span>
        </div>
      </div>

      {/* Center - Session info */}
      <div class="flex-1 flex justify-center">
        <Show when={props.sessionId}>
          <div class="text-sm text-gray-400">
            会话: <span class="text-gray-200">{props.sessionId?.slice(0, 8)}</span>
          </div>
        </Show>
      </div>

      {/* Right side */}
      <div class="flex items-center gap-3">
        {/* Connection status */}
        <div class="flex items-center gap-2 text-sm">
          <div class={`w-2 h-2 rounded-full ${
            props.connected ? "bg-green-500" : "bg-red-500"
          }`} />
          <span class="text-gray-400">
            {props.connected ? "已连接" : "未连接"}
          </span>
        </div>

        {/* Settings */}
        <button
          class="p-2 hover:bg-gray-800 rounded transition-colors"
          title="设置"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
        </button>
      </div>
    </header>
  )
}
