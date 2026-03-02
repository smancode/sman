/**
 * Permission Dialog Component
 */

import { Show } from "solid-js"
import type { Component } from "solid-js"

interface PermissionRequest {
  id: string
  permission: string
  patterns: string[]
  context?: Record<string, unknown>
}

interface PermissionDialogProps {
  request: PermissionRequest
  onConfirm: (allow: boolean) => void
}

export const PermissionDialog: Component<PermissionDialogProps> = (props) => {
  return (
    <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div class="bg-gray-900 border border-gray-700 rounded-xl max-w-md w-full mx-4 shadow-2xl">
        {/* Header */}
        <div class="p-4 border-b border-gray-800">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 rounded-full bg-yellow-600/20 flex items-center justify-center">
              <svg class="w-6 h-6 text-yellow-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            </div>
            <div>
              <h3 class="font-semibold text-lg">权限请求</h3>
              <p class="text-sm text-gray-400">Agent 请求执行以下操作</p>
            </div>
          </div>
        </div>

        {/* Content */}
        <div class="p-4">
          <div class="bg-gray-800 rounded-lg p-3 mb-4">
            <div class="text-sm font-medium text-gray-300 mb-2">
              权限类型
            </div>
            <div class="text-blue-400 font-mono">
              {props.request.permission}
            </div>
          </div>

          <div class="bg-gray-800 rounded-lg p-3 mb-4">
            <div class="text-sm font-medium text-gray-300 mb-2">
              匹配模式
            </div>
            <div class="space-y-1">
              {props.request.patterns.map(pattern => (
                <div class="text-sm font-mono text-gray-400">
                  {pattern}
                </div>
              ))}
            </div>
          </div>

          <Show when={props.request.context}>
            <div class="bg-gray-800 rounded-lg p-3">
              <div class="text-sm font-medium text-gray-300 mb-2">
                上下文
              </div>
              <pre class="text-xs text-gray-400 overflow-x-auto">
                {JSON.stringify(props.request.context, null, 2)}
              </pre>
            </div>
          </Show>
        </div>

        {/* Actions */}
        <div class="p-4 border-t border-gray-800 flex gap-3">
          <button
            onClick={() => props.onConfirm(false)}
            class="flex-1 px-4 py-2 bg-gray-700 hover:bg-gray-600 rounded-lg transition-colors"
          >
            拒绝
          </button>
          <button
            onClick={() => props.onConfirm(true)}
            class="flex-1 px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg transition-colors"
          >
            允许
          </button>
        </div>
      </div>
    </div>
  )
}
