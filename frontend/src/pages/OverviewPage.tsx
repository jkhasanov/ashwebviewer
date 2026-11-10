import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import type { Target } from '../api'
import { sortWaitClasses, waitClassColor } from '../colors'
import { Sparkline } from '../components/Sparkline'
import { StatusBadge } from '../components/StatusBadge'
import { DB_TYPE_LABEL, formatAas, formatAgo } from '../format'

function WaitMix({ target }: { target: Target }) {
  const classes = sortWaitClasses(Object.keys(target.byWaitClass))
  const total = classes.reduce((sum, c) => sum + target.byWaitClass[c], 0)
  if (total === 0) return null
  const title = classes.map((c) => `${c}: ${formatAas(target.byWaitClass[c])}`).join(' · ')
  return (
    <div className="activity-bar wait-mix" title={title}>
      {classes.map((c) => (
        <span key={c} style={{ flexGrow: target.byWaitClass[c], background: waitClassColor(c) }} />
      ))}
    </div>
  )
}

export function OverviewPage({ isAdmin }: { isAdmin: boolean }) {
  const targets = useQuery({
    queryKey: ['targets'],
    queryFn: () => api<Target[]>('GET', '/api/targets'),
    refetchInterval: 10_000,
  })
  const [filter, setFilter] = useState('')

  const rows = useMemo(() => {
    const needle = filter.trim().toLowerCase()
    return [...(targets.data ?? [])]
      .filter((t) => !needle || t.name.toLowerCase().includes(needle))
      // Problems first, then the busiest
      .sort(
        (a, b) =>
          Number(b.collector.state === 'ERROR') - Number(a.collector.state === 'ERROR') ||
          (b.avgActiveSessions ?? -1) - (a.avgActiveSessions ?? -1) ||
          a.name.localeCompare(b.name),
      )
  }, [targets.data, filter])

  if (targets.isPending) return <p className="muted pad">Loading…</p>
  if (targets.error) return <p className="error-text pad">{targets.error.message}</p>

  const all = targets.data ?? []
  const collecting = all.filter((t) => t.collector.state === 'RUNNING').length
  const errors = all.filter((t) => t.collector.state === 'ERROR').length
  const totalAas = all.reduce((sum, t) => sum + (t.avgActiveSessions ?? 0), 0)

  if (all.length === 0) {
    return (
      <div className="empty panel">
        <h2>No databases yet</h2>
        {isAdmin ? (
          <p>Add the first database to start collecting its active session history. <Link to="/databases">Add a database</Link></p>
        ) : (
          <p>An administrator has not added any databases yet.</p>
        )}
      </div>
    )
  }

  return (
    <>
      <div className="tiles">
        <div className="tile"><span className="tile-label">Databases</span><span className="tile-value">{all.length}</span></div>
        <div className="tile"><span className="tile-label">Collecting</span><span className="tile-value">{collecting}</span></div>
        <div className="tile">
          <span className="tile-label">Collector errors</span>
          <span className={`tile-value${errors ? ' tile-bad' : ''}`}>{errors}</span>
        </div>
        <div className="tile">
          <span className="tile-label">Active sessions, all databases (5 min avg)</span>
          <span className="tile-value">{formatAas(totalAas)}</span>
        </div>
      </div>

      <section className="panel">
        <div className="panel-head">
          <h2>Databases</h2>
          <input className="search" placeholder="Filter by name" value={filter} onChange={(e) => setFilter(e.target.value)}
                 aria-label="Filter databases by name" />
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Database</th>
                <th>Type</th>
                <th>Collector</th>
                <th className="num" title="Average active sessions over the last 5 minutes">Active sessions</th>
                <th>Wait mix</th>
                <th>Last hour</th>
                <th>Last sample</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((t) => (
                <tr key={t.id}>
                  <td><Link to={`/db/${t.id}`}>{t.name}</Link></td>
                  <td>{DB_TYPE_LABEL[t.dbType]}</td>
                  <td>
                    <StatusBadge status={t.collector} />
                    {t.collector.state === 'ERROR' && (
                      <div className="error-detail" title={t.collector.lastError ?? ''}>{t.collector.lastError}</div>
                    )}
                  </td>
                  <td className="num strong">{formatAas(t.avgActiveSessions)}</td>
                  <td className="col-mix"><WaitMix target={t} /></td>
                  <td><Sparkline values={t.spark} /></td>
                  <td className="muted">{formatAgo(t.collector.lastSampleTime)}</td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr><td colSpan={7} className="muted">No database matches “{filter}”.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </>
  )
}
