# Web Access Ref-Based Element Interaction

## Overview
Enhanced element interaction using accessibility tree references (ref) as primary selector, CSS selector as fallback.

## Core Changes
**Files**: `server/web-access/cdp-engine.ts`, `server/web-access/mcp-server.ts`

### Accessibility Tree Caching
```typescript
// CdpEngine
private cachedAxNodes = new Map<string, AxNode[]>();
```

- **Trigger**: Populated on every `takeFullSnapshot()`
- **Scope**: Per-tab cache
- **Lifecycle**: Cleared on tab close
- **Purpose**: Resolve AX refs to DOM elements

### Ref Format
- AX tree serialization appends `ref=<nodeId>` to interactive elements:
  ```
  [button] "Submit" ref=e123
  [textbox] "Username" placeholder="Enter" ref=e456
  ```

## Element Resolution

### Ref → DOM Mapping
```typescript
private async resolveAxRef(tabId: string, ref: string):
  Promise<{ objectId: string; sessionId: string }>
```

**Steps**:
1. Find node in cached AX tree by `nodeId`
2. Get `backendDOMNodeId` from AX node
3. Call `DOM.resolveNode` to get `objectId`
4. Return object ID + CDP session ID

**Error Cases**:
- No cached tree → "Call snapshot or navigate first"
- Ref not found → "Refresh with snapshot"
- Virtual element (no backend DOM) → "Virtual element"

## Interaction Methods

### Click by Ref
```typescript
private async clickByRef(tabId: string, ref: string):
  Promise<PageSnapshot>
```

**Implementation**:
```javascript
(el) => {
  el.scrollIntoView({ block: 'center' });
  el.click();
  return true;
}
```

**Post-action**: `waitForDomStable()` → `takeFullSnapshot()`

### Fill by Ref
```typescript
private async fillByRef(tabId: string, ref: string, value: string):
  Promise<PageSnapshot>
```

**Implementation**:
```javascript
(el) => {
  el.focus();
  el.value = <value>;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
}
```

**Post-action**: `waitForDomStable()` → `takeFullSnapshot()`

## MCP Tool Signatures

### Click Tool
```typescript
{
  tab_id: string;
  ref?: string;        // Preferred
  selector?: string;   // Fallback
}
```

### Fill Tool
```typescript
{
  tab_id: string;
  value: string;
  ref?: string;        // Preferred
  selector?: string;   // Fallback
}
```

**Validation**: Must provide either `ref` or `selector`

## Browser Engine Interface
**File**: `server/web-access/browser-engine.ts`

```typescript
// Updated signatures
click(tabId: string, options: {
  ref?: string;
  selector?: string;
}): Promise<PageSnapshot>;

fill(tabId: string, value: string, options: {
  ref?: string;
  selector?: string;
}): Promise<PageSnapshot>;
```

## Accessibility Tree Serialization
**Method**: `CdpEngine.serializeAxTree()`

**Enhancement**: Attach ref to:
- Interactive roles (button, link, textbox, etc.)
- Named semantic elements (headings, landmarks)

**Format**: `[role] "name" [key=value, ...] ref=<nodeId>`

## Performance Considerations
- **Cache hit**: O(1) ref lookup
- **Cache miss**: Full snapshot required (expensive)
- **Memory**: One tree per active tab (~10-50KB each)
- **Stale risk**: Ref invalidates after DOM mutation

## Migration Path
- **Old MCP tools**: CSS selector only (still supported)
- **New MCP tools**: Ref preferred, selector fallback
- **Recommendation**: Always prefer ref from snapshot

## Testing Strategy
1. Get snapshot → parse ref from `accessibilityTree`
2. Use ref in click/fill → verify action succeeded
3. Check new snapshot has expected changes
