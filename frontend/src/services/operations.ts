import { generateId } from '../lib/id'
import { apiErrorFromResponse } from './apiError'

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
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  if (!baseUrl) throw new Error('API URL is missing')
  return baseUrl.replace(/\/$/, '')
}

// FR-025: request the device to reconfirm the hazard is gone before the guardian's
// "direct removal" can be marked complete. The backend only ever records this intent
// today (no device ingestion exists yet), so the result is always PENDING/UNKNOWN.
export async function requestRemovalCheck(hazardId: string): Promise<ActionReceipt> {
  const response = await fetch(`${apiBase()}/api/v1/hazards/${encodeURIComponent(hazardId)}/removal-checks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': generateId() },
    body: '{}',
  })
  if (!response.ok) throw await apiErrorFromResponse(response, '직접 제거 재확인 요청을 접수하지 못했어요.')
  return response.json() as Promise<ActionReceipt>
}

export async function getSafetyAction(actionId: string): Promise<ActionResult> {
  const response = await fetch(`${apiBase()}/api/v1/safety-actions/${encodeURIComponent(actionId)}`)
  if (!response.ok) throw await apiErrorFromResponse(response, '처리 접수 상태를 확인하지 못했어요.')
  return response.json() as Promise<ActionResult>
}

// FR-026/028: the relocation contract (allowed objects, destination, device delivery)
// isn't finalized server-side, so this call is expected to always come back blocked
// with RELOCATION_NOT_CONFIGURED today. The caller surfaces that as the FR-028
// "이동 불가 시 직접 제거 안내" alternate flow rather than treating it as a bug.
export async function requestRelocation(hazardId: string): Promise<ActionReceipt> {
  const response = await fetch(`${apiBase()}/api/v1/hazards/${encodeURIComponent(hazardId)}/relocations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': generateId() },
    body: '{}',
  })
  if (!response.ok) throw await apiErrorFromResponse(response, '안전 위치 이동 요청을 접수하지 못했어요.')
  return response.json() as Promise<ActionReceipt>
}
