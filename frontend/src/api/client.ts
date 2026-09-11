import type { Pagination } from './types'

// All /api calls go through the API gateway (vite dev proxy → :8080). Auth is
// the gateway's BFF model: an HttpOnly session cookie plus a readable CSRF
// cookie mirrored into the X-CSRF-Token header on unsafe methods, and the
// X-Requested-With: sclera-spa sentinel the gateway's CsrfWebFilter requires.
const KEYCLOAK_TOKEN_ENDPOINT = '/auth/realms/sclera/protocol/openid-connect/token'
const DEV_CLIENT_ID = 'sclera-app'

// Prod HTTPS uses the __Host- prefixed name; local HTTP uses the plain one.
const CSRF_COOKIE_NAMES = ['__Host-sclera-csrf', 'sclera-csrf']

export class ApiError extends Error {
  code: string
  status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.code = code
    this.status = status
  }
}

interface Envelope<T> {
  success: boolean
  data: T
  pagination: Pagination | null
  error: { code: string; message: string } | null
}

export interface ApiResult<T> {
  data: T
  pagination: Pagination | null
}

export interface SessionUser {
  id: string | null
  email: string | null
  firstName: string | null
  lastName: string | null
  orgId: string | null
  orgType: string | null
  roles: string[]
  isPlatformAdmin: boolean
  authenticated: boolean
}

function readCsrfCookie(): string | null {
  for (const part of document.cookie.split(';')) {
    const [name, ...rest] = part.trim().split('=')
    if (CSRF_COOKIE_NAMES.includes(name)) return decodeURIComponent(rest.join('='))
  }
  return null
}

function gatewayHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = {
    'X-Requested-With': 'sclera-spa',
    'X-Correlation-ID': crypto.randomUUID(),
    ...extra,
  }
  const csrf = readCsrfCookie()
  if (csrf) headers['X-CSRF-Token'] = csrf
  return headers
}

function sessionExpired(): never {
  window.dispatchEvent(new Event('sclera:session-expired'))
  throw new ApiError('SESSION_EXPIRED', 'Your session has expired. Please sign in again.', 401)
}

async function parseEnvelope<T>(res: Response): Promise<ApiResult<T>> {
  const envelope = (await res.json().catch(() => null)) as Envelope<T> | null
  if (!envelope) {
    throw new ApiError('UNEXPECTED_RESPONSE', `Request failed with status ${res.status}`, res.status)
  }
  if (!res.ok || envelope.success === false) {
    throw new ApiError(
      envelope.error?.code ?? 'REQUEST_FAILED',
      envelope.error?.message ?? `Request failed with status ${res.status}`,
      res.status,
    )
  }
  // Gateway BFF endpoints and downstream services both use the sclera
  // envelope; fall back to the raw body for any that don't.
  const data = (envelope.data !== undefined ? envelope.data : envelope) as T
  return { data, pagination: envelope.pagination ?? null }
}

export async function apiFetch<T>(
  path: string,
  options: {
    method?: string
    body?: unknown
    query?: Record<string, string | number | undefined>
  } = {},
): Promise<ApiResult<T>> {
  let url = path
  if (options.query) {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(options.query)) {
      if (value !== undefined && value !== '') params.set(key, String(value))
    }
    const qs = params.toString()
    if (qs) url += `?${qs}`
  }

  const res = await fetch(url, {
    method: options.method ?? 'GET',
    credentials: 'same-origin',
    headers: gatewayHeaders(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })

  if (res.status === 401) sessionExpired()
  if (res.status === 403) {
    const body = await res.clone().json().catch(() => null)
    if (body?.error === 'csrf_token_mismatch') {
      throw new ApiError('CSRF_MISMATCH', 'Security token mismatch — reload the page and retry.', 403)
    }
  }
  return parseEnvelope<T>(res)
}

// ── BFF auth endpoints (served in-process by the gateway) ────────────────────

export async function fetchSession(): Promise<SessionUser> {
  const res = await fetch('/api/v1/auth/session', {
    credentials: 'same-origin',
    headers: gatewayHeaders(),
  })
  const { data } = await parseEnvelope<SessionUser>(res)
  return data
}

/**
 * Dev-only direct login: Keycloak password grant (public sclera-app client)
 * exchanged for a BFF session cookie via the gateway's test-exchange endpoint
 * (enabled locally with SCLERA_BFF_TEST_EXCHANGE_ENABLED=true).
 */
export async function devLogin(username: string, password: string): Promise<void> {
  const tokenRes = await fetch(KEYCLOAK_TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: DEV_CLIENT_ID,
      username,
      password,
    }),
  })
  const tokenBody = await tokenRes.json().catch(() => ({}))
  if (!tokenRes.ok) {
    throw new ApiError(
      tokenBody.error ?? 'AUTH_FAILED',
      tokenBody.error_description ?? 'Invalid username or password',
      tokenRes.status,
    )
  }

  const exchangeRes = await fetch('/api/auth/test-exchange', {
    method: 'POST',
    credentials: 'same-origin',
    headers: gatewayHeaders({ 'X-Test-Authorization': `Bearer ${tokenBody.access_token}` }),
  })
  if (!exchangeRes.ok) {
    const body = await exchangeRes.json().catch(() => null)
    throw new ApiError(
      'SESSION_EXCHANGE_FAILED',
      body?.message ??
        body?.error ??
        `Gateway session exchange failed (${exchangeRes.status}). Is the gateway running with SCLERA_BFF_TEST_EXCHANGE_ENABLED=true?`,
      exchangeRes.status,
    )
  }
}

/**
 * Production login flow: ask the gateway to start the OAuth code + PKCE dance.
 * Returns the Keycloak URL the browser must FULL-PAGE navigate to; the gateway
 * callback sets the session cookies and redirects back to the frontend.
 */
export async function startBffLogin(
  email: string,
  orgId: string,
  returnTo: string,
): Promise<string> {
  const res = await fetch('/api/v1/auth/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: gatewayHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ email, orgId, returnTo }),
  })
  const { data } = await parseEnvelope<{ redirectUrl: string; state: string }>(res)
  return data.redirectUrl
}

/** Logs out at the gateway; returns the OIDC end-session URL when one applies. */
export async function bffLogout(): Promise<string | null> {
  const res = await fetch('/api/v1/auth/logout', {
    method: 'POST',
    credentials: 'same-origin',
    headers: gatewayHeaders(),
  })
  if (!res.ok) return null
  const body = await res.json().catch(() => null)
  return body?.data?.logoutUrl ?? body?.logoutUrl ?? null
}
