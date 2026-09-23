const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()

// Mock data is opt-in. A missing local env file must not silently turn a real
// demonstration into the preview application.
export const isMockMode = import.meta.env.VITE_USE_MOCK === 'true'
export const apiBaseUrl = isMockMode
  ? null
  : (configuredApiBaseUrl || 'http://localhost:8080').replace(/\/$/, '')
