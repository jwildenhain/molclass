"use client"

import * as React from "react"
import { Moon, Sun } from "lucide-react"
import { useTheme } from "next-themes"

// Never emits a change, so the client snapshot is read once after hydration.
const subscribeToNothing = () => () => {}

export function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  // Avoid hydration mismatch: false on the server and during hydration, true afterwards.
  const mounted = React.useSyncExternalStore(
    subscribeToNothing,
    () => true,
    () => false,
  )

  if (!mounted) {
    return <div className="w-9 h-9" /> // placeholder with same dimensions
  }

  return (
    <button
      onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
      className="inline-flex items-center justify-center rounded-md p-2 text-slate-400 hover:text-slate-900 hover:bg-slate-100 dark:hover:text-slate-100 dark:hover:bg-slate-800 transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-400"
      aria-label="Toggle theme"
    >
      {theme === "dark" ? (
        <Moon className="h-5 w-5" />
      ) : (
        <Sun className="h-5 w-5" />
      )}
    </button>
  )
}
