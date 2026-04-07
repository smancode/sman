/**
 * Module-level auth token + base URL storage for HTTP requests.
 * Synced from ws-connection's initToken() or BackendSettings manual input.
 */

let _token = '';

/**
 * HTTP base URL for remote backend.
 * Empty string = local mode (relative fetch to same origin).
 * Set to e.g. "http://59.110.164.212:5880" for remote mode.
 */
let _baseUrl = '';

export function setAuthToken(token: string): void {
  _token = token;
}

export function getAuthToken(): string {
  return _token;
}

/**
 * Derive HTTP base URL from a WebSocket URL.
 * ws://59.110.164.212:5880/ws → http://59.110.164.212:5880
 * wss://example.com/ws → https://example.com
 */
export function setHttpBaseUrl(wsUrl: string): void {
  if (!wsUrl) {
    _baseUrl = '';
    return;
  }
  try {
    const url = new URL(wsUrl);
    const httpProtocol = url.protocol === 'wss:' ? 'https:' : 'http:';
    _baseUrl = `${httpProtocol}//${url.host}`;
  } catch {
    _baseUrl = '';
  }
}

export function authFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  if (_token) {
    headers.set('Authorization', `Bearer ${_token}`);
  }
  const fullUrl = _baseUrl ? `${_baseUrl}${url}` : url;
  return fetch(fullUrl, { ...options, headers });
}
