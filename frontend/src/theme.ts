import { useSyncExternalStore } from 'react'

export interface Theme {
  id: string
  label: string
  dark: boolean
}

/** Visual styles. The CSS for each lives in themes.css under :root[data-theme="<id>"]. */
export const THEMES: Theme[] = [
  { id: 'classic', label: 'Classic', dark: false },
  { id: 'classic-dark', label: 'Classic Dark', dark: true },
  { id: 'slate', label: 'Slate', dark: false },
  { id: 'midnight', label: 'Midnight', dark: true },
  { id: 'glass', label: 'Glass', dark: false },
  { id: 'aurora', label: 'Aurora', dark: true },
  { id: 'terminal', label: 'Terminal', dark: true },
  { id: 'paper', label: 'Paper', dark: false },
  { id: 'bold', label: 'Bold', dark: false },
  { id: 'indigo', label: 'Indigo', dark: false },
]

const STORAGE_KEY = 'ashweb.theme'
const listeners = new Set<() => void>()

function initial(): Theme {
  let stored: string | null = null
  try {
    stored = localStorage.getItem(STORAGE_KEY)
  } catch {
    // Storage can be blocked; fall through to the default
  }
  const found = THEMES.find((t) => t.id === stored)
  if (found) return found
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  return THEMES[prefersDark ? 1 : 0]
}

let current = initial()
document.documentElement.dataset.theme = current.id

export function currentTheme(): Theme {
  return current
}

export function setTheme(id: string) {
  const next = THEMES.find((t) => t.id === id)
  if (!next || next === current) return
  current = next
  document.documentElement.dataset.theme = next.id
  try {
    localStorage.setItem(STORAGE_KEY, next.id)
  } catch {
    // The choice then lasts only until the page is reloaded
  }
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, currentTheme)
}
