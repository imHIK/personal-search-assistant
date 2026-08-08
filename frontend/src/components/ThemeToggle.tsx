import { Monitor, Moon, Sun } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/Button'

/**
 * Light / dark / follow-system, persisted. Applied by toggling `.dark` on <html>, which is what
 * every token in index.css keys off — no component ever branches on the theme.
 */

type Theme = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'psa.theme'

function apply(theme: Theme) {
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  const dark = theme === 'dark' || (theme === 'system' && prefersDark)
  document.documentElement.classList.toggle('dark', dark)
}

export function initTheme() {
  apply((localStorage.getItem(STORAGE_KEY) as Theme | null) ?? 'system')
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(
    () => (localStorage.getItem(STORAGE_KEY) as Theme | null) ?? 'system',
  )

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, theme)
    apply(theme)

    if (theme !== 'system') return
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const listener = () => apply('system')
    media.addEventListener('change', listener)
    return () => media.removeEventListener('change', listener)
  }, [theme])

  const next: Record<Theme, Theme> = { system: 'light', light: 'dark', dark: 'system' }
  const Icon = { system: Monitor, light: Sun, dark: Moon }[theme]
  const description = { system: 'Follow system', light: 'Light', dark: 'Dark' }[theme]

  return (
    <Button
      variant="ghost"
      size="iconSm"
      onClick={() => setTheme(next[theme])}
      aria-label={`Theme: ${description}. Click to change.`}
      title={`Theme: ${description}`}
    >
      <Icon />
    </Button>
  )
}
