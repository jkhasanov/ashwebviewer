import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import type { DbType, Target, TargetInput } from '../api'
import { Modal } from '../components/Modal'
import { StatusBadge } from '../components/StatusBadge'
import { DB_TYPE_LABEL } from '../format'

const URL_HINT: Record<DbType, string> = {
  ORACLE: 'jdbc:oracle:thin:@//host:1521/service_name',
  POSTGRES: 'jdbc:postgresql://host:5432/database',
}

const NEEDS: Record<DbType, string> = {
  ORACLE:
    'Reads v$active_session_history, which requires the Oracle Diagnostic Pack licence. The account needs SELECT on v$active_session_history and v$sqlstats.',
  POSTGRES:
    'Samples pg_stat_activity once a second. Give the account the pg_read_all_stats role so it can see other users’ sessions and statements.',
}

function DatabaseForm({ existing, onClose }: { existing: Target | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<TargetInput>({
    name: existing?.name ?? '',
    dbType: existing?.dbType ?? 'ORACLE',
    jdbcUrl: existing?.jdbcUrl ?? '',
    username: existing?.username ?? '',
    password: '',
    enabled: existing?.enabled ?? true,
  })
  const [error, setError] = useState<string | null>(null)
  const [test, setTest] = useState<{ ok: boolean; message: string } | null>(null)
  const [busy, setBusy] = useState<'save' | 'test' | null>(null)
  const set = <K extends keyof TargetInput>(key: K, value: TargetInput[K]) => {
    setForm((f) => ({ ...f, [key]: value }))
    setTest(null)
  }

  const runTest = async () => {
    setBusy('test')
    setError(null)
    setTest(null)
    try {
      setTest(await api('POST', `/api/targets/test${existing ? `?id=${existing.id}` : ''}`, form))
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(null)
    }
  }

  const save = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy('save')
    setError(null)
    try {
      await api(existing ? 'PUT' : 'POST', existing ? `/api/targets/${existing.id}` : '/api/targets', form)
      await queryClient.invalidateQueries({ queryKey: ['targets'] })
      onClose()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(null)
    }
  }

  return (
    <Modal title={existing ? `Edit ${existing.name}` : 'Add database'} onClose={onClose}>
      <form className="form" onSubmit={save}>
        <label>Name
          <input value={form.name} onChange={(e) => set('name', e.target.value)} maxLength={80} autoFocus required />
        </label>
        <label>Type
          <select value={form.dbType} onChange={(e) => set('dbType', e.target.value as DbType)}>
            <option value="ORACLE">Oracle (Enterprise Edition, real ASH)</option>
            <option value="POSTGRES">PostgreSQL</option>
          </select>
          <small>{NEEDS[form.dbType]}</small>
        </label>
        <label>JDBC URL
          <input value={form.jdbcUrl} onChange={(e) => set('jdbcUrl', e.target.value)} placeholder={URL_HINT[form.dbType]}
                 spellCheck={false} required />
        </label>
        <label>Username
          <input value={form.username} onChange={(e) => set('username', e.target.value)} autoComplete="off" required />
        </label>
        <label>Password
          <input type="password" value={form.password} onChange={(e) => set('password', e.target.value)}
                 autoComplete="new-password" required={!existing}
                 placeholder={existing ? 'Leave empty to keep the stored password' : ''} />
          <small>Stored encrypted on the server and never shown again.</small>
        </label>
        <label className="check">
          <input type="checkbox" checked={form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
          Collect from this database
        </label>
        {test && <p className={test.ok ? 'ok-text' : 'error-text'}>{test.ok ? '✓ ' : '▲ '}{test.message}</p>}
        {error && <p className="error-text">{error}</p>}
        <div className="form-actions">
          <button type="button" onClick={runTest} disabled={busy !== null}>
            {busy === 'test' ? 'Testing…' : 'Test connection'}
          </button>
          <span className="spacer" />
          <button type="button" onClick={onClose}>Cancel</button>
          <button type="submit" className="primary" disabled={busy !== null}>{existing ? 'Save' : 'Add database'}</button>
        </div>
      </form>
    </Modal>
  )
}

export function DatabasesPage() {
  const queryClient = useQueryClient()
  const targets = useQuery({
    queryKey: ['targets'],
    queryFn: () => api<Target[]>('GET', '/api/targets'),
    refetchInterval: 10_000,
  })
  const [editing, setEditing] = useState<Target | 'new' | null>(null)
  const [deleting, setDeleting] = useState<Target | null>(null)
  const [error, setError] = useState<string | null>(null)

  const act = async (method: string, path: string) => {
    setError(null)
    try {
      await api(method, path)
      await queryClient.invalidateQueries({ queryKey: ['targets'] })
    } catch (err) {
      setError((err as Error).message)
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>Databases</h1>
        <button className="primary" onClick={() => setEditing('new')}>Add database</button>
      </div>
      {error && <p className="banner banner-error">{error}</p>}
      {targets.error && <p className="error-text pad">{targets.error.message}</p>}
      <section className="panel">
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr><th>Name</th><th>Type</th><th>JDBC URL</th><th>Username</th><th>Collector</th><th className="num">Rows collected</th><th></th></tr>
            </thead>
            <tbody>
              {(targets.data ?? []).map((t) => (
                <tr key={t.id}>
                  <td><Link to={`/db/${t.id}`}>{t.name}</Link></td>
                  <td>{DB_TYPE_LABEL[t.dbType]}</td>
                  <td className="mono ellipsis" title={t.jdbcUrl}>{t.jdbcUrl}</td>
                  <td>{t.username}</td>
                  <td>
                    <StatusBadge status={t.collector} />
                    {t.collector.state === 'ERROR' && (
                      <div className="error-detail" title={t.collector.lastError ?? ''}>{t.collector.lastError}</div>
                    )}
                  </td>
                  <td className="num">{t.collector.rowsCollected.toLocaleString()}</td>
                  <td className="row-actions">
                    {t.enabled
                      ? <button onClick={() => act('POST', `/api/targets/${t.id}/stop`)}>Stop</button>
                      : <button onClick={() => act('POST', `/api/targets/${t.id}/start`)}>Start</button>}
                    <button onClick={() => setEditing(t)}>Edit</button>
                    <button className="danger" onClick={() => setDeleting(t)}>Delete</button>
                  </td>
                </tr>
              ))}
              {targets.data?.length === 0 && (
                <tr><td colSpan={7} className="muted">No databases yet. Add one to start collecting.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {editing && <DatabaseForm existing={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      {deleting && (
        <Modal title={`Delete ${deleting.name}?`} onClose={() => setDeleting(null)}>
          <div className="form">
            <p>This removes the database and <strong>all activity history collected from it</strong>. It cannot be undone.</p>
            <div className="form-actions">
              <button onClick={() => setDeleting(null)}>Cancel</button>
              <button className="danger-solid" onClick={async () => {
                await act('DELETE', `/api/targets/${deleting.id}`)
                setDeleting(null)
              }}>Delete database and history</button>
            </div>
          </div>
        </Modal>
      )}
    </>
  )
}
