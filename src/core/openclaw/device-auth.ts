// src/core/openclaw/device-auth.ts
/**
 * Device identity and authentication for OpenClaw Gateway
 *
 * Based on OpenClaw UI implementation:
 * - Uses Web Crypto API for device identity
 * - Signs connect payload with device private key
 * - Stores device token for reconnection
 */

const DEVICE_ID_KEY = "openclaw_device_id";
const DEVICE_PRIVATE_KEY = "openclaw_device_private_key";
const DEVICE_PUBLIC_KEY = "openclaw_device_public_key";

export type DeviceIdentity = {
  deviceId: string;
  privateKey: CryptoKey;
  publicKey: string;
};

export type DeviceAuthPayload = {
  deviceId: string;
  clientId: string;
  clientMode: string;
  role: string;
  scopes: string[];
  signedAtMs: number;
  token: string | null;
  nonce: string;
};

/**
 * Build the payload to be signed for device authentication
 */
export function buildDeviceAuthPayload(params: DeviceAuthPayload): string {
  const { deviceId, clientId, clientMode, role, scopes, signedAtMs, token, nonce } = params;
  // Format matches OpenClaw's expected payload structure
  const payload = {
    deviceId,
    clientId,
    clientMode,
    role,
    scopes: scopes.sort(),
    signedAtMs,
    token: token || null,
    nonce,
  };
  return JSON.stringify(payload);
}

/**
 * Sign the device auth payload using the private key
 */
export async function signDevicePayload(privateKey: CryptoKey, payload: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(payload);

  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    privateKey,
    data
  );

  // Convert to base64
  return btoa(String.fromCharCode(...new Uint8Array(signature)));
}

/**
 * Generate a new device identity using Web Crypto API
 */
async function generateDeviceIdentity(): Promise<DeviceIdentity> {
  const keyPair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"]
  );

  // Export public key to base64
  const publicKeyBuffer = await crypto.subtle.exportKey("spki", keyPair.publicKey);
  const publicKeyBase64 = btoa(String.fromCharCode(...new Uint8Array(publicKeyBuffer)));

  // Generate device ID
  const deviceId = crypto.randomUUID();

  // Export and store private key
  const privateKeyBuffer = await crypto.subtle.exportKey("pkcs8", keyPair.privateKey);
  const privateKeyBase64 = btoa(String.fromCharCode(...new Uint8Array(privateKeyBuffer)));

  // Store in localStorage
  localStorage.setItem(DEVICE_ID_KEY, deviceId);
  localStorage.setItem(DEVICE_PRIVATE_KEY, privateKeyBase64);
  localStorage.setItem(DEVICE_PUBLIC_KEY, publicKeyBase64);

  return {
    deviceId,
    privateKey: keyPair.privateKey,
    publicKey: publicKeyBase64,
  };
}

/**
 * Import a CryptoKey from base64-encoded PKCS8 data
 */
async function importPrivateKey(base64: string): Promise<CryptoKey> {
  const binaryString = atob(base64);
  const buffer = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    buffer[i] = binaryString.charCodeAt(i);
  }

  return crypto.subtle.importKey(
    "pkcs8",
    buffer,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256",
    },
    true,
    ["sign"]
  );
}

/**
 * Load existing device identity or create a new one
 */
export async function loadOrCreateDeviceIdentity(): Promise<DeviceIdentity> {
  const deviceId = localStorage.getItem(DEVICE_ID_KEY);
  const privateKeyBase64 = localStorage.getItem(DEVICE_PRIVATE_KEY);
  const publicKey = localStorage.getItem(DEVICE_PUBLIC_KEY);

  if (deviceId && privateKeyBase64 && publicKey) {
    try {
      const privateKey = await importPrivateKey(privateKeyBase64);
      return { deviceId, privateKey, publicKey };
    } catch {
      // Failed to import, generate new
    }
  }

  return generateDeviceIdentity();
}

/**
 * Clear stored device identity
 */
export function clearDeviceIdentity(): void {
  localStorage.removeItem(DEVICE_ID_KEY);
  localStorage.removeItem(DEVICE_PRIVATE_KEY);
  localStorage.removeItem(DEVICE_PUBLIC_KEY);
}

// Device token storage

export type DeviceAuthToken = {
  deviceId: string;
  role: string;
  token: string;
  scopes: string[];
};

const DEVICE_TOKEN_PREFIX = "openclaw_device_token_";

function deviceTokenKey(deviceId: string, role: string): string {
  return `${DEVICE_TOKEN_PREFIX}${deviceId}_${role}`;
}

export function storeDeviceAuthToken(params: DeviceAuthToken): void {
  const key = deviceTokenKey(params.deviceId, params.role);
  localStorage.setItem(key, JSON.stringify({
    token: params.token,
    scopes: params.scopes,
  }));
}

export function loadDeviceAuthToken(params: { deviceId: string; role: string }): DeviceAuthToken | null {
  const key = deviceTokenKey(params.deviceId, params.role);
  const stored = localStorage.getItem(key);
  if (!stored) return null;

  try {
    const parsed = JSON.parse(stored);
    return {
      ...params,
      token: parsed.token,
      scopes: parsed.scopes || [],
    };
  } catch {
    return null;
  }
}

export function clearDeviceAuthToken(params: { deviceId: string; role: string }): void {
  const key = deviceTokenKey(params.deviceId, params.role);
  localStorage.removeItem(key);
}
