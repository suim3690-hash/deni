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

export async function getMonthlyReport(childId: string, childName: string, month: string,
  signal?: AbortSignal): Promise<MonthlyReport> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  if (!baseUrl) return {
    reportId: `preview-${childId}-${month}`,
    childId, childName, month, isMock: true,
    stageChange: null,
    stageChanges: [],
    summary: { detectionCount: 12, avoidanceRatePercent: 100, safeCleanedAreaSquareMeters: null },
    detectionsByObject: [
      { objectType: 'TOY_PART', label: '레고 브릭', count: 3, riskLevel: 'VERY_HIGH' },
      { objectType: 'COIN', label: '100원 동전', count: 2, riskLevel: 'HIGH' },
      { objectType: 'MAGNET', label: '작은 자석', count: 1, riskLevel: 'VERY_HIGH' },
      { objectType: 'OTHER', label: '기타', count: 6, riskLevel: 'MEDIUM' },
    ],
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
