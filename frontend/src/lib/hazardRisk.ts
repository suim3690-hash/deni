import type { Stage } from './stages'

export type HazardCategory = 'SWALLOW' | 'LIVING'
export type RiskLevel = 'VERY_HIGH' | 'HIGH' | 'MEDIUM'

export const categoryLabels: Record<HazardCategory, string> = {
  SWALLOW: '삼킴 위험물',
  LIVING: '생활공간 위험요소',
}

export const categoryExamples: Record<HazardCategory, string> = {
  SWALLOW: '구슬 · 동전 · 배터리',
  LIVING: '전선 · 콘센트',
}

const alertSubjects: Record<HazardCategory, string> = {
  SWALLOW: '영유아 삼킴 위험 물체',
  LIVING: '영유아 생활 공간 위험 요소',
}

export const riskLabels: Record<RiskLevel, string> = {
  VERY_HIGH: '매우 높음',
  HIGH: '높음',
  MEDIUM: '보통',
}

// 성장 단계별 위험도 기준: 삼킴 위험물은 나이가 들수록 낮아지고 생활공간 위험요소는 활동 반경이 넓어질수록 높아진다.
export const riskByStage: Record<Stage, Record<HazardCategory, RiskLevel>> = {
  INFANT: { SWALLOW: 'VERY_HIGH', LIVING: 'HIGH' },
  TODDLER: { SWALLOW: 'HIGH', LIVING: 'HIGH' },
  ACTIVE_CHILD: { SWALLOW: 'MEDIUM', LIVING: 'VERY_HIGH' },
}

export const riskStyles: Record<RiskLevel, { chip: string; dot: string; text: string; border: string; soft: string }> = {
  VERY_HIGH: { chip: 'bg-[#ffe4e6] text-[#be123c]', dot: 'bg-[#e11d48]', text: 'text-[#be123c]', border: 'border-[#fecdd3]', soft: 'bg-[#fff1f2]' },
  HIGH: { chip: 'bg-[#ffedd5] text-[#c2410c]', dot: 'bg-[#ea580c]', text: 'text-[#c2410c]', border: 'border-[#fed7aa]', soft: 'bg-[#fff7ed]' },
  MEDIUM: { chip: 'bg-[#fef9c3] text-[#a16207]', dot: 'bg-[#eab308]', text: 'text-[#a16207]', border: 'border-[#fde68a]', soft: 'bg-[#fefce8]' },
}

// 삼킴 위험물: 구슬·동전·배터리 / 생활공간 위험요소: 전선·콘센트
export const swallowKeywords = ['구슬', '동전', '배터리']
export const livingKeywords = ['전선', '콘센트']

export function classifyHazard(objectName: string): HazardCategory | null {
  const name = objectName.toLowerCase()
  if (livingKeywords.some((keyword) => name.includes(keyword))) return 'LIVING'
  if (swallowKeywords.some((keyword) => name.includes(keyword))) return 'SWALLOW'
  return null
}

function serverRisk(riskLevel: string): RiskLevel | null {
  if (riskLevel === 'VERY_HIGH' || riskLevel === 'HIGH' || riskLevel === 'MEDIUM') return riskLevel
  if (riskLevel === 'LOW') return 'MEDIUM'
  return null
}

export interface HazardAlert {
  category: HazardCategory | null
  risk: RiskLevel | null
  urgent: boolean
  urgencyLabel: '긴급' | '주의'
  title: string
}

// 분류가 가능하면 성장 단계 기준으로 위험도를 정하고, 분류할 수 없는 물체는 서버가 준 위험도를 그대로 쓴다.
export function describeHazard(hazard: { objectName: string; riskLevel: string }, stage: Stage | null): HazardAlert {
  const category = classifyHazard(hazard.objectName)
  const risk = category && stage ? riskByStage[stage][category] : serverRisk(hazard.riskLevel)
  const urgent = risk !== 'MEDIUM'
  return {
    category,
    risk,
    urgent,
    urgencyLabel: urgent ? '긴급' : '주의',
    title: `${category ? alertSubjects[category] : '위험 물체'}(${hazard.objectName}) 발견`,
  }
}

// 은/는 조사: 한글 받침이 있으면 "은", 없거나 한글이 아니면 "는"
export function withTopicParticle(name: string): string {
  const last = name.trim().slice(-1)
  const code = last.charCodeAt(0)
  const hasBatchim = code >= 0xac00 && code <= 0xd7a3 && (code - 0xac00) % 28 !== 0
  return `${name}${hasBatchim ? '은' : '는'}`
}
