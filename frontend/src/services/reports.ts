import type { Stage } from '../lib/stages'
import { apiErrorFromResponse } from './apiError'

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

const mockObjects = [
  { objectType: 'SWALLOW', label: '구슬', riskLevel: 'VERY_HIGH' },
  { objectType: 'SWALLOW', label: '동전', riskLevel: 'VERY_HIGH' },
  { objectType: 'SWALLOW', label: '배터리', riskLevel: 'VERY_HIGH' },
  { objectType: 'LIVING', label: '전선', riskLevel: 'HIGH' },
  { objectType: 'LIVING', label: '콘센트', riskLevel: 'HIGH' },
]

// 목업 리포트: 월마다 다른 값이 나오도록 월과 물체 이름으로 결정적인 건수를 만든다.
function mockMonthlyDetections(month: string) {
  return mockObjects
    .map((item) => {
      let hash = 0
      for (const char of `${month}:${item.label}`) hash = (hash * 31 + char.charCodeAt(0)) % 9973
      return { ...item, count: hash % 4 }
    })
    .filter((item) => item.count > 0)
}

export async function getMonthlyReport(childId: string, childName: string, month: string,
  signal?: AbortSignal): Promise<MonthlyReport> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  const mockDetections = mockMonthlyDetections(month)
  if (!baseUrl) return {
    reportId: `preview-${childId}-${month}`,
    childId, childName, month, isMock: true,
    stageChange: null,
    stageChanges: [],
    summary: { detectionCount: mockDetections.reduce((sum, item) => sum + item.count, 0), avoidanceRatePercent: null, safeCleanedAreaSquareMeters: null },
    detectionsByObject: mockDetections,
    criteriaChanges: [],
    nextStagePreview: { stage: null, description: '화면 확인용 예시 리포트입니다.' },
    feedback: null,
  }

  const query = new URLSearchParams({ childId, month })
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/reports/monthly?${query}`, { signal })
  if (!response.ok) throw await apiErrorFromResponse(response, '월간 리포트를 불러오지 못했어요.')
  const data = await response.json() as Omit<MonthlyReport, 'isMock'>
  return { ...data, isMock: false }
}
