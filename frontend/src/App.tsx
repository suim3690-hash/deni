import { useState } from 'react'
import ChildRegistration from './pages/ChildRegistration'
import RegisteredHome from './pages/RegisteredHome'
import type { RegisteredChild } from './services/children'

const mockChildSessionKey = 'deni:registered-child:v1'

function isRegisteredChild(value: unknown): value is RegisteredChild {
  if (!value || typeof value !== 'object') return false
  const child = value as Partial<RegisteredChild>
  const profile = child.safetyProfile
  return typeof child.childId === 'string' && child.childId.length > 0
    && typeof child.name === 'string' && child.name.length > 0
    && typeof child.birthDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(child.birthDate)
    && !!profile && (profile.status === 'APPLIED' || profile.status === 'UNSUPPORTED')
    && (profile.stage === null || profile.stage === 'INFANT' || profile.stage === 'TODDLER' || profile.stage === 'ACTIVE_CHILD')
    && typeof profile.ageMonths === 'number' && Number.isFinite(profile.ageMonths)
    && (profile.appliedAt === null || typeof profile.appliedAt === 'string')
}

function restoreMockChild(): RegisteredChild | null {
  // Once the API is connected, the registered child must be restored from the
  // authenticated server session instead of trusting browser storage.
  if (import.meta.env.VITE_API_BASE_URL) return null
  try {
    const saved = sessionStorage.getItem(mockChildSessionKey)
    if (!saved) return null
    const child: unknown = JSON.parse(saved)
    if (isRegisteredChild(child)) return child
    sessionStorage.removeItem(mockChildSessionKey)
  } catch {
    // Storage can be unavailable in private or restricted browser contexts.
  }
  return null
}

function App() {
  const [child, setChild] = useState<RegisteredChild | null>(restoreMockChild)

  function openHome(registeredChild: RegisteredChild) {
    if (!import.meta.env.VITE_API_BASE_URL) {
      try {
        sessionStorage.setItem(mockChildSessionKey, JSON.stringify(registeredChild))
      } catch {
        // Registration still works when the browser refuses session storage.
      }
    }
    setChild(registeredChild)
  }

  return child ? <RegisteredHome child={child} /> : <ChildRegistration onGoHome={openHome} />
}

export default App
