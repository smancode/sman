import { createSignal, onMount, onCleanup, For, Show } from "solid-js"
import type { Component } from "solid-js"
import { ChatInterface } from "./components/ChatInterface"
import { Sidebar } from "./components/Sidebar"
import { Header } from "./components/Header"
import { WebSocketClient } from "./utils/websocket"

const App: Component = () => {
  const [sidebarOpen, setSidebarOpen] = createSignal(true)
  const [currentSession, setCurrentSession] = createSignal<string | null>(null)
  const [connected, setConnected] = createSignal(false)
  let wsClient: WebSocketClient | null = null

  onMount(() => {
    wsClient = new WebSocketClient("ws://localhost:4000/ws")
    wsClient.onConnect(() => setConnected(true))
    wsClient.onDisconnect(() => setConnected(false))
  })

  onCleanup(() => {
    wsClient?.close()
  })

  return (
    <div class="flex h-screen bg-gray-950 text-gray-100">
      {/* Sidebar */}
      <Show when={sidebarOpen()}>
        <Sidebar
          currentSession={currentSession()}
          onSelectSession={setCurrentSession}
          onClose={() => setSidebarOpen(false)}
        />
      </Show>

      {/* Main Content */}
      <div class="flex-1 flex flex-col min-w-0">
        {/* Header */}
        <Header
          connected={connected()}
          sidebarOpen={sidebarOpen()}
          onToggleSidebar={() => setSidebarOpen(!sidebarOpen())}
          sessionId={currentSession()}
        />

        {/* Chat Interface */}
        <ChatInterface
          sessionId={currentSession()}
          wsClient={wsClient}
          onCreateSession={async () => {
            const response = await fetch("/api/sessions", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ projectId: "default" }),
            })
            const data = await response.json()
            setCurrentSession(data.session.id)
            return data.session.id
          }}
        />
      </div>
    </div>
  )
}

export default App
