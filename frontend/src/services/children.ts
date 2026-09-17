export interface ChildRegistrationInput {
  name: string
  birthDate: string
}

export interface RegisteredChild extends ChildRegistrationInput {
  childId: string
  safetyProfile: {
    status: 'APPLIED' | 'UNSUPPORTED'
    stage: 'INFANT' | 'TODDLER' | 'ACTIVE_CHILD' | null
    ageMonths: number
    appliedAt: string | null
  }
}

const mockResults = new Map<string, RegisteredChild>()
let failedOnce = false

export function localToday() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function ageInCompletedMonths(birthDate: string, today: Date) {
  const [year, month, day] = birthDate.split('-').map(Number)
  let months = (today.getFullYear() - year) * 12 + today.getMonth() + 1 - month
  if (today.getDate() < day) months -= 1
  return months
}

export function computeSafetyProfile(birthDate: string): RegisteredChild['safetyProfile'] {
  const ageMonths = ageInCompletedMonths(birthDate, new Date())
  const stage = ageMonths < 12 ? 'INFANT' : ageMonths < 36 ? 'TODDLER' : ageMonths < 96 ? 'ACTIVE_CHILD' : null
  return {
    status: stage ? 'APPLIED' : 'UNSUPPORTED',
    stage,
    ageMonths,
    appliedAt: stage ? new Date().toISOString() : null,
  }
}

async function mockRegisterChild(input: ChildRegistrationInput, idempotencyKey: string) {
  await new Promise((resolve) => setTimeout(resolve, 800))

  if (new URLSearchParams(window.location.search).get('registrationMock') === 'fail-once' && !failedOnce) {
    failedOnce = true
    throw new Error('Mock registration failure')
  }

  const previous = mockResults.get(idempotencyKey)
  if (previous) return previous

  const child: RegisteredChild = {
    childId: crypto.randomUUID(),
    ...input,
    safetyProfile: computeSafetyProfile(input.birthDate),
  }
  mockResults.set(idempotencyKey, child)
  return child
}

async function mockUpdateChild(childId: string, input: ChildRegistrationInput): Promise<RegisteredChild> {
  await new Promise((resolve) => setTimeout(resolve, 500))
  return {
    childId,
    ...input,
    safetyProfile: computeSafetyProfile(input.birthDate),
  }
}

export async function registerChild(input: ChildRegistrationInput, idempotencyKey: string): Promise<RegisteredChild> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  if (!baseUrl) return mockRegisterChild(input, idempotencyKey)

  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/children`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(input),
  })

  if (!response.ok) throw new Error(`Registration failed: ${response.status}`)
  return response.json() as Promise<RegisteredChild>
}

export async function updateChild(childId: string, input: ChildRegistrationInput): Promise<RegisteredChild> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  if (!baseUrl) return mockUpdateChild(childId, input)

  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/children/${childId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })

  if (!response.ok) throw new Error(`Update failed: ${response.status}`)
  return response.json() as Promise<RegisteredChild>
}
