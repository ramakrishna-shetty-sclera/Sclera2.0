import { Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { LoginPage } from './pages/LoginPage'
import { HomePage } from './pages/HomePage'
import { ComingSoonPage } from './pages/ComingSoonPage'
import { LocationsPage } from './pages/LocationsPage'
import { AssetsPage } from './pages/AssetsPage'
import { InspectionsPage } from './pages/InspectionsPage'
import { InspectionRunPage } from './pages/InspectionRunPage'
import { InspectionConfigsPage } from './pages/InspectionConfigsPage'
import { InspectionConfigFormPage } from './pages/InspectionConfigFormPage'
import { InspectionConfigDetailPage } from './pages/InspectionConfigDetailPage'
import { ChecklistFillPage } from './pages/ChecklistFillPage'
import { TemplatesPage } from './pages/TemplatesPage'
import { TemplateDetailPage } from './pages/TemplateDetailPage'
import { TemplateEditorPage } from './pages/TemplateEditorPage'

function Layout() {
  const { user, initializing, logout } = useAuth()
  const navigate = useNavigate()

  if (initializing) return <p className="muted center-note">Checking session…</p>
  if (!user) return <Navigate to="/login" replace />

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
          <NavLink to="/inspection-configs">Inspections</NavLink>
          <NavLink to="/tasks">Tasks</NavLink>
        </nav>
        <div className="topbar-right">
          <span className="user-chip">{user.email ?? user.id}</span>
          <button
            className="btn btn-ghost"
            onClick={() => {
              logout().then(() => navigate('/login'))
            }}
          >
            Sign out
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
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
        <Route
          path="/tasks"
          element={
            <ComingSoonPage
              title="Task Dashboard"
              icon="✅"
              scope={[
                'Work items across locations, assets and inspections',
                'Status, assignee and due-date views',
                'Quick links into the originating inspection or asset',
              ]}
            />
          }
        />
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
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
