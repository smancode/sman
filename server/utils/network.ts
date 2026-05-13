import os from 'node:os';

let cachedPublicIp: string | null = null;

function isPrivateIp(ip: string): boolean {
  return (
    ip.startsWith('10.') ||
    ip.startsWith('192.168.') ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(ip) ||
    ip.startsWith('169.254.')
  );
}

function getNicIp(): string {
  const nets = os.networkInterfaces();
  const skipNames = /^(tun|tap|veth|vEthernet|docker|br-|vnic|vmware|vbox|hyper-v|wsl)/i;
  const candidates: { address: string; priority: number }[] = [];

  for (const [name, interfaces] of Object.entries(nets)) {
    if (skipNames.test(name)) continue;
    for (const net of interfaces || []) {
      if (net.family !== 'IPv4' || net.internal) continue;
      if (net.address.startsWith('169.254.')) continue;
      let priority = 0;
      if (net.address.startsWith('10.')) priority = 3;
      else if (net.address.startsWith('192.168.')) priority = 2;
      else if (/^172\.(1[6-9]|2\d|3[01])\./.test(net.address)) priority = 1;
      else priority = 4; // Public IP wins
      candidates.push({ address: net.address, priority });
    }
  }

  if (candidates.length > 0) {
    candidates.sort((a, b) => b.priority - a.priority);
    return candidates[0].address;
  }
  return '127.0.0.1';
}

/**
 * Get the best IP for reporting.
 * - If NIC has a public IP, use it directly.
 * - If all NIC IPs are private, fetch public IP via external service (cached for 1 hour).
 */
export async function getLocalIp(): Promise<string> {
  const nicIp = getNicIp();
  if (!isPrivateIp(nicIp)) return nicIp;

  if (cachedPublicIp) return cachedPublicIp;

  try {
    const controller = new AbortController();
    const tid = setTimeout(() => controller.abort(), 3000);
    const res = await fetch('https://api.ipify.org?format=text', { signal: controller.signal });
    clearTimeout(tid);
    if (res.ok) {
      const ip = (await res.text()).trim();
      if (ip && /^\d+\.\d+\.\d+\.\d+$/.test(ip)) {
        cachedPublicIp = ip;
        // Invalidate cache after 1 hour
        setTimeout(() => { cachedPublicIp = null; }, 3600_000);
        return ip;
      }
    }
  } catch {}

  return nicIp;
}

export function getClientId(): string {
  const user = os.userInfo().username;
  // Sync version for non-hub contexts (local display)
  const ip = getNicIp();
  return `${user}@${ip || os.hostname()}`;
}
