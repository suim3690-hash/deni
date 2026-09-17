import { useState } from 'react'
import ChildRegistration from './pages/ChildRegistration'
import RegisteredHome from './pages/RegisteredHome'
import type { RegisteredChild } from './services/children'

function App() {
  const [child, setChild] = useState<RegisteredChild | null>(null)
  return child ? <RegisteredHome child={child} onUpdateChild={setChild} /> : <ChildRegistration onGoHome={setChild} />
}

export default App
