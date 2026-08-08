import { Navigate, Outlet, Route, Routes } from 'react-router'

import { ToastProvider } from './components/ToastProvider'
import ConnectionFormPage from './pages/ConnectionFormPage'
import ConnectionsPage from './pages/ConnectionsPage'
import QueueExplorerPage from './pages/QueueExplorerPage'

function Layout() {
  return (
    <div className="min-h-full bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <span className="text-base font-semibold text-slate-900">MQ mebaysanization</span>
          <span
            className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 ring-1 ring-inset ring-amber-200"
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
