/**
 * WeChat Personal Bot (iLink Bot API) HTTP Client
 * Pure functional API client — no state, no class.
 */
import crypto from 'crypto';
import type {
  BaseInfo,
  GetUpdatesReq,
  GetUpdatesResp,
  QRCodeResponse,
  QRCodeStatusResponse,
  SendMessageReq,
} from './weixin-types.js';

// ── Constants ──

const ILINK_BASE_URL = 'https://ilinkai.weixin.qq.com';
const CHANNEL_VERSION = '1.0.0';
const CLIENT_VERSION_UINT32 = encodeClientVersion(CHANNEL_VERSION);

const DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000;
const DEFAULT_API_TIMEOUT_MS = 15_000;
const QR_POLL_TIMEOUT_MS = 35_000;

// ── Headers ──

function buildHeaders(token?: string, includeAuth = true): Record<string, string> {
  const headers: Record<string, string> = {};
  if (includeAuth && token) {
    headers['Authorization'] = `Bearer ${token}`;
    headers['AuthorizationType'] = 'ilink_bot_token';
  }
  return headers;
}

function buildPostHeaders(token?: string): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    ...buildHeaders(token),
    'X-WECHAT-UIN': crypto.randomBytes(4).readUInt32BE(0).toString().slice(0, 10),
    'iLink-App-Id': 'bot',
    'iLink-App-ClientVersion': String(CLIENT_VERSION_UINT32),
  };
}

function buildBaseInfo(): BaseInfo {
  return { channel_version: CHANNEL_VERSION };
}

function encodeClientVersion(version: string): number {
  const [major = 0, minor = 0, patch = 0] = version.split('.').map(Number);
  return ((major & 0xff) << 16) | ((minor & 0xff) << 8) | (patch & 0xff);
}

// ── Fetch Helpers ──

async function apiGetFetch<T>(
  baseUrl: string,
  endpoint: string,
  timeoutMs: number,
  label: string,
): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const url = `${baseUrl}/${endpoint}`;
    const res = await fetch(url, {
      signal: controller.signal,
      headers: buildHeaders(),
    });
    const raw = await res.text();
    if (!res.ok) {
      throw new Error(`${label} ${res.status}: ${raw}`);
    }
    return JSON.parse(raw) as T;
  } finally {
    clearTimeout(timer);
  }
}

async function apiPostFetch(
  baseUrl: string,
  endpoint: string,
  body: unknown,
  token: string | undefined,
  timeoutMs: number,
  label: string,
): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const url = `${baseUrl}/${endpoint}`;
    const bodyStr = JSON.stringify(body);
    const res = await fetch(url, {
      method: 'POST',
      headers: buildPostHeaders(token),
      body: bodyStr,
      signal: controller.signal,
    });
    const raw = await res.text();
    if (!res.ok) {
      throw new Error(`${label} ${res.status}: ${raw}`);
    }
    return raw;
  } finally {
    clearTimeout(timer);
  }
}

// ── Public API ──

export async function getBotQRCode(): Promise<QRCodeResponse> {
  return apiGetFetch<QRCodeResponse>(
    ILINK_BASE_URL,
    'ilink/bot/get_bot_qrcode?bot_type=3',
    DEFAULT_API_TIMEOUT_MS,
    'getBotQRCode',
  );
}

export async function getQRCodeStatus(qrcode: string, timeoutMs = 35_000): Promise<QRCodeStatusResponse> {
  return apiGetFetch<QRCodeStatusResponse>(
    ILINK_BASE_URL,
    `ilink/bot/get_qrcode_status?qrcode=${encodeURIComponent(qrcode)}`,
    timeoutMs,
    'getQRCodeStatus',
  );
}

export async function getUpdates(opts: {
  baseUrl: string;
  token?: string;
  getUpdatesBuf: string;
}): Promise<GetUpdatesResp> {
  const body: GetUpdatesReq & { base_info: BaseInfo } = {
    get_updates_buf: opts.getUpdatesBuf,
    base_info: buildBaseInfo(),
  };

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), DEFAULT_LONG_POLL_TIMEOUT_MS);
  try {
    const url = `${opts.baseUrl}/ilink/bot/getupdates`;
    const res = await fetch(url, {
      method: 'POST',
      headers: buildPostHeaders(opts.token),
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const raw = await res.text();
    if (!res.ok) {
      throw new Error(`getUpdates ${res.status}: ${raw}`);
    }
    return JSON.parse(raw) as GetUpdatesResp;
  } catch (err) {
    // AbortError from long-poll timeout is normal — return empty response
    if (err instanceof DOMException && err.name === 'AbortError') {
      return { ret: 0, msgs: [], get_updates_buf: opts.getUpdatesBuf };
    }
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

export async function sendMessage(opts: {
  baseUrl: string;
  token: string;
  toUserId: string;
  text: string;
  contextToken?: string;
}): Promise<void> {
  const body: SendMessageReq = {
    msg: {
      to_user_id: opts.toUserId,
      message_type: 2, // BOT
      message_state: 2, // FINISH
      item_list: [
        {
          type: 1, // TEXT
          text_item: { text: opts.text },
        },
      ],
      ...(opts.contextToken ? { context_token: opts.contextToken } : {}),
    },
  };

  await apiPostFetch(
    opts.baseUrl,
    'ilink/bot/sendmessage',
    { ...body, base_info: buildBaseInfo() },
    opts.token,
    DEFAULT_API_TIMEOUT_MS,
    'sendMessage',
  );
}
