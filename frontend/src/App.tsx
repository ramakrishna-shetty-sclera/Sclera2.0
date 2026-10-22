import { useState } from 'react'
import { Navigate, NavLink, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { getSelectedProperty, setSelectedProperty } from './api/client'
import { useAuth } from './auth/AuthContext'
import { PropertySwitcher } from './components/PropertySwitcher'
import { TopBarActions } from './components/TopBarActions'
import { TopBarSearch } from './components/TopBarSearch'
import { LoginPage } from './pages/LoginPage'
import { HomePage } from './pages/HomePage'
import { LocationsPage } from './pages/LocationsPage'
import { AssetsPage } from './pages/AssetsPage'
import { InspectionsPage } from './pages/InspectionsPage'
import { InspectionRunPage } from './pages/InspectionRunPage'
import { InspectionConfigsPage } from './pages/InspectionConfigsPage'
import { InspectionConfigFormPage } from './pages/InspectionConfigFormPage'
import { InspectionConfigDetailPage } from './pages/InspectionConfigDetailPage'
import { ChecklistFillPage } from './pages/ChecklistFillPage'
import { TaskDashboardPage } from './pages/TaskDashboardPage'
import { TaskMapPage } from './pages/TaskMapPage'
import { ReactiveServicesPage } from './pages/ReactiveServicesPage'
import { ScanPage } from './pages/ScanPage'
import { TemplatesPage } from './pages/TemplatesPage'
import { TemplateDetailPage } from './pages/TemplateDetailPage'
import { TemplateEditorPage } from './pages/TemplateEditorPage'
import { ResultTypesPage } from './pages/ResultTypesPage'

/** Two letters for the sidebar avatar — initials if we have a name, else the start of the email. */
function initialsOf(user: { firstName: string | null; lastName: string | null; email: string | null }): string {
  const f = user.firstName?.trim()?.[0]
  const l = user.lastName?.trim()?.[0]
  if (f && l) return `${f}${l}`.toUpperCase()
  if (f) return f.toUpperCase()
  return (user.email ?? '?').slice(0, 2).toUpperCase()
}

const SIDEBAR_COLLAPSED_KEY = 'sclera.sidebar.collapsed'

function Layout() {
  const { user, initializing, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // Which property the pages are scoped to. Keying the outlet on it re-mounts
  // the current page on a switch, so it fetches again with the new header.
  const [scope, setScope] = useState<string | null>(getSelectedProperty())
  // Purely a per-browser display preference, so it lives in localStorage —
  // the same treatment the selected property already gets.
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1'
    } catch {
      return false
    }
  })

  function toggleCollapsed() {
    setCollapsed((prev) => {
      const next = !prev
      try {
        localStorage.setItem(SIDEBAR_COLLAPSED_KEY, next ? '1' : '0')
      } catch {
        // per-browser convenience only — nothing breaks if it can't persist
      }
      return next
    })
  }

  if (initializing) return <p className="muted center-note">Checking session…</p>
  // Preserve the deep link (e.g. a scanned /scan/:token QR) across login.
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />

  const displayName = `${user.firstName ?? ''} ${user.lastName ?? ''}`.trim() || user.email || 'User'
  // The dark, edge-to-edge pages (Procedures and everything under it) need
  // .content to give up its centered-960px layout entirely. Driven from the
  // route rather than a CSS `:has()` selector on the rendered child, so it
  // does not depend on that selector actually matching in every browser.
  const isFullBleedRoute = location.pathname.startsWith('/templates')

  return (
    <div className="app">
      <aside className={`sidebar${collapsed ? ' collapsed' : ''}`}>
        <div className="sidebar-brand">
          <NavLink to="/" className="brand" end>
            <span className="brand-text">Sclera</span>
          </NavLink>
          <button
            type="button"
            className="sidebar-toggle"
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            onClick={toggleCollapsed}
          >
            {collapsed ? '▶' : '◀'}
          </button>
        </div>
        <nav className="sidebar-nav">
          <div className="nav-group">
            <span className="nav-label">Operations</span>
            <NavLink to="/locations" className="nav-item">
              <span className="nav-icon" aria-hidden="true">
                🏢
              </span>
              <span className="nav-text">Locations</span>
            </NavLink>
            <NavLink to="/assets" className="nav-item">
              <span className="nav-icon" aria-hidden="true">
                📦
              </span>
              <span className="nav-text">Assets</span>
            </NavLink>
            <NavLink to="/tasks" className="nav-item">
              <span className="nav-icon" aria-hidden="true">
                ☑
              </span>
              <span className="nav-text">Tasks</span>
            </NavLink>
          </div>
          <div className="nav-group">
            <span className="nav-label">Procedures</span>
            <NavLink to="/templates" className="nav-item">
              <span className="nav-icon" aria-hidden="true">
                📋
              </span>
              <span className="nav-text">Procedures</span>
            </NavLink>
            <NavLink to="/settings/result-types" className="nav-item">
              <span className="nav-icon" aria-hidden="true">
                🏷
              </span>
              <span className="nav-text">Result types</span>
            </NavLink>
          </div>
          <div className="nav-group">
            <span className="nav-label">Inspections</span>
            <NavLink to="/inspection-configs" className="nav-item">
              <span className="nav-icon" aria-hidden="true">
                🔍
              </span>
              <span className="nav-text">Inspections</span>
            </NavLink>
            <NavLink to="/reactive-services" className="nav-item">
              <span className="nav-icon" aria-hidden="true">
                ⚡
              </span>
              <span className="nav-text">Reactive</span>
            </NavLink>
          </div>
        </nav>
        <div className="sidebar-user">
          <span className="avatar" aria-hidden="true">
            {initialsOf(user)}
          </span>
          <span className="who">
            <span className="who-name">{displayName}</span>
            <span className="who-email">{user.email}</span>
          </span>
        </div>
      </aside>
      <div className="appmain">
        <header className="topbar">
          <div className="topbar-right">
            <TopBarSearch />
            <PropertySwitcher onChange={setScope} />
            <TopBarActions />
            <span className="user-chip">{user.email ?? user.id}</span>
            <button
              className="btn btn-ghost"
              onClick={() => {
                // The next person to sign in on this tab starts at organization level.
                setSelectedProperty(null)
                logout().then(() => navigate('/login'))
              }}
            >
              Sign out
            </button>
          </div>
        </header>
        <main className={`content${isFullBleedRoute ? ' content-full' : ''}`}>
          <Outlet key={scope ?? 'organization'} />
        </main>
      </div>
    </div>
  )
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<Layout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/locations" element={<LocationsPage />} />
        <Route path="/assets" element={<AssetsPage />} />
        <Route path="/tasks" element={<TaskDashboardPage />} />
        <Route path="/task-map" element={<TaskMapPage />} />
        <Route path="/reactive-services" element={<ReactiveServicesPage />} />
        <Route path="/scan/:token" element={<ScanPage />} />
        <Route path="/inspection-configs" element={<InspectionConfigsPage />} />
        <Route path="/inspection-configs/new" element={<InspectionConfigFormPage />} />
        <Route path="/inspection-configs/:id" element={<InspectionConfigDetailPage />} />
        <Route path="/inspection-configs/:id/edit" element={<InspectionConfigFormPage />} />
        <Route path="/checklists/:id" element={<ChecklistFillPage />} />
        {/* Legacy template-run flow — still reachable directly, not in primary nav */}
        <Route path="/inspections" element={<InspectionsPage />} />
        <Route path="/inspections/:id" element={<InspectionRunPage />} />
        <Route path="/templates" element={<TemplatesPage />} />
        <Route path="/templates/new" element={<TemplateEditorPage />} />
        <Route path="/templates/:id" element={<TemplateDetailPage />} />
        <Route path="/templates/:id/edit" element={<TemplateEditorPage />} />
        <Route path="/settings/result-types" element={<ResultTypesPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
