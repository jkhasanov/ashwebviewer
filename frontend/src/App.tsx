import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { api } from './api'
import type { Me } from './api'
import { Modal } from './components/Modal'
import { DatabasesPage } from './pages/DatabasesPage'
import { LoginPage } from './pages/LoginPage'
import { OverviewPage } from './pages/OverviewPage'
import { ReportPage } from './pages/ReportPage'
import { SamplesPage } from './pages/SamplesPage'
import { SessionPage } from './pages/SessionPage'
import { SqlPage } from './pages/SqlPage'
import { TargetPage } from './pages/TargetPage'
import { UsersPage } from './pages/UsersPage'
import { THEMES, setTheme, useTheme } from './theme'

function ChangePassword({ onClose }: { onClose: () => void }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api('POST', '/api/auth/password', { currentPassword: current, newPassword: next })
      onClose()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="Change password" onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <label>Current password
          <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoFocus required />
        </label>
        <label>New password
          <input type="password" value={next} onChange={(e) => setNext(e.target.value)} minLength={8} required />
          <small>At least 8 characters.</small>
        </label>
        {error && <p className="error-text">{error}</p>}
        <div className="form-actions">
          <button type="button" onClick={onClose}>Cancel</button>
          <button type="submit" className="primary" disabled={busy}>Change password</button>
        </div>
      </form>
    </Modal>
  )
}

export function App() {
  const queryClient = useQueryClient()
  const me = useQuery({ queryKey: ['me'], queryFn: () => api<Me>('GET', '/api/auth/me') })
  const [changingPassword, setChangingPassword] = useState(false)
  const theme = useTheme()

  useEffect(() => {
    const signedOut = () => queryClient.resetQueries({ queryKey: ['me'] })
    window.addEventListener('ashweb:signed-out', signedOut)
    return () => window.removeEventListener('ashweb:signed-out', signedOut)
  }, [queryClient])

  if (me.isPending) return <div className="center-screen muted">Loading…</div>
  if (!me.data) return <LoginPage onSignedIn={(user) => queryClient.setQueryData(['me'], user)} />

  const user = me.data
  const isAdmin = user.role === 'ADMIN'
  const signOut = async () => {
    await api('POST', '/api/auth/logout').catch(() => undefined)
    queryClient.clear()
    queryClient.resetQueries({ queryKey: ['me'] })
  }

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">ASH Web Viewer</span>
        <nav>
          <NavLink to="/" end>Overview</NavLink>
          {isAdmin && <NavLink to="/databases">Databases</NavLink>}
          {isAdmin && <NavLink to="/users">Users</NavLink>}
        </nav>
        <div className="topbar-user">
          <label className="theme-select">Style
            <select value={theme.id} onChange={(e) => setTheme(e.target.value)}>
              {THEMES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
            </select>
          </label>
          <span className="muted">{user.username}{isAdmin ? ' (admin)' : ''}</span>
          <button onClick={() => setChangingPassword(true)}>Change password</button>
          <button onClick={signOut}>Sign out</button>
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<OverviewPage isAdmin={isAdmin} />} />
          <Route path="/db/:id" element={<TargetPage />} />
          <Route path="/db/:id/samples" element={<SamplesPage />} />
          <Route path="/db/:id/report" element={<ReportPage />} />
          <Route path="/db/:id/sql/:sqlId" element={<SqlPage />} />
          <Route path="/db/:id/session/:sessionId/:sessionSerial" element={<SessionPage />} />
          {isAdmin && <Route path="/databases" element={<DatabasesPage />} />}
          {isAdmin && <Route path="/users" element={<UsersPage me={user} />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      {changingPassword && <ChangePassword onClose={() => setChangingPassword(false)} />}
    </div>
  )
}
