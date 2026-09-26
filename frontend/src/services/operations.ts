import { generateId } from '../lib/id'
import { apiErrorFromResponse } from './apiError'
import { apiBaseUrl } from '../lib/runtime'

export interface ActionReceipt {
  actionId: string
  type: string
  status: string
  deliveryState: string
}

export interface ActionResult extends ActionReceipt {
  hazardId: string
  hazardPresent: boolean | null
  treatmentStatus: string
  deviceOperationState: string
  completedAt: string | null
  requestedAt: string
}

function apiBase() {
  const baseUrl = apiBaseUrl
  if (!baseUrl) throw new Error('API URL is missing')
  return baseUrl
}

async function requestHazardAction(
  hazardId: string,
  action: 'removal-checks' | 'relocations',
  errorMessage: string,
): Promise<ActionReceipt> {
  const response = await fetch(`${apiBase()}/api/v1/hazards/${encodeURIComponent(hazardId)}/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': generateId() },
    body: '{}',
  })
  if (!response.ok) throw await apiErrorFromResponse(response, errorMessage)
  return response.json() as Promise<ActionReceipt>
}

// 접수는 완료가 아니다. 기기의 연속 미검출 확인 후 getSafetyAction으로 완료를 조회한다.
export async function requestRemovalCheck(hazardId: string): Promise<ActionReceipt> {
  return requestHazardAction(hazardId, 'removal-checks', '직접 제거 재확인 요청을 접수하지 못했어요.')
}

export async function getSafetyAction(actionId: string): Promise<ActionResult> {
  const response = await fetch(`${apiBase()}/api/v1/safety-actions/${encodeURIComponent(actionId)}`)
  if (!response.ok) throw await apiErrorFromResponse(response, '처리 접수 상태를 확인하지 못했어요.')
  return response.json() as Promise<ActionResult>
}

// 마커 위치로 이송하므로 본문은 비운다. 거절 사유는 호출부에 전달하고 완료는 별도 조회한다.
export async function requestRelocation(hazardId: string): Promise<ActionReceipt> {
  return requestHazardAction(hazardId, 'relocations', '안전 위치 이동 요청을 접수하지 못했어요.')
}
