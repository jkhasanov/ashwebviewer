import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, query } from '../api'
import type { SamplesPage as SamplesResult, Target } from '../api'
import { waitClassColor } from '../colors'
import { formatDateTime, formatDuration, formatRange } from '../format'
import { useTheme } from '../theme'

const PAGE_SIZE = 200

const DETAIL_LABELS: Record<string, string> = {
  machine: 'Machine',
  module: 'Module',
  action: 'Action',
  sessionType: 'Session type',
  blockingSession: 'Blocked by',
  planHashValue: 'Plan hash',
  database: 'Database',
  client: 'Client address',
  backendType: 'Backend type',
}

/** The individual samples behind a period: one row per active session per sample. */
export function SamplesPage() {
  const id = Number(useParams().id)
  // Read so the wait class colours follow the style
  useTheme()
  const [search, setSearch] = useSearchParams()
  const from = Number(search.get('from'))
  const to = Number(search.get('to'))
  const waitClass = search.get('waitClass') ?? ''
  const sqlId = search.get('sqlId') ?? ''
  const sessionId = search.get('sessionId') ?? ''
  const sessionSerial = search.get('sessionSerial') ?? ''
  const valid = from > 0 && to > from

  const [newestFirst, setNewestFirst] = useState(true)
  const [offset, setOffset] = useState(0)
  const filterKey = search.toString()
  useEffect(() => setOffset(0), [filterKey, newestFirst])

  const target = useQuery({ queryKey: ['target', id], queryFn: () => api<Target>('GET', `/api/targets/${id}`) })
  const filters = {
    from: valid ? new Date(from).toISOString() : '',
    to: valid ? new Date(to).toISOString() : '',
    waitClass, sqlId, sessionId, sessionSerial,
  }
  const samples = useQuery({
    queryKey: ['samples', id, filterKey, newestFirst, offset],
    queryFn: () => api<SamplesResult>('GET', `/api/targets/${id}/samples?` + query({
      ...filters, order: newestFirst ? 'desc' : 'asc', limit: PAGE_SIZE, offset,
    })),
    enabled: valid,
    placeholderData: keepPreviousData,
  })

  const remove = (...names: string[]) => {
    const next = new URLSearchParams(search)
    names.forEach((n) => next.delete(n))
    setSearch(next, { replace: true })
  }
  const oracle = target.data?.dbType === 'ORACLE'
  const data = samples.data
  const rows = data?.rows ?? []
  const keptSince = data ? new Date(data.keptSince).getTime() : 0
  const range = `from=${from}&to=${to}`

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs">
            <Link to="/">Overview</Link> / <Link to={`/db/${id}`}>{target.data?.name ?? '…'}</Link> /
          </div>
          <h1>Samples</h1>
        </div>
        {valid && <span className="muted">{formatRange(from, to)} ({formatDuration(to - from)})</span>}
      </div>

      {!valid && <p className="banner">No period was given. Open this page from a chart with “View samples”.</p>}
      {valid && data && from < keptSince && (
        <p className="banner">
          Individual samples are kept since {formatDateTime(keptSince, false)}. Earlier parts of this period only
          have per-minute totals, which the charts use, so they are missing here.
        </p>
      )}

      {valid && (
        <div className="filters">
          <div className="chips">
            {!waitClass && !sqlId && !sessionId && <span className="muted">All samples of the period</span>}
            {waitClass && (
              <span className="chip">Wait class: {waitClass}
                <button aria-label="Remove wait class filter" onClick={() => remove('waitClass')}>×</button>
              </span>
            )}
            {sqlId && (
              <span className="chip">SQL ID: <span className="mono">{sqlId}</span>
                <button aria-label="Remove statement filter" onClick={() => remove('sqlId')}>×</button>
              </span>
            )}
            {sessionId && (
              <span className="chip">Session: <span className="mono">{sessionId}{oracle && sessionSerial && `,${sessionSerial}`}</span>
                <button aria-label="Remove session filter" onClick={() => remove('sessionId', 'sessionSerial')}>×</button>
              </span>
            )}
          </div>
          <a href={`/api/targets/${id}/samples.csv?` + query(filters)} download>Download CSV</a>
        </div>
      )}

      {valid && (
        <section className="panel">
          <div className="panel-head">
            <h2>
              {data
                ? `${data.totalCapped ? 'More than ' : ''}${data.total.toLocaleString()} sample${data.total === 1 ? '' : 's'}`
                : 'Samples'}
            </h2>
            <span className="muted">One row is one session that was active at that second</span>
          </div>
          {samples.error && <p className="error-text pad">{samples.error.message}</p>}
          {samples.isPending && <p className="muted pad">Loading…</p>}
          {data && rows.length === 0 && <p className="muted pad">No samples match in this period.</p>}
          {rows.length > 0 && (
            <div className="table-scroll">
              <table className="data-table samples-table">
                <thead>
                  <tr>
                    <th aria-sort={newestFirst ? 'descending' : 'ascending'}>
                      <button onClick={() => setNewestFirst((v) => !v)} title="Change the order">
                        Time {newestFirst ? '↓' : '↑'}
                      </button>
                    </th>
                    <th>Session</th>
                    <th>User</th>
                    <th>Program</th>
                    <th>SQL ID</th>
                    <th>Type</th>
                    <th>Wait class</th>
                    <th>Event</th>
                    {data!.detailKeys.map((key) => <th key={key}>{DETAIL_LABELS[key] ?? key}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, i) => (
                    <tr key={`${row.time}:${row.sessionId}:${row.sessionSerial}:${i}`}>
                      <td className="mono">{formatDateTime(new Date(row.time).getTime())}</td>
                      <td className="mono">
                        <Link to={`/db/${id}/session/${row.sessionId}/${row.sessionSerial}?${range}`}>
                          {row.sessionId}{oracle && <span className="muted">,{row.sessionSerial}</span>}
                        </Link>
                      </td>
                      <td>{row.userName ?? ''}</td>
                      <td className="ellipsis" title={row.program ?? undefined}>{row.program ?? ''}</td>
                      <td className="mono">
                        {row.sqlId && <Link to={`/db/${id}/sql/${encodeURIComponent(row.sqlId)}?${range}`}>{row.sqlId}</Link>}
                      </td>
                      <td>{row.sqlOpname ?? ''}</td>
                      <td>
                        <span className="wait-cell">
                          <span className="tip-swatch" style={{ background: waitClassColor(row.waitClass) }} />
                          {row.waitClass}
                        </span>
                      </td>
                      <td>{row.event}</td>
                      {data!.detailKeys.map((key) => (
                        <td key={key} className="ellipsis" title={row.details[key]}>{row.details[key] ?? ''}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data && data.total > 0 && (
            <div className="pager">
              <span className="muted">
                Rows {(data.offset + 1).toLocaleString()}–{(data.offset + rows.length).toLocaleString()} of{' '}
                {data.totalCapped ? 'more than ' : ''}{data.total.toLocaleString()}
                {data.totalCapped && '. Narrow the period to reach the rest; a download holds the first 100,000.'}
              </span>
              <span className="pager-buttons">
                <button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>Previous</button>
                <button disabled={offset + PAGE_SIZE >= data.total} onClick={() => setOffset(offset + PAGE_SIZE)}>Next</button>
              </span>
            </div>
          )}
        </section>
      )}
    </>
  )
}
