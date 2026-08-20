import { NavLink, Navigate, Outlet, Route, Routes } from 'react-router'

import { CommandPalette } from './components/CommandPalette'
import { SearchIcon } from './components/icons'
import { ToastProvider } from './components/ToastProvider'
import ConnectionFormPage from './pages/ConnectionFormPage'
import ConnectionsPage from './pages/ConnectionsPage'
import LogsPage from './pages/LogsPage'
import ManualPage from './pages/ManualPage'
import QueueExplorerPage from './pages/QueueExplorerPage'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `rounded-lg px-3 py-1.5 text-sm font-medium transition-colors duration-150 ${
    isActive
      ? 'bg-brand-50 text-brand-700'
      : 'text-fg-muted hover:bg-hover hover:text-fg'
  }`

function Layout() {
  return (
    <div className="min-h-full bg-bg text-fg">
      <header className="sticky top-0 z-30 border-b border-line/80 bg-surface/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-6 py-3">
          <div className="flex items-center gap-2.5">
            {/* The app icon (same image the .exe uses), so the product mark matches everywhere. */}
            <img
              src="/app-icon.png"
              alt="MQ mebaysanization"
              className="h-8 w-8 rounded-lg object-cover shadow-xs ring-1 ring-inset ring-line"
            />
            <span className="text-[15px] font-semibold tracking-tight text-fg">
              mebaysanization
            </span>
          </div>
          <nav className="flex items-center gap-1">
            {/* Connections is matched with `end` so it does not stay highlighted while a nested
                queue or edit route is open. */}
            <NavLink to="/connections" end className={navLinkClass}>
              Connections
            </NavLink>
            <NavLink to="/logs" className={navLinkClass}>
              Logs
            </NavLink>
            <NavLink to="/manual" className={navLinkClass}>
              Manual
            </NavLink>
          </nav>
          <div className="ml-auto flex items-center gap-3">
            <button
              type="button"
              onClick={() => window.dispatchEvent(new Event('command-palette:open'))}
              className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface py-1.5 pl-2.5 pr-2 text-xs text-fg-subtle shadow-xs transition-colors hover:bg-hover hover:text-fg-muted"
              aria-label="Open command palette"
            >
              <SearchIcon size={14} />
              <span className="hidden sm:inline">Search…</span>
              <kbd className="rounded border border-line bg-surface-2 px-1 font-mono text-[10px] text-fg-subtle">
                ⌘K
              </kbd>
            </button>
            <span
              className="inline-flex items-center gap-1.5 rounded-full bg-amber-50 px-2.5 py-1 text-xs font-medium text-amber-700 ring-1 ring-inset ring-amber-200"
              title="This build has no login. Anyone who can reach this port can operate every configured broker."
            >
              <span className="h-1.5 w-1.5 rounded-full bg-amber-500" aria-hidden="true" />
              No authentication
            </span>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">
        <Outlet />
      </main>
    </div>
  )
}

export default function App() {
  return (
    <ToastProvider>
      <CommandPalette />
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<Navigate to="/connections" replace />} />
          <Route path="/connections" element={<ConnectionsPage />} />
          <Route path="/connections/new" element={<ConnectionFormPage />} />
          <Route path="/connections/:id/edit" element={<ConnectionFormPage />} />
          <Route path="/connections/:id/queue" element={<QueueExplorerPage />} />
          <Route path="/logs" element={<LogsPage />} />
          <Route path="/manual" element={<ManualPage />} />
          <Route
            path="*"
            element={
              <div className="text-sm text-fg-muted">
                That page does not exist.{' '}
                <a href="/connections" className="text-brand-700 hover:underline">
                  Back to connections
                </a>
              </div>
            }
          />
        </Route>
      </Routes>
    </ToastProvider>
  )
}
