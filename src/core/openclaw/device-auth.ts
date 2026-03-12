// src/core/openclaw/device-auth.ts
/**
 * Device identity and authentication for OpenClaw Gateway
 *
 * Based on ClawX implementation:
 * - Uses Ed25519 for device identity (same as ClawX)
 * - Signs connect payload with device private key
 * - Device ID is SHA256 fingerprint of public key
 */

const DEVICE_ID_KEY = "openclaw_device_id";
const DEVICE_PRIVATE_KEY = "openclaw_device_private_key";
const DEVICE_PUBLIC_KEY = "openclaw_device_public_key";

export type DeviceIdentity = {
  deviceId: string;
  privateKey: CryptoKey;
  publicKey: string; // base64url encoded raw public key
  publicKeyPem: string; // SPKI PEM format
};

export type DeviceAuthPayloadParams = {
  deviceId: string;
  clientId: string;
  clientMode: string;
  role: string;
  scopes: string[];
  signedAtMs: number;
  token?: string | null;
  nonce?: string | null;
  version?: "v1" | "v2";
};

// Ed25519 SPKI prefix (30 2a 30 05 06 03 2b 65 70 03 21 00)
const ED25519_SPKI_PREFIX = new Uint8Array([0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00]);

/**
 * Base64url encode a buffer
 */
function base64UrlEncode(buf: Uint8Array): string {
  const base64 = btoa(String.fromCharCode(...buf));
  return base64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Base64url decode to Uint8Array
 */
function base64UrlDecode(str: string): Uint8Array {
  // Restore padding
  const padding = 4 - (str.length % 4);
  if (padding !== 4) {
    str += "=".repeat(padding);
  }
  const base64 = str.replace(/-/g, "+").replace(/_/g, "/");
  const binaryString = atob(base64);
  const buffer = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    buffer[i] = binaryString.charCodeAt(i);
  }
  return buffer;
}

/**
 * Derive raw Ed25519 public key bytes from SPKI format
 */
function derivePublicKeyRaw(publicKeyBuffer: Uint8Array): Uint8Array {
  if (
    publicKeyBuffer.length === ED25519_SPKI_PREFIX.length + 32 &&
    publicKeyBuffer.slice(0, ED25519_SPKI_PREFIX.length).every((b, i) => b === ED25519_SPKI_PREFIX[i])
  ) {
    return publicKeyBuffer.slice(ED25519_SPKI_PREFIX.length);
  }
  return publicKeyBuffer;
}

/**
 * Compute SHA256 fingerprint of public key
 */
async function fingerprintPublicKey(publicKeyBuffer: Uint8Array): Promise<string> {
  const raw = derivePublicKeyRaw(publicKeyBuffer);
  const hashBuffer = await crypto.subtle.digest("SHA-256", raw.buffer as ArrayBuffer);
  const hashArray = new Uint8Array(hashBuffer);
  return Array.from(hashArray)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Build the canonical payload string that must be signed for device auth.
 * Format: v2|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce
 */
export function buildDeviceAuthPayload(params: DeviceAuthPayloadParams): string {
  const version = params.version ?? (params.nonce ? "v2" : "v1");
  const scopes = params.scopes.join(",");
  const token = params.token ?? "";
  const base = [
    version,
    params.deviceId,
    params.clientId,
    params.clientMode,
    params.role,
    scopes,
    String(params.signedAtMs),
    token,
  ];
  if (version === "v2") base.push(params.nonce ?? "");
  return base.join("|");
}

/**
 * Sign a payload with the Ed25519 private key, returns base64url signature.
 */
export async function signDevicePayload(privateKey: CryptoKey, payload: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(payload);
  const signature = await crypto.subtle.sign("Ed25519", privateKey, data);
  return base64UrlEncode(new Uint8Array(signature));
}

/**
 * Generate a new Ed25519 device identity
 */
async function generateDeviceIdentity(): Promise<DeviceIdentity> {
  const keyPair = await crypto.subtle.generateKey(
    "Ed25519",
    true, // extractable
    ["sign", "verify"]
  );

  // Export public key to SPKI format
  const publicKeyBuffer = new Uint8Array(await crypto.subtle.exportKey("spki", keyPair.publicKey));
  const publicKeyRaw = derivePublicKeyRaw(publicKeyBuffer);
  const publicKeyBase64Url = base64UrlEncode(publicKeyRaw);

  // Generate device ID from public key fingerprint
  const deviceId = await fingerprintPublicKey(publicKeyBuffer);

  // Export private key to PKCS8 format
  const privateKeyBuffer = new Uint8Array(await crypto.subtle.exportKey("pkcs8", keyPair.privateKey));
  const privateKeyBase64Url = base64UrlEncode(privateKeyBuffer);

  // Create PEM format for compatibility
  const publicKeyPem = `-----BEGIN PUBLIC KEY-----\n${btoa(String.fromCharCode(...publicKeyBuffer)).match(/.{1,64}/g)?.join("\n")}\n-----END PUBLIC KEY-----`;

  // Store in localStorage
  localStorage.setItem(DEVICE_ID_KEY, deviceId);
  localStorage.setItem(DEVICE_PRIVATE_KEY, privateKeyBase64Url);
  localStorage.setItem(DEVICE_PUBLIC_KEY, publicKeyBase64Url);

  return {
    deviceId,
    privateKey: keyPair.privateKey,
    publicKey: publicKeyBase64Url,
    publicKeyPem,
  };
}

/**
 * Import a CryptoKey from base64url-encoded PKCS8 data
 */
async function importPrivateKey(base64url: string): Promise<CryptoKey> {
  const buffer = base64UrlDecode(base64url);

  return crypto.subtle.importKey(
    "pkcs8",
    buffer.buffer as ArrayBuffer,
    "Ed25519",
    true,
    ["sign"]
  );
}

/**
 * Import a public key from base64url-encoded raw data
 */
async function importPublicKey(base64url: string): Promise<CryptoKey> {
  const raw = base64UrlDecode(base64url);

  // Reconstruct SPKI format
  const spki = new Uint8Array(ED25519_SPKI_PREFIX.length + raw.length);
  spki.set(ED25519_SPKI_PREFIX);
  spki.set(raw, ED25519_SPKI_PREFIX.length);

  return crypto.subtle.importKey("spki", spki, "Ed25519", true, ["verify"]);
}

/**
 * Load existing device identity or create a new one
 */
export async function loadOrCreateDeviceIdentity(): Promise<DeviceIdentity> {
  const deviceId = localStorage.getItem(DEVICE_ID_KEY);
  const privateKeyBase64Url = localStorage.getItem(DEVICE_PRIVATE_KEY);
  const publicKeyBase64Url = localStorage.getItem(DEVICE_PUBLIC_KEY);

  if (deviceId && privateKeyBase64Url && publicKeyBase64Url) {
    try {
      const privateKey = await importPrivateKey(privateKeyBase64Url);
      const publicKeyRaw = base64UrlDecode(publicKeyBase64Url);

      // Reconstruct public key PEM
      const spki = new Uint8Array(ED25519_SPKI_PREFIX.length + publicKeyRaw.length);
      spki.set(ED25519_SPKI_PREFIX);
      spki.set(publicKeyRaw, ED25519_SPKI_PREFIX.length);
      const publicKeyPem = `-----BEGIN PUBLIC KEY-----\n${btoa(String.fromCharCode(...spki)).match(/.{1,64}/g)?.join("\n")}\n-----END PUBLIC KEY-----`;

      return { deviceId, privateKey, publicKey: publicKeyBase64Url, publicKeyPem };
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
  localStorage.setItem(
    key,
    JSON.stringify({
      token: params.token,
      scopes: params.scopes,
    })
  );
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
