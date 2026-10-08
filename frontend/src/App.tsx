import { useState } from 'react'
import { Navigate, NavLink, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { getSelectedProperty, setSelectedProperty } from './api/client'
import { useAuth } from './auth/AuthContext'
import { PropertySwitcher } from './components/PropertySwitcher'
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
import { DocumentsPage } from './pages/DocumentsPage'
import { ResultTypesPage } from './pages/ResultTypesPage'

function Layout() {
  const { user, initializing, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // Which property the pages are scoped to. Keying the outlet on it re-mounts
  // the current page on a switch, so it fetches again with the new header.
  const [scope, setScope] = useState<string | null>(getSelectedProperty())

  if (initializing) return <p className="muted center-note">Checking session…</p>
  // Preserve the deep link (e.g. a scanned /scan/:token QR) across login.
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />

  return (
    <div className="app">
      <header className="topbar">
        <NavLink to="/" className="brand" end>
          Sclera
        </NavLink>
        <nav>
          <NavLink to="/locations">Locations</NavLink>
          <NavLink to="/assets">Assets</NavLink>
          <NavLink to="/templates">Procedures</NavLink>
          <NavLink to="/settings/result-types">Result types</NavLink>
          <NavLink to="/settings/documents">Documents</NavLink>
          <NavLink to="/inspection-configs">Inspections</NavLink>
          <NavLink to="/reactive-services">Reactive</NavLink>
          <NavLink to="/tasks">Tasks</NavLink>
        </nav>
        <div className="topbar-right">
          <PropertySwitcher onChange={setScope} />
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
      <main className="content">
        <Outlet key={scope ?? 'organization'} />
      </main>
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
        <Route path="/settings/documents" element={<DocumentsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
