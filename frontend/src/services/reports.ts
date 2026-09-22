import { stageByOrder, stageCriteriaDescriptions, stageOrder, type Stage } from '../lib/stages'
import { apiErrorFromResponse } from './apiError'
import { computeSafetyProfile } from './children'
import { riskByStage, type HazardCategory } from '../lib/hazardRisk'

export interface ProfileStageChange {
  from: Stage | null
  to: Stage | null
  changedAt: string
  fromStatus: 'APPLIED' | 'UNSUPPORTED'
  toStatus: 'APPLIED' | 'UNSUPPORTED'
  reason: 'AGE_CHANGED' | 'BIRTH_DATE_UPDATED'
}

export interface MonthlyReport {
  reportId: string
  childId: string
  month: string
  childName: string
  stageChange: ProfileStageChange | null
  stageChanges: ProfileStageChange[]
  summary: {
    detectionCount: number
    avoidanceRatePercent: number | null
    safeCleanedAreaSquareMeters: number | null
  }
  detectionsByObject: { objectType: string; label: string; count: number; riskLevel: string }[]
  criteriaChanges: { title: string; description: string; changedAt: string }[]
  nextStagePreview: { stage: Stage | null; description: string }
  feedback: string | null
  isMock: boolean
}

export function currentReportMonth() {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit',
  }).format(new Date())
}

const mockObjects: { objectType: HazardCategory; label: string }[] = [
  { objectType: 'SWALLOW', label: '구슬' },
  { objectType: 'SWALLOW', label: '동전' },
  { objectType: 'SWALLOW', label: '배터리' },
  { objectType: 'LIVING', label: '전선' },
  { objectType: 'LIVING', label: '콘센트' },
]

// 목업 리포트: 월마다 다른 값이 나오도록 월과 물체 이름으로 결정적인 건수를 만든다.
function mockMonthlyDetections(month: string, birthDate: string) {
  const [year, monthNumber] = month.split('-').map(Number)
  const referenceDate = month === currentReportMonth() ? new Date() : new Date(year, monthNumber, 0)
  const stage = computeSafetyProfile(birthDate, referenceDate).stage
  if (!stage) return []
  return mockObjects
    .map((item) => {
      let hash = 0
      for (const char of `${month}:${item.label}`) hash = (hash * 31 + char.charCodeAt(0)) % 9973
      return { ...item, riskLevel: riskByStage[stage][item.objectType], count: hash % 4 }
    })
    .filter((item) => item.count > 0)
}

// 목업: 생년월일로 그 달에 성장단계가 바뀌었는지 계산한다. 이번 달은 오늘까지만 본다.
function mockStageChangesFor(month: string, birthDate: string): ProfileStageChange[] {
  const [year, monthNumber] = month.split('-').map(Number)
  const before = computeSafetyProfile(birthDate, new Date(year, monthNumber - 1, 0))
  if (before.ageMonths < 0) return []
  const lastDay = month === currentReportMonth() ? new Date().getDate() : new Date(year, monthNumber, 0).getDate()
  for (let day = 1; day <= lastDay; day += 1) {
    const current = computeSafetyProfile(birthDate, new Date(year, monthNumber - 1, day))
    if (current.stage !== before.stage || current.status !== before.status) {
      return [{
        from: before.stage, to: current.stage, fromStatus: before.status, toStatus: current.status,
        changedAt: `${month}-${String(day).padStart(2, '0')}T00:05:00+09:00`, reason: 'AGE_CHANGED',
      }]
    }
  }
  return []
}

// 목업: 백엔드와 같은 기준으로 조회 월의 기준일(이번 달은 오늘, 지난 달은 말일)에서 본 다음 성장단계.
function mockNextStagePreview(month: string, birthDate: string): MonthlyReport['nextStagePreview'] {
  const [year, monthNumber] = month.split('-').map(Number)
  const profile = computeSafetyProfile(birthDate, month === currentReportMonth() ? new Date() : new Date(year, monthNumber, 0))
  if (profile.ageMonths < 0) return { stage: null, description: '조회 월은 등록된 생년월일 이전이므로 다음 단계 안내를 제공하지 않습니다.' }
  if (!profile.stage) return { stage: null, description: '조회 기준일의 월령은 지원 연령 범위 밖입니다.' }
  const next: Stage | undefined = stageByOrder[stageOrder[profile.stage] + 1]
  return next
    ? { stage: next, description: stageCriteriaDescriptions[next] }
    : { stage: null, description: '조회 기준일의 월령은 마지막 지원 성장단계입니다. 96개월부터는 지원 범위 밖입니다.' }
}

export async function getMonthlyReport(childId: string, childName: string, birthDate: string, month: string,
  signal?: AbortSignal): Promise<MonthlyReport> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  const mockDetections = mockMonthlyDetections(month, birthDate)
  // 주소에 ?mockStageChange 를 붙이면 모든 달에 19일 걸음마 시기 → 유아 활동기 전환을 강제로 보여준다(디자인 확인용).
  const forcedChange: ProfileStageChange | null = new URLSearchParams(window.location.search).has('mockStageChange')
    ? { from: 'TODDLER', to: 'ACTIVE_CHILD', changedAt: `${month}-19T00:05:00+09:00`, fromStatus: 'APPLIED', toStatus: 'APPLIED', reason: 'AGE_CHANGED' }
    : null
  if (!baseUrl) {
    const mockChanges = forcedChange ? [forcedChange] : mockStageChangesFor(month, birthDate)
    return {
      reportId: `preview-${childId}-${month}`,
      childId, childName, month, isMock: true,
      stageChange: mockChanges.at(-1) ?? null,
      stageChanges: mockChanges,
      summary: { detectionCount: mockDetections.reduce((sum, item) => sum + item.count, 0), avoidanceRatePercent: null, safeCleanedAreaSquareMeters: null },
      detectionsByObject: mockDetections,
      criteriaChanges: [],
      nextStagePreview: mockNextStagePreview(month, birthDate),
      feedback: null,
    }
  }

  const query = new URLSearchParams({ childId, month })
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/reports/monthly?${query}`, { signal })
  if (!response.ok) throw await apiErrorFromResponse(response, '월간 리포트를 불러오지 못했어요.')
  const data = await response.json() as Omit<MonthlyReport, 'isMock'>
  return { ...data, isMock: false }
}
