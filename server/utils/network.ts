import os from 'node:os';

export function getLocalIp(): string {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] ?? []) {
      if (net.family === 'IPv4' && !net.internal) return net.address;
    }
  }
  return '';
}

export function getClientId(): string {
  const user = os.userInfo().username;
  const ip = getLocalIp();
  return `${user}@${ip || os.hostname()}`;
}
