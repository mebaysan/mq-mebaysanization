import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// React Router v8: `react-router-dom` no longer exists. Everything comes from 'react-router'.
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import App from './App'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // This is an admin tool driven by deliberate clicks, not a live dashboard. Refetching on every
      // window focus would fire real broker connections behind the user's back.
      refetchOnWindowFocus: false,
      retry: 1,
      staleTime: 5_000,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
