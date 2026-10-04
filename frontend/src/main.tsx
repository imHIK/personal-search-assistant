import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { Toaster } from 'sonner'
import { TechnicalDetailsProvider } from '@/components/TechnicalDetails'
import { initTheme } from '@/components/ThemeToggle'
import { router } from '@/router'
import './index.css'

initTheme()

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: true,
      staleTime: 2000,
      retry: (failureCount, error) => {
        if (error instanceof Error && error.name === 'ApiError') return false
        return failureCount < 2
      },
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <TechnicalDetailsProvider>
        <RouterProvider router={router} />
        <Toaster
          position="bottom-right"
          toastOptions={{
            style: {
              background: 'var(--surface)',
              border: '1px solid var(--border)',
              color: 'var(--text)',
            },
          }}
        />
      </TechnicalDetailsProvider>
    </QueryClientProvider>
  </StrictMode>,
)
