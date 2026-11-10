import type { CollectorStatus } from '../api'

const LABEL = {
  RUNNING: 'Collecting',
  STARTING: 'Starting',
  ERROR: 'Error',
  STOPPED: 'Stopped',
} as const

const ICON = { RUNNING: '●', STARTING: '◌', ERROR: '▲', STOPPED: '■' } as const

/** Collector state as an icon plus a word, so it never depends on colour alone. */
export function StatusBadge({ status }: { status: CollectorStatus }) {
  return (
    <span className={`status status-${status.state.toLowerCase()}`} title={status.lastError ?? undefined}>
      <span aria-hidden="true">{ICON[status.state]}</span> {LABEL[status.state]}
    </span>
  )
}
