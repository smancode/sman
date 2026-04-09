# WS batch.items

## Signature
```
WS message: {
  type: "batch.items",
  taskId: string,
  status?: string,
  offset?: number,
  limit?: number
}
```

## Business Flow
Returns paginated batch items for a task. Optional `status` filter (pending/running/done/failed/cancelled). Used by the UI to display per-item progress.

## Called Services
`batchStore.listItems()`

## Source
`server/index.ts`
