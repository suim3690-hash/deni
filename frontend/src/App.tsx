import { useState } from 'react'
import ChildRegister from './pages/ChildRegister'
import Home from './pages/Home'

type Screen = 'register' | 'home'

function App() {
  const [screen, setScreen] = useState<Screen>('register')
  const [childName, setChildName] = useState('')

  if (screen === 'home') {
    return <Home childName={childName} />
  }

  return (
    <ChildRegister
      onRegistered={(name) => {
        setChildName(name)
        setScreen('home')
      }}
    />
  )
}

export default App
