/**
 * Port allocation utilities
 */

export const DEFAULT_PORTS = {
  server: 3000,
  gateway: 18789,
  vite: 5173,
} as const;

/**
 * Check if a port is available
 */
export async function isPortAvailable(port: number): Promise<boolean> {
  const net = await import('net');

  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.once('listening', () => {
      server.close();
      resolve(true);
    });
    server.listen(port);
  });
}

/**
 * Find an available port starting from the given port
 */
export async function findAvailablePort(startPort: number): Promise<number> {
  let port = startPort;
  while (!(await isPortAvailable(port))) {
    port++;
    if (port > startPort + 100) {
      throw new Error(`Could not find available port starting from ${startPort}`);
    }
  }
  return port;
}
