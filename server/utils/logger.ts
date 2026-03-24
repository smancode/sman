export interface Logger {
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
  debug(message: string, meta?: Record<string, unknown>): void;
}

export function createLogger(module: string): Logger {
  const isDebug = process.env.LOG_LEVEL === 'debug';

  return {
    info(message: string, meta?: Record<string, unknown>) {
      console.log(JSON.stringify({ level: 'info', module, message, ...meta, ts: new Date().toISOString() }));
    },
    warn(message: string, meta?: Record<string, unknown>) {
      console.warn(JSON.stringify({ level: 'warn', module, message, ...meta, ts: new Date().toISOString() }));
    },
    error(message: string, meta?: Record<string, unknown>) {
      console.error(JSON.stringify({ level: 'error', module, message, ...meta, ts: new Date().toISOString() }));
    },
    debug(message: string, meta?: Record<string, unknown>) {
      if (isDebug) {
        console.debug(JSON.stringify({ level: 'debug', module, message, ...meta, ts: new Date().toISOString() }));
      }
    },
  };
}
