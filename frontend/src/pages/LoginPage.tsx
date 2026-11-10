import { useState } from 'react'
import { api } from '../api'
import type { Me } from '../api'

export function LoginPage({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onSignedIn(await api<Me>('POST', '/api/auth/login', { username, password }))
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="center-screen">
      <form className="login panel form" onSubmit={submit}>
        <h1>ASH Web Viewer</h1>
        <label>Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus autoComplete="username" required />
        </label>
        <label>Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                 autoComplete="current-password" required />
        </label>
        {error && <p className="error-text">{error}</p>}
        <button type="submit" className="primary" disabled={busy}>Sign in</button>
      </form>
    </div>
  )
}
