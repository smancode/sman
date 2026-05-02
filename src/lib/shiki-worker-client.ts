/**
 * Main-thread client for the Shiki Web Worker.
 *
 * Provides a Promise-based API to offload syntax highlighting to a background worker.
 */

import type { TokensResult } from 'shiki';

interface PendingRequest {
  resolve: (result: TokensResult) => void;
  reject: (error: Error) => void;
}

let worker: Worker | null = null;
let requestId = 0;
const pendingRequests = new Map<string, PendingRequest>();

function getWorker(): Worker {
  if (!worker) {
    worker = new Worker(new URL('./shiki-worker.ts', import.meta.url), {
      type: 'module',
    });
    worker.onmessage = (event) => {
      const { id, result, error } = event.data;
      const pending = pendingRequests.get(id);
      if (!pending) return;
      pendingRequests.delete(id);
      if (error) {
        pending.reject(new Error(error));
      } else {
        pending.resolve(result);
      }
    };
    worker.onerror = (err) => {
      console.error('[ShikiWorker] Worker error:', err);
    };
  }
  return worker;
}

export function highlightCode(
  code: string,
  language: string,
  themes: [string, string],
): Promise<TokensResult> {
  return new Promise((resolve, reject) => {
    const id = `${++requestId}`;
    pendingRequests.set(id, { resolve, reject });
    getWorker().postMessage({ id, code, language, themes });
  });
}

export function terminateWorker(): void {
  if (worker) {
    worker.terminate();
    worker = null;
    pendingRequests.clear();
  }
}
