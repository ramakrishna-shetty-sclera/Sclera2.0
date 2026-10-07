import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import {
  bffLogout,
  devLogin,
  fetchSession,
  startBffLogin,
  type SessionUser,
} from '../api/client'
import { getMyPermissions } from '../api/me'
import type { Permissions } from '../api/types'

/** The safe default: gate on this until the real answer loads, and again if
 * it never does. Nothing here is ever true by assumption. */
const NO_PERMISSIONS: Permissions = {
  canViewProcedures: false,
  canManageTemplates: false,
  canPublishTemplates: false,
  canManageResultTypes: false,
}

interface AuthState {
  user: SessionUser | null
  /** true while the initial session probe is in flight */
  initializing: boolean
  /** What this account may do with procedures and result types. */
  permissions: Permissions
  /**
   * True once the permissions fetch has settled, success or failure. A screen
   * that gates on `permissions` should render nothing gated until this is
   * true — a button that appears and then vanishes a moment later is worse
   * than one that arrives a moment late.
   */
  permissionsLoaded: boolean
  login: (username: string, password: string) => Promise<void>
  loginSso: (email: string, orgId: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState>({
  user: null,
  initializing: true,
  permissions: NO_PERMISSIONS,
  permissionsLoaded: false,
  login: async () => {},
  loginSso: async () => {},
  logout: async () => {},
})

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null)
  const [initializing, setInitializing] = useState(true)
  const [permissions, setPermissions] = useState<Permissions>(NO_PERMISSIONS)
  const [permissionsLoaded, setPermissionsLoaded] = useState(false)

  const resetPermissions = useCallback(() => {
    setPermissions(NO_PERMISSIONS)
    setPermissionsLoaded(false)
  }, [])

  const refresh = useCallback(async () => {
    try {
      const session = await fetchSession()
      if (!session.authenticated) {
        setUser(null)
        resetPermissions()
        return
      }
      setUser(session)
      try {
        setPermissions(await getMyPermissions())
      } catch {
        // Fail closed: an account whose permissions could not even be asked
        // for is treated as able to do nothing, not as still loading.
        setPermissions(NO_PERMISSIONS)
      } finally {
        setPermissionsLoaded(true)
      }
    } catch {
      setUser(null)
      resetPermissions()
    }
  }, [resetPermissions])

  useEffect(() => {
    refresh().finally(() => setInitializing(false))
    const onExpired = () => {
      setUser(null)
      resetPermissions()
    }
    window.addEventListener('sclera:session-expired', onExpired)
    return () => window.removeEventListener('sclera:session-expired', onExpired)
  }, [refresh, resetPermissions])

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
    resetPermissions()
    if (logoutUrl) window.location.assign(logoutUrl)
  }, [resetPermissions])

  return (
    <AuthContext.Provider
      value={{ user, initializing, permissions, permissionsLoaded, login, loginSso, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthState {
  return useContext(AuthContext)
}
