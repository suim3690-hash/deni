export type Stage = 'INFANT' | 'TODDLER' | 'ACTIVE_CHILD'

export const stageOrder: Record<Stage, number> = {
  INFANT: 1,
  TODDLER: 2,
  ACTIVE_CHILD: 3,
}

export const stageByOrder: Record<number, Stage> = {
  1: 'INFANT',
  2: 'TODDLER',
  3: 'ACTIVE_CHILD',
}

export const stageFocusLabels: Record<Stage, string> = {
  INFANT: '바닥 이물질 집중 모드',
  TODDLER: '모서리·전선 집중 모드',
  ACTIVE_CHILD: '집 전체 생활공간 점검',
}

export const stageLowerBoundMonths: Record<Stage, number> = {
  INFANT: 0,
  TODDLER: 12,
  ACTIVE_CHILD: 36,
}

export const stageAgeRangeLabels: Record<Stage, string> = {
  INFANT: '0~12개월',
  TODDLER: '12~36개월',
  ACTIVE_CHILD: '36~96개월',
}

export const stageLabels: Record<Stage, string> = {
  INFANT: '바닥 탐색 시기',
  TODDLER: '걸음마 시기',
  ACTIVE_CHILD: '유아 활동기',
}

export const stageTitles: Record<Stage, string> = {
  INFANT: '바닥을 탐색하는 시기예요',
  TODDLER: '두발로 집안을 탐험하는 시기에요',
  ACTIVE_CHILD: '활동 범위가 넓어지는 시기예요',
}

export const stageBannerSubtitles: Record<Stage, string> = {
  INFANT: '바닥 이물질 및 삼킴 위험물 집중 감지 모드',
  TODDLER: '모서리 충돌 방지 및 바닥 전선 걸림 집중 감지 모드',
  ACTIVE_CHILD: '활동 반경 확대에 따른 광범위 위험 탐지 모드',
}

export const stageCriteriaTitles: Record<Stage, string> = {
  INFANT: '바닥 이물질·삼킴 위험 탐지 강화',
  TODDLER: '모서리·문턱·전선 등 이동 위험 탐지 강화',
  ACTIVE_CHILD: '활동 반경 확대에 따른 광범위 위험 탐지',
}

export const stageCriteriaDescriptions: Record<Stage, string> = {
  INFANT: '바닥에 떨어진 작은 물체와 삼킴 위험 물건을 중심으로 집중 모니터링',
  TODDLER: '높이 30~80cm 가구 모서리, 바닥 문턱 단차, 콘센트 및 전선 걸림 집중 모니터링',
  ACTIVE_CHILD: '집 전체 활동 반경으로 감지 범위를 넓혀 낙상·충돌 위험까지 폭넓게 모니터링',
}
