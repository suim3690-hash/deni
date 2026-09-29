import type { RobotState } from '../services/dashboard'

export function treatmentReadinessMessage(state: RobotState | null | undefined): string {
  if (!state || state.stale || state.powerEnabled == null) return '로봇의 최신 상태를 확인하고 있어요. 연결 상태를 확인해 주세요.'
  if (!state.powerEnabled) return '로봇 전원이 꺼져 있어요. 홈에서 전원을 켜 주세요.'
  if (state.taskState === 'RECHECKING') return '로봇이 이미 제거 여부를 확인 중이에요. 결과를 기다려 주세요.'
  if (state.operationState === 'PAUSED' && state.movementState === 'STOPPED'
    && (state.taskState === 'HAZARD_PAUSED' || state.taskState === 'PAUSED')) return ''
  return '로봇의 정지 확인을 기다리고 있어요. 탐지 준비 중이거나 주행 중에는 요청할 수 없어요. 필요하면 홈에서 일시정지해 주세요.'
}
