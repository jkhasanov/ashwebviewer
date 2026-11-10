import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import type { Me, Role, User } from '../api'
import { Modal } from '../components/Modal'

function UserForm({ existing, onClose }: { existing: User | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [username, setUsername] = useState(existing?.username ?? '')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>(existing?.role ?? 'VIEWER')
  const [enabled, setEnabled] = useState(existing?.enabled ?? true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const save = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (existing) {
        await api('PUT', `/api/users/${existing.id}`, { role, enabled, password: password || null })
      } else {
        await api('POST', '/api/users', { username, password, role })
      }
      await queryClient.invalidateQueries({ queryKey: ['users'] })
      onClose()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={existing ? `Edit ${existing.username}` : 'Add user'} onClose={onClose}>
      <form className="form" onSubmit={save}>
        {!existing && (
          <label>Username
            <input value={username} onChange={(e) => setUsername(e.target.value)} maxLength={60} autoFocus
                   autoComplete="off" required />
          </label>
        )}
        <label>{existing ? 'New password' : 'Password'}
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8}
                 autoComplete="new-password" required={!existing}
                 placeholder={existing ? 'Leave empty to keep the current password' : ''} />
          <small>At least 8 characters.</small>
        </label>
        <label>Role
          <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
            <option value="VIEWER">Viewer: can see everything, change nothing</option>
            <option value="ADMIN">Admin: also manages databases and users</option>
          </select>
        </label>
        {existing && (
          <label className="check">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            Can sign in
          </label>
        )}
        {error && <p className="error-text">{error}</p>}
        <div className="form-actions">
          <button type="button" onClick={onClose}>Cancel</button>
          <button type="submit" className="primary" disabled={busy}>{existing ? 'Save' : 'Add user'}</button>
        </div>
      </form>
    </Modal>
  )
}

export function UsersPage({ me }: { me: Me }) {
  const queryClient = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: () => api<User[]>('GET', '/api/users') })
  const [editing, setEditing] = useState<User | 'new' | null>(null)
  const [deleting, setDeleting] = useState<User | null>(null)
  const [error, setError] = useState<string | null>(null)

  const remove = async (user: User) => {
    setError(null)
    try {
      await api('DELETE', `/api/users/${user.id}`)
      await queryClient.invalidateQueries({ queryKey: ['users'] })
    } catch (err) {
      setError((err as Error).message)
    }
    setDeleting(null)
  }

  return (
    <>
      <div className="page-head">
        <h1>Users</h1>
        <button className="primary" onClick={() => setEditing('new')}>Add user</button>
      </div>
      {error && <p className="banner banner-error">{error}</p>}
      {users.error && <p className="error-text pad">{users.error.message}</p>}
      <section className="panel">
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>Username</th><th>Role</th><th>Sign-in</th><th>Created</th><th></th></tr></thead>
            <tbody>
              {(users.data ?? []).map((u) => (
                <tr key={u.id}>
                  <td>{u.username}{u.username === me.username && <span className="muted"> (you)</span>}</td>
                  <td>{u.role === 'ADMIN' ? 'Admin' : 'Viewer'}</td>
                  <td>{u.enabled ? 'Allowed' : <span className="muted">Disabled</span>}</td>
                  <td className="muted">{new Date(u.createdAt).toLocaleDateString()}</td>
                  <td className="row-actions">
                    <button onClick={() => setEditing(u)}>Edit</button>
                    {u.username !== me.username && <button className="danger" onClick={() => setDeleting(u)}>Delete</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      {editing && <UserForm existing={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      {deleting && (
        <Modal title={`Delete user ${deleting.username}?`} onClose={() => setDeleting(null)}>
          <div className="form">
            <p>They will no longer be able to sign in.</p>
            <div className="form-actions">
              <button onClick={() => setDeleting(null)}>Cancel</button>
              <button className="danger-solid" onClick={() => remove(deleting)}>Delete user</button>
            </div>
          </div>
        </Modal>
      )}
    </>
  )
}
