# shared/ — Shared TypeScript Types (Cross-Module)

## Purpose
TypeScript type definitions shared between smanbase main app and the bazaar server. Defines the bazaar protocol message types.

## Key Files

| File | Purpose |
|---|---|
| `bazaar-types.ts` | Bazaar protocol message types (TS source) |
| `bazaar-types.d.ts` | Generated JavaScript declaration file |
| `bazaar-types.js` | Generated JavaScript implementation |

## Bazaar Protocol Types (`bazaar-types.ts`)

Core message types used across the bazaar network:

```typescript
// Agent registration
AgentRegister, AgentHeartbeat, AgentCapabilities

// Task lifecycle
TaskOffer, TaskBid, TaskAccept, TaskReject, TaskResult

// Capability discovery
CapabilityQuery, CapabilityMatch

// Audit
AuditEntry, ReputationUpdate
```

## Dependencies
- Imported by `bazaar/src/protocol.ts` for type safety
- Imported by `server/bazaar-client.ts`, `server/bazaar-bridge.ts`
- Both `smanbase` and `bazaar` are compiled separately; `shared/` provides the contract
