/**
 * Sidebar Component
 */

import { createSignal, For, Show, onMount } from "solid-js"
import type { Component } from "solid-js"
import type { Session } from "@smancode/core"

interface SidebarProps {
  currentSession: string | null
  onSelectSession: (id: string) => void
  onClose: () => void
}

export const Sidebar: Component<SidebarProps> = (props) => {
  const [sessions, setSessions] = createSignal<Session[]>([])
  const [loading, setLoading] = createSignal(true)

  onMount(async () => {
    try {
      const response = await fetch("/api/sessions?projectId=default&limit=20")
      const data = await response.json()
      setSessions(data.sessions || [])
    } catch (error) {
      console.error("Failed to load sessions:", error)
    } finally {
      setLoading(false)
    }
  })

  const formatDate = (timestamp: number) => {
    const date = new Date(timestamp)
    const now = new Date()
    const diff = now.getTime() - date.getTime()

    if (diff < 60000) return "刚刚"
    if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
    if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小时前`
    return date.toLocaleDateString()
  }

  const groupByDate = (sessions: Session[]) => {
    const groups = new Map<string, Session[]>()
    const today = new Date().toDateString()
    const yesterday = new Date(Date.now() - 86400000).toDateString()

    for (const session of sessions) {
      const date = new Date(session.timestamp.created).toDateString()
      let label: string

      if (date === today) label = "今天"
      else if (date === yesterday) label = "昨天"
      else label = new Date(session.timestamp.created).toLocaleDateString()

      if (!groups.has(label)) groups.set(label, [])
      groups.get(label)!.push(session)
    }

    return groups
  }

  return (
    <aside class="w-64 bg-gray-900 border-r border-gray-800 flex flex-col">
      {/* Header */}
      <div class="p-4 border-b border-gray-800">
        <div class="flex items-center justify-between">
          <h2 class="font-semibold text-lg">会话</h2>
          <button
            onClick={props.onClose}
            class="p-1 hover:bg-gray-800 rounded"
            title="关闭侧边栏"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
            </svg>
          </button>
        </div>
      </div>

      {/* Session List */}
      <div class="flex-1 overflow-y-auto">
        <Show when={loading()}>
          <div class="p-4 text-center text-gray-500">
            加载中...
          </div>
        </Show>

        <Show when={!loading()}>
          <For each={Array.from(groupByDate(sessions()).entries())}>
            {([label, groupSessions]) => (
              <div class="py-2">
                <div class="px-4 py-1 text-xs text-gray-500 uppercase tracking-wider">
                  {label}
                </div>
                <For each={groupSessions}>
                  {(session) => (
                    <button
                      class={`w-full px-4 py-2 text-left hover:bg-gray-800 transition-colors ${
                        props.currentSession === session.id ? "bg-gray-800" : ""
                      }`}
                      onClick={() => props.onSelectSession(session.id)}
                    >
                      <div class="text-sm font-medium truncate">
                        {session.title || "新会话"}
                      </div>
                      <div class="text-xs text-gray-500 mt-0.5">
                        {formatDate(session.timestamp.created)}
                      </div>
                    </button>
                  )}
                </For>
              </div>
            )}
          </For>

          <Show when={sessions().length === 0}>
            <div class="p-4 text-center text-gray-500">
              暂无会话记录
            </div>
          </Show>
        </Show>
      </div>

      {/* Footer */}
      <div class="p-4 border-t border-gray-800">
        <div class="text-xs text-gray-500 text-center">
          SmanCode v0.1.0
        </div>
      </div>
    </aside>
  )
}
