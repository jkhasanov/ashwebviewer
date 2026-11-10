import { currentTheme } from './theme'

/**
 * Wait class colours follow the Oracle Enterprise Manager "Top Activity" convention (green CPU, blue I/O,
 * red-brown contention...). Database administrators read these at a glance, so the convention is kept on
 * purpose instead of a generic chart palette. Identity never rests on colour alone: every chart has a
 * legend, a tooltip with names, and the Top tables beside it.
 */
const ORACLE: Record<string, string> = {
  CPU: '#04ce04',
  Scheduler: '#87fd88',
  'User I/O': '#0646e8',
  'System I/O': '#0993e1',
  Concurrency: '#8b1a04',
  Application: '#c02805',
  Commit: '#e46a04',
  Configuration: '#5c460e',
  Administrative: '#717354',
  Network: '#9c9d6c',
  Queueing: '#c2b79b',
  Cluster: '#c9c2af',
  Other: '#f06eaa',
}

/** PostgreSQL wait event types, mapped to the nearest Oracle meaning so both read the same way. */
const POSTGRES: Record<string, string> = {
  CPU: '#04ce04',
  IO: '#0646e8',
  Lock: '#c02805',
  LWLock: '#8b1a04',
  BufferPin: '#0993e1',
  Activity: '#c2b79b',
  Extension: '#717354',
  Client: '#9c9d6c',
  IPC: '#e46a04',
  Timeout: '#5c460e',
}

/** Stacking order, bottom first: CPU at the base like Enterprise Manager. */
export const WAIT_CLASS_ORDER = [...Object.keys(ORACLE), ...Object.keys(POSTGRES).filter((k) => !(k in ORACLE))]

const FALLBACK = '#8a8a86'

/** The darkest convention colours disappear on a dark page, so those few are lightened there (same hue). */
const DARK_OVERRIDE: Record<string, string> = {
  'User I/O': '#3f74f5',
  IO: '#3f74f5',
  Concurrency: '#c2472b',
  LWLock: '#c2472b',
  Application: '#e2502a',
  Lock: '#e2502a',
  Configuration: '#a98a35',
  Timeout: '#a98a35',
  Administrative: '#8f916d',
  Extension: '#8f916d',
}

export function waitClassColor(name: string): string {
  return (currentTheme().dark ? DARK_OVERRIDE[name] : undefined) ?? ORACLE[name] ?? POSTGRES[name] ?? FALLBACK
}

/** Categorical palette for wait events inside one wait class (fixed order, never cycled). */
const EVENT_LIGHT = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948']
const EVENT_DARK = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767']
export const MAX_EVENT_SERIES = EVENT_LIGHT.length
export const OTHER_EVENTS = 'Other events'

export function eventColor(index: number, dark: boolean): string {
  const palette = dark ? EVENT_DARK : EVENT_LIGHT
  return index < palette.length ? palette[index] : FALLBACK
}

export function sortWaitClasses(names: string[]): string[] {
  const rank = (n: string) => {
    const i = WAIT_CLASS_ORDER.indexOf(n)
    return i < 0 ? WAIT_CLASS_ORDER.length : i
  }
  return [...names].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))
}
