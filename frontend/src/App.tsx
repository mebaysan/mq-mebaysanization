import { NavLink, Navigate, Outlet, Route, Routes } from 'react-router'

import { ToastProvider } from './components/ToastProvider'
import ConnectionFormPage from './pages/ConnectionFormPage'
import ConnectionsPage from './pages/ConnectionsPage'
import LogsPage from './pages/LogsPage'
import ManualPage from './pages/ManualPage'
import QueueExplorerPage from './pages/QueueExplorerPage'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `rounded-lg px-2 py-1 text-sm font-medium ${
    isActive ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
  }`

function Layout() {
  return (
    <div className="min-h-full bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-6 py-4">
          <span className="text-base font-semibold text-slate-900">MQ mebaysanization</span>
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
          <span
            className="ml-auto rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 ring-1 ring-inset ring-amber-200"
            title="This build has no login. Anyone who can reach this port can operate every configured broker."
          >
            No authentication
          </span>
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
              <div className="text-sm text-slate-600">
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
