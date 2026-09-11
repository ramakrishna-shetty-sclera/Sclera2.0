import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import {
  bffLogout,
  devLogin,
  fetchSession,
  startBffLogin,
  type SessionUser,
} from '../api/client'

interface AuthState {
  user: SessionUser | null
  /** true while the initial session probe is in flight */
  initializing: boolean
  login: (username: string, password: string) => Promise<void>
  loginSso: (email: string, orgId: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState>({
  user: null,
  initializing: true,
  login: async () => {},
  loginSso: async () => {},
  logout: async () => {},
})

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null)
  const [initializing, setInitializing] = useState(true)

  const refresh = useCallback(async () => {
    try {
      const session = await fetchSession()
      setUser(session.authenticated ? session : null)
    } catch {
      setUser(null)
    }
  }, [])

  useEffect(() => {
    refresh().finally(() => setInitializing(false))
    const onExpired = () => setUser(null)
    window.addEventListener('sclera:session-expired', onExpired)
    return () => window.removeEventListener('sclera:session-expired', onExpired)
  }, [refresh])

  const login = useCallback(
    async (username: string, password: string) => {
      await devLogin(username, password)
      await refresh()
    },
    [refresh],
  )

  const loginSso = useCallback(async (email: string, orgId: string) => {
    const redirectUrl = await startBffLogin(email, orgId, '/inspections')
    // Must be a full-page navigation so Keycloak can set its own cookies.
    window.location.assign(redirectUrl)
  }, [])

  const logout = useCallback(async () => {
    const logoutUrl = await bffLogout().catch(() => null)
    setUser(null)
    if (logoutUrl) window.location.assign(logoutUrl)
  }, [])

  return (
    <AuthContext.Provider value={{ user, initializing, login, loginSso, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthState {
  return useContext(AuthContext)
}
