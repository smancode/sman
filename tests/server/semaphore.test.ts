import { describe, it, expect, vi, afterEach } from 'vitest';
import { Semaphore, SemaphoreStoppedError } from '../../server/semaphore.js';

describe('Semaphore', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('should limit concurrency to max', async () => {
    const sem = new Semaphore(2);
    let active = 0;
    let peak = 0;

    const tasks = Array.from({ length: 10 }, () =>
      sem.withLock(async () => {
        active++;
        peak = Math.max(peak, active);
        await new Promise(r => setTimeout(r, 10));
        active--;
      }),
    );

    await Promise.all(tasks);
    expect(peak).toBeLessThanOrEqual(2);
    expect(active).toBe(0);
  });

  it('should support pause and resume', async () => {
    const sem = new Semaphore(1);
    const order: string[] = [];

    // Fill the only slot
    const blocker = sem.withLock(async () => {
      order.push('blocker-start');
      await new Promise(r => setTimeout(r, 50));
      order.push('blocker-end');
    });

    // Pause before waiter tries to acquire
    await new Promise(r => setTimeout(r, 5));
    sem.pause();

    // Waiter will try to acquire but get blocked by pause
    const waiterPromise = sem.withLock(async () => {
      order.push('waiter-start');
      order.push('waiter-end');
    });

    // Give waiter time to enter acquire() and hit pause check
    await new Promise(r => setTimeout(r, 20));
    // Waiter should not have started yet
    expect(order).toEqual(['blocker-start']);

    // Let blocker finish (releases slot, but waiter is paused)
    await blocker;
    order.push('after-blocker');
    expect(order).toEqual(['blocker-start', 'blocker-end', 'after-blocker']);

    // Resume — waiter should now start
    sem.resume();
    await waiterPromise;
    expect(order).toEqual(['blocker-start', 'blocker-end', 'after-blocker', 'waiter-start', 'waiter-end']);
  });

  it('should stop and reject pending waiters', async () => {
    const sem = new Semaphore(1);

    const blocker = sem.withLock(async () => {
      await new Promise(r => setTimeout(r, 50));
    });

    const rejected = sem.withLock(async () => {
      throw new Error('should not reach');
    });

    await new Promise(r => setTimeout(r, 5));
    sem.stop();

    // Await both concurrently so the rejection handler on `rejected` is
    // attached before the microtask queue processes the stop-induced throw.
    const [, rejectedErr] = await Promise.all([
      blocker,
      rejected.catch(err => err),
    ]);
    expect(rejectedErr).toBeInstanceOf(SemaphoreStoppedError);
  });

  it('should report active count', async () => {
    const sem = new Semaphore(3);
    expect(sem.activeCount).toBe(0);

    const task = sem.withLock(async () => {
      await new Promise(r => setTimeout(r, 30));
      expect(sem.activeCount).toBe(1);
    });

    await new Promise(r => setTimeout(r, 5));
    expect(sem.activeCount).toBe(1);
    await task;
    expect(sem.activeCount).toBe(0);
  });

  it('should not underflow on release after stop', async () => {
    const sem = new Semaphore(1);
    sem.stop();
    sem.release(); // Should not throw or go negative
    sem.release(); // Should not throw or go negative
    expect(sem.activeCount).toBe(0);
  });
});
