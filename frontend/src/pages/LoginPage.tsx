import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

// testuser's org in the local Keycloak realm (setup-keycloak.ps1); the real
// deployment resolves orgId via the gateway's /resolve-identity endpoint.
const DEFAULT_ORG_ID =
  (import.meta.env.VITE_ORG_ID as string | undefined) ?? '11111111-1111-1111-1111-111111111111'

export function LoginPage() {
  const { user, initializing, login, loginSso } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Deep link preserved by Layout's redirect (e.g. a scanned /scan/:token QR).
  const returnTo = (location.state as { from?: string } | null)?.from ?? '/inspections'

  if (initializing) return null
  if (user) return <Navigate to={returnTo} replace />

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(username, password)
      navigate(returnTo)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed')
      setBusy(false)
    }
  }

  async function onSso() {
    setBusy(true)
    setError(null)
    try {
      const email = username.includes('@') ? username : 'testuser@sclera.local'
      await loginSso(email, DEFAULT_ORG_ID)
      // loginSso navigates the whole page to Keycloak; nothing more to do here.
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start SSO login')
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={onSubmit}>
        <h1>Sclera</h1>
        <p className="muted">Sign in to manage inspections</p>
        {error && <div className="alert alert-error">{error}</div>}
        <label>
          Username
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
            required
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <div className="login-divider">or</div>
        <button className="btn" type="button" disabled={busy} onClick={onSso}>
          Sign in with Keycloak (SSO redirect)
        </button>
        <p className="muted small login-note">
          Both paths create a gateway BFF session cookie; the SSO button drives the production
          authorization-code flow through Keycloak's own login page.
        </p>
      </form>
    </div>
  )
}
