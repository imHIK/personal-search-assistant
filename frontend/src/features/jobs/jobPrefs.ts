import { useEffect, useMemo, useState } from 'react'
import { jobDashboard } from '@/config/jobDashboard'

const keys = jobDashboard.storage

// Storage can be missing or throw (private windows, blocked site data); every read degrades to empty.
function read(store: () => Storage, key: string): string | null {
  try {
    return store().getItem(key)
  } catch {
    return null
  }
}

function write(store: () => Storage, key: string, value: string) {
  try {
    store().setItem(key, value)
  } catch {
    // Not persisted; the page still works for this visit.
  }
}

const local = () => window.localStorage
const session = () => window.sessionStorage

/**
 * The previous visit's time, or null on a first visit. Pinned in sessionStorage so reloading the tab
 * keeps the same postings marked new.
 */
export function useNewSince(): number | null {
  const [since] = useState(() => {
    const pinned = read(session, keys.since) ?? read(local, keys.lastVisit) ?? ''
    write(session, keys.since, pinned)
    write(local, keys.lastVisit, new Date().toISOString())
    const at = Date.parse(pinned)
    return Number.isNaN(at) ? null : at
  })
  return since
}

export function skillKey(skill: string): string {
  return skill.trim().toLowerCase().replace(/\s+/g, ' ')
}

export function useMySkills() {
  const [raw, setRaw] = useState(() => read(local, keys.mySkills) ?? '')
  useEffect(() => write(local, keys.mySkills, raw), [raw])
  const skills = useMemo(() => new Set(raw.split(',').map(skillKey).filter(Boolean)), [raw])
  return { raw, setRaw, skills }
}
