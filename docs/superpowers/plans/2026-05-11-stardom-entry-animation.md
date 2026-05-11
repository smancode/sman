# Stardom Entry Animation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cyber-style starfield warp animation as the entry screen for the Stardom (协作星图) feature, controlled by a dev-mode toggle in sman-server admin UI.

**Architecture:** Two-project change. sman-server gets a `hub_settings` table and admin toggle. sman client gets a `StardomEntry` wrapper component with Canvas 2D starfield animation that checks the toggle via hub proxy. Toggle off → animation loops with "coming soon" message. Toggle on → 3s animation then 2s fade into StardomDashboard.

**Tech Stack:** Canvas 2D API, React 19, Zustand, Express, better-sqlite3, @tanstack/react-query

**Spec:** `docs/superpowers/specs/2026-05-11-stardom-entry-animation-design.md`

---

## File Structure

### sman-server (`/Users/nasakim/projects/sman-server/`)

| File | Action | Responsibility |
|------|--------|----------------|
| `src/db.ts` | Modify | Add `hub_settings` table + `getSetting`/`setSetting` methods |
| `src/routes/admin.ts` | Modify | Add GET/PUT `/admin/stardom-dev-mode` endpoints |
| `src/routes/hub-api.ts` | Modify | Add POST `/api/hub/stardom-dev-mode` endpoint |
| `web/src/components/DashboardTab.tsx` | Modify | Embed stardom dev-mode toggle |
| `web/src/api.ts` | Modify | Add `getStardomDevMode`/`setStardomDevMode` methods |
| `web/src/locales/zh-CN.json` | Modify | Add dashboard.stardomDevMode keys |
| `web/src/locales/en-US.json` | Modify | Add dashboard.stardomDevMode keys |
| `src/__tests__/db.test.ts` | Modify | Add hub_settings tests |

### sman client (`/Users/nasakim/projects/sman/`)

| File | Action | Responsibility |
|------|--------|----------------|
| `src/features/stardom/StardomEntry.tsx` | Create | Starfield animation + toggle check + fade transition |
| `src/features/stardom/StarfieldCanvas.tsx` | Create | Pure Canvas 2D starfield warp animation |
| `src/app/routes.tsx` | Modify | Change stardom route to StardomEntry |
| `src/queries/use-hub.ts` | Modify | Add `useStardomDevMode()` hook |
| `src/locales/zh-CN.json` | Modify | Add `stardom.entry.comingSoon` key |
| `src/locales/en-US.json` | Modify | Add `stardom.entry.comingSoon` key |

---

## Chunk 1: sman-server Backend

### Task 1: Add hub_settings table to HubDB

**Files:**
- Modify: `/Users/nasakim/projects/sman-server/src/db.ts` (initTables + new methods)
- Test: `/Users/nasakim/projects/sman-server/src/__tests__/db.test.ts`

- [ ] **Step 1: Write failing test for getSetting / setSetting**

In `db.test.ts`, add inside the existing `describe('HubDB', ...)` block:

```typescript
describe('hub_settings', () => {
  it('should return default value for stardom_dev_mode', () => {
    const val = db.getSetting('stardom_dev_mode');
    expect(val).toBe('0');
  });

  it('should update and retrieve setting', () => {
    db.setSetting('stardom_dev_mode', '1');
    expect(db.getSetting('stardom_dev_mode')).toBe('1');
  });

  it('should return undefined for unknown key', () => {
    expect(db.getSetting('nonexistent')).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/nasakim/projects/sman-server && npx vitest run src/__tests__/db.test.ts`
Expected: FAIL — `db.getSetting is not a function`

- [ ] **Step 3: Implement getSetting / setSetting in HubDB**

In `src/db.ts`, add to `initTables()`:

```typescript
CREATE TABLE IF NOT EXISTS hub_settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT DEFAULT (datetime('now', 'localtime'))
);
INSERT OR IGNORE INTO hub_settings (key, value) VALUES ('stardom_dev_mode', '0');
```

Add methods to `HubDB` class:

```typescript
getSetting(key: string): string | undefined {
  const row = this.db.prepare('SELECT value FROM hub_settings WHERE key = ?').get(key) as { value: string } | undefined;
  return row?.value;
}

setSetting(key: string, value: string): void {
  this.db.prepare(`
    INSERT INTO hub_settings (key, value, updated_at) VALUES (?, ?, datetime('now', 'localtime'))
    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
  `).run(key, value);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/nasakim/projects/sman-server && npx vitest run src/__tests__/db.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/nasakim/projects/sman-server && git add src/db.ts src/__tests__/db.test.ts && git commit -m "feat(db): add hub_settings table with getSetting/setSetting"
```

---

### Task 2: Add stardom-dev-mode admin endpoints

**Files:**
- Modify: `/Users/nasakim/projects/sman-server/src/routes/admin.ts`

- [ ] **Step 1: Add GET and PUT endpoints**

The `createAdminRouter` signature needs `HubDB` — it already receives it as `db`. Add before the `return router` statement:

```typescript
// Stardom dev-mode toggle
router.get('/stardom-dev-mode', (_req: Request, res: Response) => {
  const val = db.getSetting('stardom_dev_mode');
  res.json({ enabled: val === '1' });
});

router.put('/stardom-dev-mode', (req: Request, res: Response) => {
  const { enabled } = req.body;
  if (typeof enabled !== 'boolean') {
    res.status(400).json({ error: 'enabled (boolean) required' });
    return;
  }
  db.setSetting('stardom_dev_mode', enabled ? '1' : '0');
  res.json({ ok: true, enabled });
});
```

- [ ] **Step 2: Commit**

```bash
cd /Users/nasakim/projects/sman-server && git add src/routes/admin.ts && git commit -m "feat(admin): add stardom-dev-mode GET/PUT endpoints"
```

---

### Task 3: Add stardom-dev-mode hub-api endpoint

**Files:**
- Modify: `/Users/nasakim/projects/sman-server/src/routes/hub-api.ts`

The `createHubApiRouter` currently receives `roomDB, taskDB, psk, taskEngine`. It needs `HubDB` for the settings. We add `hubDB` parameter.

- [ ] **Step 1: Update function signature and add endpoint**

In `src/routes/hub-api.ts`, change function signature:

```typescript
export function createHubApiRouter(roomDB: RoomDB, taskDB: TaskDB, psk: string, taskEngine: TaskEngine | undefined, hubDB: HubDB): Router {
```

Add import at top:

```typescript
import type { HubDB } from '../db.js';
```

Add endpoint before `return router`:

```typescript
// Stardom dev-mode (read-only for clients)
router.post('/stardom-dev-mode', (_req: Request, res: Response) => {
  const val = hubDB.getSetting('stardom_dev_mode');
  res.json({ payload: encrypt({ enabled: val === '1' }, psk) });
});
```

- [ ] **Step 2: Update index.ts call site**

In `src/index.ts` line 98, change:

```typescript
app.use('/api/hub', createHubApiRouter(roomDB, taskDB, PSK, taskEngine, db));
```

- [ ] **Step 3: Build check**

Run: `cd /Users/nasakim/projects/sman-server && npx tsc --noEmit`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
cd /Users/nasakim/projects/sman-server && git add src/routes/hub-api.ts src/index.ts && git commit -m "feat(hub-api): add stardom-dev-mode endpoint for client query"
```

---

### Task 4: Add admin UI toggle

**Files:**
- Modify: `/Users/nasakim/projects/sman-server/web/src/api.ts`
- Modify: `/Users/nasakim/projects/sman-server/web/src/components/DashboardTab.tsx`
- Modify: `/Users/nasakim/projects/sman-server/web/src/locales/zh-CN.json`
- Modify: `/Users/nasakim/projects/sman-server/web/src/locales/en-US.json`

- [ ] **Step 1: Add i18n keys**

In `web/src/locales/zh-CN.json`, add before closing `}`:

```json
,
  "dashboard.stardomDevMode": { "text": "协作星图开发模式" },
  "dashboard.stardomDevModeHint": { "text": "开启后客户端可进入协作星图页面" }
```

In `web/src/locales/en-US.json`, add before closing `}`:

```json
,
  "dashboard.stardomDevMode": { "text": "Stardom Dev Mode" },
  "dashboard.stardomDevModeHint": { "text": "When enabled, clients can access the Stardom page" }
```

- [ ] **Step 2: Add api methods**

In `web/src/api.ts`, add to the `api` object:

```typescript
getStardomDevMode: (token: string) => request('/stardom-dev-mode', token),
setStardomDevMode: (token: string, enabled: boolean) =>
  request('/stardom-dev-mode', token, { method: 'PUT', body: JSON.stringify({ enabled }) }),
```

- [ ] **Step 3: Add toggle to DashboardTab**

In `DashboardTab.tsx`, add a toggle section after the stat cards. The component needs state for the toggle:

```tsx
const [stardomDev, setStardomDev] = useState(false);
const [loadingToggle, setLoadingToggle] = useState(false);

useEffect(() => {
  if (!token) return;
  (async () => {
    try {
      const data = await api.getStardomDevMode(token) as { enabled: boolean };
      setStardomDev(data.enabled);
    } catch { /* ignore */ }
  })();
}, [token]);

const handleToggleStardom = async (enabled: boolean) => {
  setLoadingToggle(true);
  try {
    await api.setStardomDevMode(token!, enabled);
    setStardomDev(enabled);
  } catch { /* ignore */ }
  setLoadingToggle(false);
};
```

In the JSX, after the `page-grid` div:

```tsx
<div className="stat-card" style={{ gridColumn: '1 / -1', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
  <div>
    <div style={{ fontWeight: 600 }}>{t('dashboard.stardomDevMode')}</div>
    <div style={{ fontSize: '0.8em', opacity: 0.6 }}>{t('dashboard.stardomDevModeHint')}</div>
  </div>
  <button
    className={`btn ${stardomDev ? 'btn-primary' : 'btn-secondary'}`}
    disabled={loadingToggle}
    onClick={() => handleToggleStardom(!stardomDev)}
  >
    {stardomDev ? t('broadcast.active') : t('broadcast.inactive')}
  </button>
</div>
```

- [ ] **Step 4: Verify dev server builds**

Run: `cd /Users/nasakim/projects/sman-server/web && npx vite build 2>&1 | tail -5`
Expected: Build succeeds

- [ ] **Step 5: Commit**

```bash
cd /Users/nasakim/projects/sman-server && git add web/src/ && git commit -m "feat(admin-ui): add stardom dev-mode toggle on dashboard"
```

---

## Chunk 2: sman Client

### Task 5: Add i18n keys to sman client

**Files:**
- Modify: `/Users/nasakim/projects/sman/src/locales/zh-CN.json`
- Modify: `/Users/nasakim/projects/sman/src/locales/en-US.json`

- [ ] **Step 1: Find the stardom section and add entry key**

In `zh-CN.json`, find the last `stardom.` key and add after it:

```json
"stardom.entry.comingSoon": "星际航道探索中，敬请期待",
```

In `en-US.json`, find the last `stardom.` key and add after it:

```json
"stardom.entry.comingSoon": "Exploring new starlanes... Stay tuned",
```

- [ ] **Step 2: Commit**

```bash
cd /Users/nasakim/projects/sman && git add src/locales/zh-CN.json src/locales/en-US.json && git commit -m "feat(i18n): add stardom entry animation i18n keys"
```

---

### Task 6: Add useStardomDevMode hook

**Files:**
- Modify: `/Users/nasakim/projects/sman/src/queries/use-hub.ts`

- [ ] **Step 1: Add hook at end of file**

```typescript
export function useStardomDevMode() {
  return useQuery({
    queryKey: ['stardom-dev-mode'],
    queryFn: async () => {
      const { data: raw } = await hubFetch('/stardom-dev-mode', {
        method: 'POST',
        body: JSON.stringify({}),
        throwOnNetworkError: true,
      });
      if (!raw || typeof raw !== 'object') return false;
      return (raw as { enabled?: boolean }).enabled === true;
    },
    staleTime: 0,
    retry: false,
  });
}
```

Note: `hubFetch` returns `{ data, unreachable }` (not raw response). All hub-api endpoints require POST with encrypted body (hub proxy converts to POST + encrypts). The 3-second timeout is built into `hubFetch`. On timeout/unreachable with `throwOnNetworkError: true`, it throws → react-query sets `isError = true` → `data` stays `undefined` → maps to "dev mode off" in StardomEntry.

- [ ] **Step 2: Commit**

```bash
cd /Users/nasakim/projects/sman && git add src/queries/use-hub.ts && git commit -m "feat(hub): add useStardomDevMode query hook"
```

---

### Task 7: Create StarfieldCanvas component

**Files:**
- Create: `/Users/nasakim/projects/sman/src/features/stardom/StarfieldCanvas.tsx`

- [ ] **Step 1: Create the Canvas animation component**

This is a pure Canvas 2D starfield warp effect. No external dependencies.

```tsx
import { useEffect, useRef } from 'react';

interface Star {
  x: number;
  y: number;
  z: number;
  color: string;
}

const STAR_COUNT = 300;
const SPEED = 0.02;
const BG_COLOR = '#050510';
const CYAN_COLORS = ['#00f0ff', '#00c8ff', '#00a0ff', '#40e0ff', '#80f0ff'];
const MAGENTA_COLORS = ['#ff00ff', '#ff40ff', '#ff80ff', '#ffffff'];
const TRAIL_ALPHA = 0.15;
const ENGINE_PULSE_SPEED = 0.002;

function createStar(width: number, height: number, resetZ = false): Star {
  const isCyan = Math.random() < 0.8;
  const palette = isCyan ? CYAN_COLORS : MAGENTA_COLORS;
  return {
    x: (Math.random() - 0.5) * width * 2,
    y: (Math.random() - 0.5) * height * 2,
    z: resetZ ? Math.random() * 0.01 : Math.random() * 2,
    color: palette[Math.floor(Math.random() * palette.length)],
  };
}

export function StarfieldCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animRef = useRef<number>(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    let width = window.innerWidth;
    let height = window.innerHeight;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.scale(dpr, dpr);

    const stars: Star[] = Array.from({ length: STAR_COUNT }, () => createStar(width, height));
    let frame = 0;

    const resize = () => {
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.scale(dpr, dpr);
    };
    window.addEventListener('resize', resize);

    const draw = () => {
      frame++;
      // Motion blur via semi-transparent overlay
      ctx.fillStyle = `rgba(5, 5, 16, ${TRAIL_ALPHA})`;
      ctx.fillRect(0, 0, width, height);

      const cx = width / 2;
      const cy = height / 2;

      // Engine glow at center
      const pulse = 0.6 + 0.4 * Math.sin(frame * ENGINE_PULSE_SPEED);
      const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, 120 * pulse);
      grad.addColorStop(0, `rgba(0, 240, 255, ${0.08 * pulse})`);
      grad.addColorStop(0.5, `rgba(0, 200, 255, ${0.03 * pulse})`);
      grad.addColorStop(1, 'rgba(0, 200, 255, 0)');
      ctx.fillStyle = grad;
      ctx.fillRect(cx - 150, cy - 150, 300, 300);

      for (const star of stars) {
        star.z -= SPEED;
        if (star.z <= 0.001) {
          Object.assign(star, createStar(width, height, false));
          star.z = 2;
        }

        const sx = cx + (star.x / star.z) * 0.5;
        const sy = cy + (star.y / star.z) * 0.5;
        const size = Math.min(3, (1 - star.z / 2) * 4);
        const alpha = Math.min(1, (1 - star.z / 2) * 1.5);

        if (sx < -10 || sx > width + 10 || sy < -10 || sy > height + 10) {
          Object.assign(star, createStar(width, height, false));
          star.z = 2;
          continue;
        }

        ctx.beginPath();
        ctx.arc(sx, sy, size, 0, Math.PI * 2);
        ctx.fillStyle = star.color;
        ctx.globalAlpha = alpha;
        ctx.fill();
        ctx.globalAlpha = 1;
      }

      animRef.current = requestAnimationFrame(draw);
    };

    // Initial fill
    ctx.fillStyle = BG_COLOR;
    ctx.fillRect(0, 0, width, height);

    animRef.current = requestAnimationFrame(draw);

    return () => {
      window.removeEventListener('resize', resize);
      cancelAnimationFrame(animRef.current);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        width: '100vw',
        height: '100vh',
        background: BG_COLOR,
      }}
    />
  );
}
```

- [ ] **Step 2: Commit**

```bash
cd /Users/nasakim/projects/sman && git add src/features/stardom/StarfieldCanvas.tsx && git commit -m "feat(stardom): add StarfieldCanvas animation component"
```

---

### Task 8: Create StardomEntry wrapper

**Files:**
- Create: `/Users/nasakim/projects/sman/src/features/stardom/StardomEntry.tsx`

- [ ] **Step 1: Create the entry component**

State machine: `checking` → `locked` | `unlocking` → `dashboard`

```tsx
import { useEffect, useState, useRef } from 'react';
import { StarfieldCanvas } from './StarfieldCanvas';
import { StardomDashboard } from './StardomDashboard';
import { useStardomDevMode } from '@/queries/use-hub';
import { t } from '@/locales';

type Phase = 'checking' | 'locked' | 'unlocking' | 'dashboard';

const CHECK_DELAY_MS = 3000;
const FADE_DURATION_MS = 2000;

export function StardomEntry() {
  const { data: devMode, isError, isFetched } = useStardomDevMode();
  const [phase, setPhase] = useState<Phase>('checking');
  const [showMessage, setShowMessage] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    if (!isFetched && !isError) return;

    const enabled = devMode === true;

    // Wait 3s before deciding
    timerRef.current = setTimeout(() => {
      if (enabled) {
        setPhase('unlocking');
        // After fade, show dashboard
        timerRef.current = setTimeout(() => {
          setPhase('dashboard');
        }, FADE_DURATION_MS);
      } else {
        setPhase('locked');
        setShowMessage(true);
      }
    }, CHECK_DELAY_MS);

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [isFetched, isError, devMode]);

  if (phase === 'dashboard') {
    return <StardomDashboard />;
  }

  const isFading = phase === 'unlocking';

  return (
    <div
      style={{
        position: 'relative',
        width: '100vw',
        height: '100vh',
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 0,
          transition: isFading ? `opacity ${FADE_DURATION_MS}ms ease-out` : undefined,
          opacity: isFading ? 0 : 1,
        }}
      >
        <StarfieldCanvas />
        {showMessage && (
          <div
            style={{
              position: 'absolute',
              top: '50%',
              left: '50%',
              transform: 'translate(-50%, -50%)',
              textAlign: 'center',
            }}
          >
            <p
              className="stardom-coming-soon"
              style={{
                color: '#00f0ff',
                fontSize: '1.25rem',
                fontWeight: 300,
                letterSpacing: '0.15em',
                textShadow: '0 0 20px rgba(0, 240, 255, 0.5), 0 0 40px rgba(0, 240, 255, 0.2)',
                animation: 'stardom-breathe 3s ease-in-out infinite',
                margin: 0,
              }}
            >
              {t('stardom.entry.comingSoon')}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Add breathe animation CSS**

Inject the `@keyframes` unconditionally on mount (not conditionally on phase) to avoid a 1-frame animation miss:

```tsx
useEffect(() => {
  const style = document.createElement('style');
  style.textContent = `
    @keyframes stardom-breathe {
      0%, 100% { opacity: 0.4; }
      50% { opacity: 0.9; }
    }
    .stardom-coming-soon { animation: stardom-breathe 3s ease-in-out infinite; }
  `;
  document.head.appendChild(style);
  return () => { document.head.removeChild(style); };
}, []);
```

- [ ] **Step 3: Commit**

```bash
cd /Users/nasakim/projects/sman && git add src/features/stardom/StardomEntry.tsx && git commit -m "feat(stardom): add StardomEntry wrapper with starfield animation and toggle logic"
```

---

### Task 9: Update route to use StardomEntry

**Files:**
- Modify: `/Users/nasakim/projects/sman/src/app/routes.tsx`

- [ ] **Step 1: Update import and route**

Change the import from `StardomDashboard` to `StardomEntry`:

```tsx
import { StardomEntry } from '@/features/stardom/StardomEntry';
```

Change the route:

```tsx
{ path: 'stardom', element: <StardomEntry /> },
```

- [ ] **Step 2: Commit**

```bash
cd /Users/nasakim/projects/sman && git add src/app/routes.tsx && git commit -m "feat(routes): use StardomEntry as stardom route handler"
```

---

### Task 10: Build verification

- [ ] **Step 1: TypeScript check on sman-server**

Run: `cd /Users/nasakim/projects/sman-server && npx tsc --noEmit`
Expected: No errors

- [ ] **Step 2: Run sman-server tests**

Run: `cd /Users/nasakim/projects/sman-server && npx vitest run`
Expected: All tests pass

- [ ] **Step 3: TypeScript check on sman client**

Run: `cd /Users/nasakim/projects/sman && npx tsc --noEmit`
Expected: No errors

- [ ] **Step 4: Final commit if any fixes needed**
