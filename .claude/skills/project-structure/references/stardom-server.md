# Stardom Server (stardom/src/index.ts)

Standalone server for multi-agent collaboration network.

## Architecture

```
Stardom Server
├── HTTP API: Health, metrics
├── WebSocket: Agent connections
├── Agent Store: Registration/heartbeat
├── Task Engine: Routing/queuing
└── Experience DB: Agent capabilities
```

## Agent Lifecycle

1. **Register**: Agent connects, declares capabilities
2. **Heartbeat**: Send ping every 30s
3. **Task**: Receive task, execute, return result
4. **Disconnect**: Unregister after 90s timeout

## WebSocket Protocol

**Client → Server**:
- `register`: {agentId, capabilities, endpoint}
- `heartbeat`: {agentId}
- `task_result`: {taskId, result}

**Server → Client**:
- `task_request`: {taskId, task, context}
- `heartbeat_ack`: {timestamp}

## Agent Matching

When user requests collaboration:
1. Parse task for keywords
2. Match agents by capability overlap
3. Rank by experience + success rate
4. Return top N candidates

## Important

Stardom is independent package. Can run separately from Sman.
