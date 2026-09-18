import { generateId } from '../lib/id'
import { apiErrorFromResponse } from './apiError'

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

export interface SafetyCriterion {
  code: string
  title: string
  description: string
}

export type SafetyProfileData = RegisteredChild['safetyProfile'] & {
  childId: string
  stageLabel: string | null
  criteria: SafetyCriterion[]
}

type SupportedStage = NonNullable<RegisteredChild['safetyProfile']['stage']>

const mockStageLabels: Record<SupportedStage, string> = {
  INFANT: '바닥 탐색 시기',
  TODDLER: '걸음마 시기',
  ACTIVE_CHILD: '유아 활동기',
}

const mockCriteria: Record<SupportedStage, SafetyCriterion[]> = {
  INFANT: [{
    code: 'CHOKING',
    title: '바닥 이물질·삼킴 위험 탐지 강화',
    description: '바닥에 떨어진 작은 물체와 삼킴 위험 물건을 중심으로 집중 모니터링합니다.',
  }],
  TODDLER: [{
    code: 'MOVEMENT_HAZARD',
    title: '모서리·문턱·전선 등 이동 위험 탐지 강화',
    description: '가구 모서리, 바닥 문턱, 콘센트와 전선 걸림 위험을 집중 모니터링합니다.',
  }],
  ACTIVE_CHILD: [{
    code: 'WIDE_AREA_HAZARD',
    title: '활동 반경에 따른 광범위 위험 탐지',
    description: '집 전체 활동 반경에서 낙상과 충돌 위험을 폭넓게 모니터링합니다.',
  }],
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
    childId: generateId(),
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

async function mockGetSafetyProfile(childId: string, birthDate: string): Promise<SafetyProfileData> {
  await new Promise((resolve) => setTimeout(resolve, 300))
  const profile = computeSafetyProfile(birthDate)
  const stage = profile.stage
  return {
    childId,
    ...profile,
    stageLabel: stage ? mockStageLabels[stage] : null,
    criteria: stage ? mockCriteria[stage] : [],
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

  if (!response.ok) throw await apiErrorFromResponse(response, '아이 정보를 등록하지 못했어요.')
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

  if (!response.ok) throw await apiErrorFromResponse(response, '아이 정보를 수정하지 못했어요.')
  return response.json() as Promise<RegisteredChild>
}

export async function getSafetyProfile(childId: string, birthDate: string): Promise<SafetyProfileData> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  if (!baseUrl) return mockGetSafetyProfile(childId, birthDate)

  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/children/${encodeURIComponent(childId)}/safety-profile`)
  if (!response.ok) throw await apiErrorFromResponse(response, '안전점검 기준을 불러오지 못했어요.')
  return response.json() as Promise<SafetyProfileData>
}
