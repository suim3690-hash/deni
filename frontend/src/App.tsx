import { useCallback, useState } from 'react'
import ChildRegistration from './pages/ChildRegistration'
import RegisteredHome from './pages/RegisteredHome'
import type { RegisteredChild } from './services/children'
import { isMockMode } from './lib/runtime'

const childSessionKey = isMockMode
  ? 'deni:registered-child:mock:v1'
  : 'deni:registered-child:api:v1'

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

function restoreRegisteredChild(): RegisteredChild | null {
  // This only remembers which child was selected until authentication can
  // restore the guardian's default child from the server session.
  try {
    const saved = sessionStorage.getItem(childSessionKey)
    if (!saved) return null
    const child: unknown = JSON.parse(saved)
    if (isRegisteredChild(child)) return child
    sessionStorage.removeItem(childSessionKey)
  } catch {
    // Storage can be unavailable in private or restricted browser contexts.
  }
  return null
}

function App() {
  const [child, setChild] = useState<RegisteredChild | null>(restoreRegisteredChild)
  const [registrationNotice, setRegistrationNotice] = useState('')

  const persistChild = useCallback((registeredChild: RegisteredChild) => {
    setRegistrationNotice('')
    try {
      sessionStorage.setItem(childSessionKey, JSON.stringify(registeredChild))
    } catch {
      // Registration still works when the browser refuses session storage.
    }
    setChild(registeredChild)
  }, [])

  const clearChild = useCallback((message: string) => {
    try {
      sessionStorage.removeItem(childSessionKey)
    } catch {
      // The registration screen remains available without session storage.
    }
    setChild(null)
    setRegistrationNotice(message)
  }, [])

  return child ? <RegisteredHome child={child} onUpdateChild={persistChild} onChildUnavailable={clearChild} /> : <ChildRegistration onGoHome={persistChild} notice={registrationNotice} />
}

export default App
