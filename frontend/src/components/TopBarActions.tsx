import { useEffect, useRef, useState, type ReactNode } from 'react'

/**
 * The bell and the "?" in the top bar. Each opens a small panel under its
 * button; one open at a time, and a click outside or Escape closes it.
 *
 * There is no notifications backend yet, so the bell's panel is an honest
 * empty state rather than a list that is never filled.
 */

type Panel = 'notifications' | 'help' | null

function BellIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 9a6 6 0 1 1 12 0c0 5 2 6.5 2 6.5H4S6 14 6 9Z" />
      <path d="M10 19a2 2 0 0 0 4 0" />
    </svg>
  )
}

function HelpIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9.5" />
      <path d="M9.4 9.3a2.7 2.7 0 0 1 5.2.9c0 1.8-2.6 2.2-2.6 3.9" />
      <circle cx="12" cy="17" r="0.6" fill="currentColor" />
    </svg>
  )
}

function IconButton({
  label,
  open,
  onClick,
  children,
}: {
  label: string
  open: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      className={`topbar-icon${open ? ' open' : ''}`}
      aria-label={label}
      title={label}
      aria-haspopup="true"
      aria-expanded={open}
      onClick={onClick}
    >
      {children}
    </button>
  )
}

export function TopBarActions() {
  const [open, setOpen] = useState<Panel>(null)
  const root = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(null)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(null)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const toggle = (panel: Exclude<Panel, null>) => setOpen((cur) => (cur === panel ? null : panel))

  return (
    <div className="topbar-actions" ref={root}>
      <div className="topbar-action">
        <IconButton label="Notifications" open={open === 'notifications'} onClick={() => toggle('notifications')}>
          <BellIcon />
        </IconButton>
        {open === 'notifications' && (
          <div className="topbar-panel" role="dialog" aria-label="Notifications">
            <h3>Notifications</h3>
            <p className="topbar-empty">No notifications yet.</p>
            <p className="topbar-hint">Alerts about procedures and inspections will show up here.</p>
          </div>
        )}
      </div>
      <div className="topbar-action">
        <IconButton label="Help" open={open === 'help'} onClick={() => toggle('help')}>
          <HelpIcon />
        </IconButton>
        {open === 'help' && (
          <div className="topbar-panel wide" role="dialog" aria-label="Help">
            <h3>How procedures work</h3>
            <ul>
              <li>
                <strong>Draft, then publish.</strong> You edit a draft. Publishing makes it a new version; editing a
                published procedure starts a new draft and the published one stays live.
              </li>
              <li>
                <strong>A name is required</strong> before you can save, preview or publish.
              </li>
              <li>
                <strong>Autosave</strong> runs every 30 seconds on an open draft. Use Save draft to save at once.
              </li>
              <li>
                <strong>Follow-ups</strong> are shown only when their answer is picked. They can go three levels deep.
              </li>
            </ul>
            <h3>Shortcuts in the builder</h3>
            <ul>
              <li>
                <kbd>Enter</kbd> in Quick add adds the question; <kbd>Shift</kbd>+<kbd>Enter</kbd> starts a new line.
              </li>
              <li>
                <kbd>Enter</kbd> or <kbd>Space</kbd> on a procedure card opens it.
              </li>
            </ul>
          </div>
        )}
      </div>
    </div>
  )
}
