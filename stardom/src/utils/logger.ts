// stardom/src/utils/logger.ts
export interface Logger {
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
  debug(message: string, meta?: Record<string, unknown>): void;
}

function formatLocalTime(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const ms = String(now.getMilliseconds()).padStart(3, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}.${ms}`;
}

export function createLogger(module: string): Logger {
  const isDebug = process.env.LOG_LEVEL === 'debug';

  return {
    info(message: string, meta?: Record<string, unknown>) {
      console.log(JSON.stringify({ level: 'info', module, message, ...meta, ts: formatLocalTime() }));
    },
    warn(message: string, meta?: Record<string, unknown>) {
      console.warn(JSON.stringify({ level: 'warn', module, message, ...meta, ts: formatLocalTime() }));
    },
    error(message: string, meta?: Record<string, unknown>) {
      console.error(JSON.stringify({ level: 'error', module, message, ...meta, ts: formatLocalTime() }));
    },
    debug(message: string, meta?: Record<string, unknown>) {
      if (isDebug) {
        console.debug(JSON.stringify({ level: 'debug', module, message, ...meta, ts: formatLocalTime() }));
      }
    },
  };
}
