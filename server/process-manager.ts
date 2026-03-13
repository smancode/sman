/**
 * Process Manager for OpenClaw Gateway and acpx
 *
 * Manages child processes with:
 * - Health monitoring
 * - Auto-restart on crash
 * - Graceful shutdown
 */

import { spawn, ChildProcess } from 'child_process';
import { createLogger } from './utils/logger.js';
import path from 'path';

const log = createLogger('ProcessManager');

export interface ProcessConfig {
  name: string;
  command: string;
  args: string[];
  env?: NodeJS.ProcessEnv;
  cwd?: string;
  restartOnExit?: boolean;
  healthCheckInterval?: number;
}

export interface ManagedProcess {
  name: string;
  process: ChildProcess | null;
  config: ProcessConfig;
  isHealthy: boolean;
  restartCount: number;
  lastStart: number;
}

export class ProcessManager {
  private processes: Map<string, ManagedProcess> = new Map();
  private shutdownRequested = false;
  private healthCheckTimers: Map<string, NodeJS.Timeout> = new Map();

  /**
   * Start a managed process
   */
  start(config: ProcessConfig): ManagedProcess {
    if (this.processes.has(config.name)) {
      throw new Error(`Process ${config.name} is already running`);
    }

    const managed: ManagedProcess = {
      name: config.name,
      process: null,
      config,
      isHealthy: false,
      restartCount: 0,
      lastStart: 0,
    };

    this.processes.set(config.name, managed);
    this.spawnProcess(managed);

    return managed;
  }

  private spawnProcess(managed: ManagedProcess): void {
    const { config } = managed;
    log.info(`Starting process: ${config.name}`, { command: config.command, args: config.args });

    managed.lastStart = Date.now();
    managed.process = spawn(config.command, config.args, {
      cwd: config.cwd || process.cwd(),
      env: { ...process.env, ...config.env },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });

    // Handle stdout
    managed.process.stdout?.on('data', (data: Buffer) => {
      const lines = data.toString().trim().split('\n');
      for (const line of lines) {
        log.info(`[${config.name}] ${line}`);
      }
    });

    // Handle stderr
    managed.process.stderr?.on('data', (data: Buffer) => {
      const lines = data.toString().trim().split('\n');
      for (const line of lines) {
        log.warn(`[${config.name}] ${line}`);
      }
    });

    // Handle process exit
    managed.process.on('exit', (code, signal) => {
      log.warn(`Process ${config.name} exited`, { code, signal });
      managed.process = null;
      managed.isHealthy = false;

      // Auto-restart unless shutdown requested
      if (!this.shutdownRequested && config.restartOnExit !== false) {
        const delay = Math.min(1000 * Math.pow(2, managed.restartCount), 30000);
        managed.restartCount++;

        log.info(`Scheduling restart for ${config.name} in ${delay}ms`);
        setTimeout(() => {
          if (!this.shutdownRequested && this.processes.has(config.name)) {
            this.spawnProcess(managed);
          }
        }, delay);
      }
    });

    managed.process.on('error', (err) => {
      log.error(`Process ${config.name} error`, { error: err.message });
      managed.isHealthy = false;
    });

    // Start health check
    this.startHealthCheck(managed);
  }

  private startHealthCheck(managed: ManagedProcess): void {
    const interval = managed.config.healthCheckInterval || 30000;

    const timer = setInterval(() => {
      if (managed.process && !managed.process.killed) {
        managed.isHealthy = true;
      } else {
        managed.isHealthy = false;
      }
    }, interval);

    this.healthCheckTimers.set(managed.name, timer);
  }

  /**
   * Stop a managed process
   */
  stop(name: string): Promise<void> {
    return new Promise((resolve) => {
      const managed = this.processes.get(name);
      if (!managed || !managed.process) {
        resolve();
        return;
      }

      log.info(`Stopping process: ${name}`);

      // Clear health check
      const timer = this.healthCheckTimers.get(name);
      if (timer) {
        clearInterval(timer);
        this.healthCheckTimers.delete(name);
      }

      // Graceful shutdown
      managed.process.once('exit', () => {
        log.info(`Process ${name} stopped`);
        resolve();
      });

      // Send SIGTERM, force kill after 10s
      managed.process.kill('SIGTERM');

      setTimeout(() => {
        if (managed.process && !managed.process.killed) {
          log.warn(`Force killing process: ${name}`);
          managed.process.kill('SIGKILL');
        }
      }, 10000);
    });
  }

  /**
   * Stop all managed processes
   */
  async stopAll(): Promise<void> {
    this.shutdownRequested = true;
    const stops: Promise<void>[] = [];

    for (const name of this.processes.keys()) {
      stops.push(this.stop(name));
    }

    await Promise.all(stops);
    log.info('All processes stopped');
  }

  /**
   * Get status of all processes
   */
  getStatus(): Record<string, { healthy: boolean; restartCount: number }> {
    const status: Record<string, { healthy: boolean; restartCount: number }> = {};

    for (const [name, managed] of this.processes) {
      status[name] = {
        healthy: managed.isHealthy,
        restartCount: managed.restartCount,
      };
    }

    return status;
  }
}

/**
 * Create OpenClaw Gateway process config
 */
export function createGatewayConfig(options: {
  bundledPath: string;
  port: number;
  authToken: string;
}): ProcessConfig {
  return {
    name: 'openclaw-gateway',
    command: 'node',
    args: [
      path.join(options.bundledPath, 'openclaw.mjs'),
      'gateway',
      '--port', String(options.port),
      '--auth-mode', 'token',
      '--auth-token', options.authToken,
      '--bind', 'loopback',
    ],
    cwd: options.bundledPath,
    restartOnExit: true,
    healthCheckInterval: 10000,
  };
}
