export class SemaphoreStoppedError extends Error {
  constructor() {
    super('Semaphore has been stopped');
    this.name = 'SemaphoreStoppedError';
  }
}

type Waiter = {
  type: 'normal' | 'paused';
  resolve: () => void;
};

export class Semaphore {
  private waiters: Waiter[] = [];
  private _active = 0;
  private _paused = false;
  private _stopped = false;

  constructor(private max: number) {}

  get activeCount(): number {
    return this._active;
  }

  get isPaused(): boolean {
    return this._paused;
  }

  get isStopped(): boolean {
    return this._stopped;
  }

  async acquire(): Promise<void> {
    if (this._stopped) throw new SemaphoreStoppedError();

    if (this._paused) {
      await new Promise<void>((resolve) => {
        const check = () => {
          if (!this._paused || this._stopped) resolve();
        };
        this.waiters.push({ type: 'paused', resolve: check });
      });
      if (this._stopped) throw new SemaphoreStoppedError();
    }

    while (this._active >= this.max && !this._stopped) {
      await new Promise<void>((resolve) => {
        this.waiters.push({ type: 'normal', resolve });
      });
    }

    if (this._stopped) throw new SemaphoreStoppedError();
    this._active++;
  }

  release(): void {
    if (this._active <= 0) return;
    this._active--;

    if (this._paused) return;

    const next = this.waiters.shift();
    if (next) next.resolve();
  }

  pause(): void {
    this._paused = true;
  }

  resume(): void {
    this._paused = false;
    const stillWaiting: Waiter[] = [];
    for (const w of this.waiters) {
      if (w.type === 'paused') {
        w.resolve();
      } else {
        stillWaiting.push(w);
      }
    }
    this.waiters = stillWaiting;
  }

  stop(): void {
    this._stopped = true;
    const waiters = this.waiters;
    this.waiters = [];
    waiters.forEach(w => w.resolve());
  }

  async withLock<T>(fn: () => Promise<T>): Promise<T> {
    await this.acquire();
    try {
      return await fn();
    } finally {
      this.release();
    }
  }
}
