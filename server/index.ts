/**
 * SmanWeb Server - Node.js Backend
 *
 * Main entry point for the integrated service:
 * - Serves static frontend
 * - Manages OpenClaw Gateway process
 * - Proxies WebSocket connections
 */

import express from 'express';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import { createLogger } from './utils/logger.js';
import { ProcessManager, createGatewayConfig } from './process-manager.js';
import { createGatewayProxy } from './gateway-proxy.js';
import { DEFAULT_PORTS } from './utils/ports.js';

// ES module __dirname equivalent
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const log = createLogger('Server');

// Configuration from environment
const config = {
  port: parseInt(process.env.PORT || String(DEFAULT_PORTS.server), 10),
  gatewayPort: parseInt(process.env.GATEWAY_PORT || String(DEFAULT_PORTS.gateway), 10),
  gatewayToken: process.env.GATEWAY_TOKEN || 'sman-default-token-change-in-production',
  bundledPath: path.resolve(__dirname, '../../bundled/openclaw'),
  staticPath: path.resolve(__dirname, '../../dist'),
};

// Express app
const app = express();
const server = http.createServer(app);

// Process manager
const processManager = new ProcessManager();

// Health check endpoint
app.get('/api/health', (req, res) => {
  const processStatus = processManager.getStatus();
  const gatewayHealthy = processStatus['openclaw-gateway']?.healthy ?? false;

  res.json({
    status: gatewayHealthy ? 'healthy' : 'degraded',
    timestamp: new Date().toISOString(),
    processes: processStatus,
  });
});

// Configuration endpoint
app.get('/api/config', (req, res) => {
  res.json({
    gateway: {
      url: `ws://127.0.0.1:${config.gatewayPort}`,
      token: config.gatewayToken,
    },
  });
});

// Static files (frontend) - serve last to allow API routes to match first
app.use(express.static(config.staticPath));

// SPA fallback - serve index.html for all unmatched routes
app.get('*', (req, res) => {
  res.sendFile(path.join(config.staticPath, 'index.html'));
});

// Graceful shutdown
async function shutdown(signal: string) {
  log.info(`Received ${signal}, shutting down...`);
  await processManager.stopAll();
  server.close(() => {
    log.info('Server closed');
    process.exit(0);
  });
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

// Start server
async function start() {
  log.info('Starting SmanWeb server', { config });

  // Start OpenClaw Gateway
  const gatewayConfig = createGatewayConfig({
    bundledPath: config.bundledPath,
    port: config.gatewayPort,
    authToken: config.gatewayToken,
  });

  processManager.start(gatewayConfig);

  // Create WebSocket proxy
  createGatewayProxy({
    server,
    gatewayUrl: `ws://127.0.0.1:${config.gatewayPort}`,
    gatewayToken: config.gatewayToken,
    path: '/ws',
  });

  // Start HTTP server
  server.listen(config.port, () => {
    log.info(`Server listening on port ${config.port}`);
    log.info(`Gateway proxy available at ws://localhost:${config.port}/ws`);
  });
}

start().catch((err) => {
  log.error('Failed to start server', { error: err.message });
  process.exit(1);
});
