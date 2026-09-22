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
// "direct removal" can be marked complete. The backend queues this for the device and
// only reports COMPLETED after the device confirms a continuous absence window, so the
// caller has to poll getSafetyAction instead of assuming the receipt means success.
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

// FR-026/028: relocation pushes a swallow hazard to the ArUco marker position, so the
// body stays empty and a safeZoneId is rejected. The backend refuses the request when
// relocation cannot start (RELOCATION_NOT_SUPPORTED, DEVICE_NOT_PAUSED,
// DEVICE_NOT_CONTROLLABLE, ACTION_IN_PROGRESS); the caller surfaces that as the FR-028
// "이동 불가 시 직접 제거 안내" alternate flow rather than treating it as a bug.
// Completion is only TEMPORARY_COMPLETED after the device reports it, via getSafetyAction.
export async function requestRelocation(hazardId: string): Promise<ActionReceipt> {
  const response = await fetch(`${apiBase()}/api/v1/hazards/${encodeURIComponent(hazardId)}/relocations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': generateId() },
    body: '{}',
  })
  if (!response.ok) throw await apiErrorFromResponse(response, '안전 위치 이동 요청을 접수하지 못했어요.')
  return response.json() as Promise<ActionReceipt>
}
