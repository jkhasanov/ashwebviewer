import { Link } from 'react-router-dom'
import type { TopResult, TopRow } from '../api'
import { sortWaitClasses, waitClassColor } from '../colors'
import { formatAas } from '../format'

function ActivityBar({ row, maxSamples }: { row: TopRow; maxSamples: number }) {
  const classes = sortWaitClasses(Object.keys(row.byWaitClass))
  const title = classes
    .map((c) => `${c}: ${((100 * row.byWaitClass[c]) / row.samples).toFixed(0)}%`)
    .join(' · ')
  return (
    <div className="activity-cell" title={title}>
      <div className="activity-bar" style={{ width: `${Math.max(2, (100 * row.samples) / maxSamples)}%` }}>
        {classes.map((c) => (
          <span key={c} style={{ flexGrow: row.byWaitClass[c], background: waitClassColor(c) }} />
        ))}
      </div>
      <span className="activity-percent">{row.percent < 10 ? row.percent.toFixed(1) : row.percent.toFixed(0)}%</span>
    </div>
  )
}

interface Props {
  kind: 'SQL' | 'SESSION'
  result: TopResult | undefined
  loading: boolean
  error: string | null
  /** Where a SQL ID leads (the statement's detail page). Without it SQL IDs are plain text. */
  sqlLink?: (sqlId: string) => string
  /** Where a session leads (its detail page). Without it sessions are plain text. */
  sessionLink?: (sessionId: number, sessionSerial: number) => string
  title?: string
  /**
   * Show the serial number next to the session id (Oracle, where "sid,serial#" names a session and one sid
   * is reused by many short sessions).
   */
  showSerial?: boolean
}

/** Statements or sessions ranked by their share of the activity in the selected period. */
export function TopTable({ kind, result, loading, error, sqlLink, sessionLink, title, showSerial }: Props) {
  const rows = result?.rows ?? []
  const maxSamples = rows.length ? rows[0].samples : 1
  const sessionLabel = (row: TopRow) => (
    <>{row.sessionId}{showSerial && <span className="muted">,{row.sessionSerial}</span>}</>
  )
  return (
    <section className="panel top-panel">
      <h3>{title ?? (kind === 'SQL' ? 'Top SQL' : 'Top sessions')}</h3>
      {error && <p className="error-text">{error}</p>}
      {!error && rows.length === 0 && (
        <p className="muted pad">{loading ? 'Loading…' : 'No activity in this period.'}</p>
      )}
      {rows.length > 0 && (
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              {kind === 'SQL' ? (
                <tr>
                  <th className="col-activity">Activity</th>
                  <th className="num" title="Average active sessions">AAS</th>
                  <th>SQL ID</th>
                  <th>Type</th>
                  <th>Statement</th>
                </tr>
              ) : (
                <tr>
                  <th className="col-activity">Activity</th>
                  <th className="num" title="Average active sessions">AAS</th>
                  <th>Session</th>
                  <th>User</th>
                  <th>Program</th>
                </tr>
              )}
            </thead>
            <tbody>
              {rows.map((row) =>
                kind === 'SQL' ? (
                  <tr key={row.sqlId ?? ''}>
                    <td><ActivityBar row={row} maxSamples={maxSamples} /></td>
                    <td className="num">{formatAas(row.avgActiveSessions)}</td>
                    <td className="mono">
                      {row.sqlId ? (
                        sqlLink ? <Link to={sqlLink(row.sqlId)}>{row.sqlId}</Link> : row.sqlId
                      ) : (
                        <span className="muted">no statement</span>
                      )}
                    </td>
                    <td>{row.sqlOpname ?? ''}</td>
                    <td className="sql-snippet mono" title={row.sqlText ?? undefined}>{row.sqlText ?? ''}</td>
                  </tr>
                ) : (
                  <tr key={`${row.sessionId}:${row.sessionSerial}`}>
                    <td><ActivityBar row={row} maxSamples={maxSamples} /></td>
                    <td className="num">{formatAas(row.avgActiveSessions)}</td>
                    <td className="mono" title={showSerial ? 'Session id, serial number' : undefined}>
                      {sessionLink
                        ? <Link to={sessionLink(row.sessionId!, row.sessionSerial!)}>{sessionLabel(row)}</Link>
                        : sessionLabel(row)}
                    </td>
                    <td>{row.userName ?? ''}</td>
                    <td className="ellipsis" title={row.program ?? undefined}>{row.program ?? ''}</td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
