import { useCallback, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { apiKeyStore, RetryxApiError } from './lib/api'
import { Connect } from './pages/Connect'
import { Overview } from './pages/Overview'
import { Deliveries } from './pages/Deliveries'
import { DeliveryDetail } from './pages/DeliveryDetail'
import { Endpoints } from './pages/Endpoints'

/**
 * Query defaults.
 *
 * `retry` is a function rather than a number because retrying a 401 is pointless and harmful: the
 * key is wrong, three more attempts will not fix it, and all they do is delay the moment the user
 * is told to reconnect. Same for a 404. Everything else gets one retry, which covers a transient
 * network blip without turning a server outage into a request storm from every open tab.
 *
 * `refetchOnWindowFocus` is off. The lists here poll on their own schedule, and adding a burst of
 * refetches every time someone alt-tabs back just doubles the request rate for no new information.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (error instanceof RetryxApiError && (error.status === 401 || error.status === 404)) {
          return false
        }
        return failureCount < 1
      },
      staleTime: 1500,
      refetchOnWindowFocus: false,
    },
  },
})

export function App() {
  const [connected, setConnected] = useState(() => apiKeyStore.get() !== null)

  const disconnect = useCallback(() => {
    apiKeyStore.clear()
    // Drop every cached response. Without this, the next person to connect would briefly see the
    // previous tenant's deliveries rendered from cache before the new requests resolved.
    queryClient.clear()
    setConnected(false)
  }, [])

  if (!connected) {
    return (
      <QueryClientProvider client={queryClient}>
        <Connect onConnected={() => setConnected(true)} />
      </QueryClientProvider>
    )
  }

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Overview onDisconnect={disconnect} />} />
          <Route path="/deliveries" element={<Deliveries onDisconnect={disconnect} />} />
          <Route path="/deliveries/:id" element={<DeliveryDetail onDisconnect={disconnect} />} />
          <Route path="/endpoints" element={<Endpoints onDisconnect={disconnect} />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
