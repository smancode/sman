#!/usr/bin/env node
/**
 * E2E Test for Gateway Connection - Version 2
 * Tests different protocol versions to find the correct one
 */

const WebSocket = require('ws');

const GATEWAY_URL = process.env.GATEWAY_URL || 'ws://127.0.0.1:18790';
const GATEWAY_TOKEN = process.env.GATEWAY_TOKEN || 'sman-31244d65207dcced';

async function testConnect() {
  return new Promise((resolve, reject) => {
    console.log(`\n[TEST] Connecting to ${GATEWAY_URL}...`);

    const ws = new WebSocket(GATEWAY_URL);
    let challengeNonce = null;
    const timeout = setTimeout(() => {
      console.log('[TEST] Timeout');
      ws.close();
      reject(new Error('Timeout'));
    }, 10000);

    ws.on('open', () => {
      console.log('[TEST] WebSocket opened');
    });

    ws.on('message', (data) => {
      try {
        const msg = JSON.parse(data.toString());
        console.log('[TEST] Received:', JSON.stringify(msg, null, 2).slice(0, 500));

        if (msg.type === 'event' && msg.event === 'connect.challenge') {
          challengeNonce = msg.payload?.nonce;
          console.log(`[TEST] Challenge nonce: ${challengeNonce}`);

          // Try with correct client.id format (must be 'gateway-client' or match a schema)
          const connectFrame = {
            type: 'req',
            id: `connect-${Date.now()}`,
            method: 'connect',
            params: {
              minProtocol: 3,
              maxProtocol: 3,
              client: {
                id: 'gateway-client',
                displayName: 'SmanWeb E2E Test',
                version: '0.1.0',
                platform: 'node',
                mode: 'ui',
              },
              auth: { token: GATEWAY_TOKEN },
              caps: [],
              role: 'operator',
              scopes: ['operator.admin'],
            },
          };

          console.log('[TEST] Sending connect frame (without nonce in params)...');
          ws.send(JSON.stringify(connectFrame));
        }

        if (msg.type === 'res' && msg.id?.startsWith('connect-')) {
          clearTimeout(timeout);
          if (msg.ok) {
            console.log('[TEST] ✅ Connection successful!');
            console.log('[TEST] Server version:', msg.payload?.server?.version);
            ws.close();
            resolve(msg.payload);
          } else {
            console.log('[TEST] ❌ Connection failed:', msg.error);
            ws.close();
            reject(new Error(msg.error?.message || 'Connection failed'));
          }
        }
      } catch (err) {
        console.error('[TEST] Parse error:', err.message);
      }
    });

    ws.on('error', (err) => {
      console.error('[TEST] WebSocket error:', err.message);
      clearTimeout(timeout);
      reject(err);
    });

    ws.on('close', (code, reason) => {
      console.log(`[TEST] WebSocket closed: ${code} ${reason}`);
    });
  });
}

async function main() {
  try {
    await testConnect();
    console.log('\n[TEST] ✅ All tests passed!');
    process.exit(0);
  } catch (err) {
    console.error('\n[TEST] ❌ Test failed:', err.message);
    process.exit(1);
  }
}

main();
