# Testing Patterns

**Analysis Date:** 2026-03-14

## Test Framework

**Runner:**
- **Status:** Not configured
- No test framework installed (no Jest, Vitest, or similar)
- No test configuration files present
- `package.json` has no test-related scripts

**Assertion Library:**
- Not applicable (no test framework)

**Run Commands:**
```bash
# Currently not available - no test command defined
pnpm test    # Not configured
```

## Test File Organization

**Location:**
- **Status:** No test files in project source
- No `*.test.ts`, `*.spec.ts`, `__tests__/` directories
- Only test files present are in `node_modules` (from dependencies)

**Naming:**
- Not applicable (no tests)

**Structure:**
- Not applicable (no tests)

## Test Structure

**Suite Organization:**
- Not applicable

**Patterns:**
- Not applicable

## Mocking

**Framework:** Not applicable

**Patterns:**
- Not applicable

**What to Mock:**
- Not applicable

**What NOT to Mock:**
- Not applicable

## Fixtures and Factories

**Test Data:**
- Not applicable

**Location:**
- Not applicable

## Coverage

**Requirements:** None enforced

**View Coverage:**
```bash
# Not available
```

## Test Types

**Unit Tests:**
- **Status:** Not present
- Should cover: utility functions, message extractors, formatters

**Integration Tests:**
- **Status:** Not present
- Should cover: GatewayClient WebSocket communication, store actions

**E2E Tests:**
- **Status:** Not used

## Testing Recommendations

Based on the current codebase structure, the following testing approach is recommended:

### Recommended Test Framework Setup

```bash
# Install Vitest (matches Vite build tooling)
pnpm add -D vitest @testing-library/react @testing-library/jest-dom jsdom
```

### Recommended Test File Locations

```
src/
├── lib/
│   ├── utils.ts
│   └── __tests__/
│       └── utils.test.ts          # Unit tests for utility functions
├── features/
│   └── chat/
│       ├── message-utils.ts
│       └── __tests__/
│           └── message-utils.test.ts
├── stores/
│   └── __tests__/
│       └── chat.test.ts           # Unit tests for store logic
server/
├── utils/
│   └── __tests__/
│       └── logger.test.ts
```

### Recommended vitest.config.ts

```typescript
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}', 'server/**/*.{test,spec}.ts'],
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
```

### Recommended Package.json Scripts

```json
{
  "scripts": {
    "test": "vitest run",
    "test:watch": "vitest",
    "test:coverage": "vitest run --coverage"
  }
}
```

## Priority Areas for Testing

### High Priority

1. **`src/features/chat/message-utils.ts`** - Pure functions with clear inputs/outputs
   - `extractText()` - handles multiple content formats
   - `extractThinking()` - extracts thinking blocks
   - `extractImages()` - extracts image data
   - `extractToolUse()` - handles both Anthropic and OpenAI formats
   - `formatTimestamp()` - timestamp formatting

2. **`src/lib/utils.ts`** - Utility functions
   - `formatRelativeTime()` - date formatting
   - `formatDuration()` - duration formatting
   - `truncate()` - string manipulation

3. **`src/lib/gateway-client.ts`** - WebSocket client
   - Connection lifecycle
   - RPC request/response handling
   - Error handling and reconnection logic

### Medium Priority

4. **`src/stores/chat.ts`** - Zustand store
   - Session management (`loadSessions`, `switchSession`, `deleteSession`)
   - Message handling (`sendMessage`, `handleChatEvent`)
   - State transitions during streaming

5. **`server/utils/logger.ts`** - Server logger
   - Log level formatting
   - Structured output

6. **`server/process-manager.ts`** - Process management
   - Process lifecycle
   - Restart logic
   - Health checks

### Lower Priority

7. **React Components** - Use React Testing Library
   - `ChatMessage` - rendering different message types
   - `ConnectionSettings` - form interactions
   - `ErrorBoundary` - error handling

## Example Test Patterns

### Utility Function Testing

```typescript
// src/features/chat/__tests__/message-utils.test.ts
import { describe, it, expect } from 'vitest'
import { extractText, extractThinking, formatTimestamp } from '../message-utils'

describe('extractText', () => {
  it('returns empty string for null/undefined input', () => {
    expect(extractText(null)).toBe('')
    expect(extractText(undefined)).toBe('')
  })

  it('extracts string content directly', () => {
    const message = { role: 'assistant', content: 'Hello world' }
    expect(extractText(message)).toBe('Hello world')
  })

  it('joins text blocks from array content', () => {
    const message = {
      role: 'assistant',
      content: [
        { type: 'text', text: 'First paragraph' },
        { type: 'text', text: 'Second paragraph' },
      ]
    }
    expect(extractText(message)).toBe('First paragraph\n\nSecond paragraph')
  })

  it('strips Gateway metadata from user messages', () => {
    const message = {
      role: 'user',
      content: '[Fri 2026-02-13 22:39 GMT+8] Hello [media attached: file.png (image/png) | /path]'
    }
    expect(extractText(message)).toBe('Hello')
  })
})
```

### Store Testing

```typescript
// src/stores/__tests__/chat.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useChatStore } from '../chat'

describe('ChatStore', () => {
  beforeEach(() => {
    // Reset store state
    useChatStore.setState({
      messages: [],
      loading: false,
      error: null,
      currentSessionKey: 'agent:main:main',
    })
  })

  it('starts with empty messages', () => {
    const { messages } = useChatStore.getState()
    expect(messages).toEqual([])
  })

  it('switches session and clears messages', () => {
    useChatStore.setState({ messages: [{ role: 'user', content: 'test' }] })
    useChatStore.getState().switchSession('agent:main:new')
    expect(useChatStore.getState().currentSessionKey).toBe('agent:main:new')
    expect(useChatStore.getState().messages).toEqual([])
  })

  it('clears error', () => {
    useChatStore.setState({ error: 'Something went wrong' })
    useChatStore.getState().clearError()
    expect(useChatStore.getState().error).toBeNull()
  })
})
```

### React Component Testing

```typescript
// src/components/common/__tests__/ErrorBoundary.test.tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ErrorBoundary } from '../ErrorBoundary'

function ThrowError(): never {
  throw new Error('Test error')
}

describe('ErrorBoundary', () => {
  it('renders children when no error', () => {
    render(
      <ErrorBoundary>
        <div>Safe content</div>
      </ErrorBoundary>
    )
    expect(screen.getByText('Safe content')).toBeInTheDocument()
  })

  it('renders error UI when error occurs', () => {
    // Suppress console.error for this test
    vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <ErrorBoundary>
        <ThrowError />
      </ErrorBoundary>
    )

    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    expect(screen.getByText('Test error')).toBeInTheDocument()
  })
})
```

---

*Testing analysis: 2026-03-14*
