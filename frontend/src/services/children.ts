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

function ageInCompletedMonths(birthDate: string, today: Date) {
  const [year, month, day] = birthDate.split('-').map(Number)
  let months = (today.getFullYear() - year) * 12 + today.getMonth() + 1 - month
  if (today.getDate() < day) months -= 1
  return months
}

async function mockRegisterChild(input: ChildRegistrationInput, idempotencyKey: string) {
  await new Promise((resolve) => setTimeout(resolve, 800))

  if (new URLSearchParams(window.location.search).get('registrationMock') === 'fail-once' && !failedOnce) {
    failedOnce = true
    throw new Error('Mock registration failure')
  }

  const previous = mockResults.get(idempotencyKey)
  if (previous) return previous

  const ageMonths = ageInCompletedMonths(input.birthDate, new Date())
  const stage = ageMonths < 12 ? 'INFANT' : ageMonths < 36 ? 'TODDLER' : ageMonths < 96 ? 'ACTIVE_CHILD' : null
  const child: RegisteredChild = {
    childId: crypto.randomUUID(),
    ...input,
    safetyProfile: {
      status: stage ? 'APPLIED' : 'UNSUPPORTED',
      stage,
      ageMonths,
      appliedAt: stage ? new Date().toISOString() : null,
    },
  }
  mockResults.set(idempotencyKey, child)
  return child
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
