/**
 * Module-level auth token storage for HTTP requests.
 * Synced from ws-connection's initToken() or BackendSettings manual input.
 */

let _token = '';

export function setAuthToken(token: string): void {
  _token = token;
}

export function getAuthToken(): string {
  return _token;
}

export function authFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  if (_token) {
    headers.set('Authorization', `Bearer ${_token}`);
  }
  return fetch(url, { ...options, headers });
}
