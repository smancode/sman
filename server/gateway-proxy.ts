/**
 * WebSocket Proxy for OpenClaw Gateway
 *
 * Proxies client WebSocket connections to the OpenClaw Gateway,
 * injecting authentication tokens automatically.
 */

import { WebSocketServer, WebSocket } from 'ws';
import { createLogger } from './utils/logger.js';
import http from 'http';

const log = createLogger('GatewayProxy');

export interface GatewayProxyOptions {
  server: http.Server;
  gatewayUrl: string;
  gatewayToken: string;
  path?: string;
}

export function createGatewayProxy(options: GatewayProxyOptions): WebSocketServer {
  const { server, gatewayUrl, gatewayToken, path = '/ws' } = options;

  const wss = new WebSocketServer({ server, path });

  wss.on('connection', (clientWs, req) => {
    const clientIp = req.socket.remoteAddress || 'unknown';
    log.info('Client connected', { ip: clientIp, path: req.url });

    // Connect to OpenClaw Gateway
    const gatewayWs = new WebSocket(gatewayUrl);

    gatewayWs.on('open', () => {
      log.debug('Connected to gateway');

      // Forward client messages to gateway
      clientWs.on('message', (data) => {
        if (gatewayWs.readyState === WebSocket.OPEN) {
          gatewayWs.send(data);
        }
      });

      // Handle gateway messages
      gatewayWs.on('message', (data) => {
        if (clientWs.readyState === WebSocket.OPEN) {
          clientWs.send(data);
        }
      });

      // Handle close
      const onClose = (reason: string) => {
        log.debug('Connection closed', { reason });
        if (gatewayWs.readyState === WebSocket.OPEN) {
          gatewayWs.close();
        }
        if (clientWs.readyState === WebSocket.OPEN) {
          clientWs.close();
        }
      };

      clientWs.on('close', () => onClose('client'));
      gatewayWs.on('close', () => onClose('gateway'));

      // Handle errors
      clientWs.on('error', (err) => log.error('Client WS error', { error: err.message }));
      gatewayWs.on('error', (err) => log.error('Gateway WS error', { error: err.message }));
    });

    gatewayWs.on('error', (err) => {
      log.error('Failed to connect to gateway', { error: err.message });
      clientWs.close(1011, 'Gateway connection failed');
    });
  });

  log.info('Gateway proxy created', { path, gatewayUrl });
  return wss;
}
