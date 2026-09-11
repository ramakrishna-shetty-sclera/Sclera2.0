import { Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { LoginPage } from './pages/LoginPage'
import { InspectionsPage } from './pages/InspectionsPage'
import { InspectionRunPage } from './pages/InspectionRunPage'
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
        <span className="brand">Sclera</span>
        <nav>
          <NavLink to="/inspections">Inspections</NavLink>
          <NavLink to="/templates">Templates</NavLink>
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
        <Route path="/" element={<Navigate to="/inspections" replace />} />
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
