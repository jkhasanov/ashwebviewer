import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, query } from '../api'
import type { ActivityReport, Target } from '../api'
import { formatDateTime, formatDuration, formatRange } from '../format'

/** The report the database itself writes about a period (Oracle's ASH report). */
export function ReportPage() {
  const id = Number(useParams().id)
  const [search] = useSearchParams()
  const from = Number(search.get('from'))
  const to = Number(search.get('to'))
  const waitClass = search.get('waitClass') ?? ''
  const sqlId = search.get('sqlId') ?? ''
  const sessionId = search.get('sessionId') ?? ''
  const valid = from > 0 && to > from

  const target = useQuery({ queryKey: ['target', id], queryFn: () => api<Target>('GET', `/api/targets/${id}`) })
  // Writing the report is real work for the monitored database, so it is asked for once and kept
  const report = useQuery({
    queryKey: ['report', id, search.toString()],
    queryFn: () => api<ActivityReport>('GET', `/api/targets/${id}/report?` + query({
      from: new Date(from).toISOString(), to: new Date(to).toISOString(), waitClass, sqlId, sessionId,
    })),
    enabled: valid,
    retry: false,
    staleTime: Infinity,
    gcTime: 10 * 60_000,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  const html = report.data?.html ?? null
  const download = () => {
    if (!html) return
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `ash-report-${target.data?.name ?? id}-${formatDateTime(from, false).replace(/[: ]/g, '-')}.html`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  const cpuOnly = waitClass === 'CPU'
  const narrowed = [
    waitClass && !cpuOnly && `wait class ${waitClass}`,
    sqlId && `SQL ID ${sqlId}`,
    sessionId && `session ${sessionId}`,
  ].filter(Boolean).join(', ')
  const skew = target.data?.collector.clockSkewSeconds ?? 0

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs">
            <Link to="/">Overview</Link> / <Link to={`/db/${id}`}>{target.data?.name ?? '…'}</Link> /
          </div>
          <h1>ASH report</h1>
        </div>
        {valid && <span className="muted">{formatRange(from, to)} ({formatDuration(to - from)})</span>}
      </div>

      {!valid && <p className="banner">No period was given. Open this page from a chart with “ASH report”.</p>}
      {valid && cpuOnly && (
        <p className="banner">Oracle cannot narrow its report to CPU, so this report covers all activity of the period.</p>
      )}
      {valid && Math.abs(skew) >= 60 && (
        <p className="banner">
          Times inside the report are on the database’s own clock, which is {formatDuration(Math.abs(skew) * 1000)}{' '}
          {skew > 0 ? 'ahead of' : 'behind'} the times shown elsewhere in this application.
        </p>
      )}

      {valid && (
        <section className="panel">
          <div className="panel-head">
            <h2>{narrowed ? `Report for ${narrowed}` : 'Report for all activity'}</h2>
            <span className="period-actions">
              <button onClick={() => report.refetch()} disabled={report.isFetching}>Write again</button>
              <button onClick={download} disabled={!html}>Download</button>
            </span>
          </div>
          {report.isFetching && (
            <p className="muted pad">The database is writing the report. This can take a minute for long periods…</p>
          )}
          {!report.isFetching && report.error && <p className="error-text pad">{report.error.message}</p>}
          {!report.isFetching && report.data?.message && <p className="error-text pad">{report.data.message}</p>}
          {/* The report is the database's HTML: shown in a frame that may not run scripts or reach this page */}
          {!report.isFetching && html && (
            <iframe className="report-frame" title="ASH report" sandbox="" srcDoc={html} />
          )}
        </section>
      )}
    </>
  )
}
