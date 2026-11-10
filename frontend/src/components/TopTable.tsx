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
  onSqlClick: (row: TopRow) => void
}

/** Statements or sessions ranked by their share of the activity in the selected period. */
export function TopTable({ kind, result, loading, error, onSqlClick }: Props) {
  const rows = result?.rows ?? []
  const maxSamples = rows.length ? rows[0].samples : 1
  return (
    <section className="panel top-panel">
      <h3>{kind === 'SQL' ? 'Top SQL' : 'Top sessions'}</h3>
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
                        row.sqlText ? (
                          <button className="link-button mono" onClick={() => onSqlClick(row)}>{row.sqlId}</button>
                        ) : (
                          row.sqlId
                        )
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
                    <td className="mono" title={`Serial ${row.sessionSerial}`}>{row.sessionId}</td>
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
