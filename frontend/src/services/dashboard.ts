import type { RegisteredChild } from './children'
import { generateId } from '../lib/id'
import { classifyHazard } from '../lib/hazardRisk'
import { apiErrorFromResponse } from './apiError'

export type ConnectionState = 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
export type OperationState = 'RUNNING' | 'PAUSED' | 'STOPPING' | 'RESUMING' | 'READY_TO_RESUME' | 'UNKNOWN'

export interface DashboardDevice {
  deviceId: string
  name?: string
  commandsAvailable?: boolean
  connectionState: ConnectionState
  operationState: OperationState
  batteryPercent: number | null
  lastSeenAt: string | null
  safetyModeEnabled?: boolean | null
}

export interface DashboardHazard {
  hazardId: string
  objectName: string
  riskLevel: string
  detectedAt: string
}

export interface DashboardProfile {
  status: RegisteredChild['safetyProfile']['status']
  stage: RegisteredChild['safetyProfile']['stage']
  ageMonths: number
}

export interface HazardMarker {
  x: number
  y: number
}

export interface HazardDetail extends DashboardHazard {
  riskReason: string | null
  captureImageUrl: string | null
  marker: HazardMarker | null
}

// 서버 좌표는 지도 이미지 기준 0~1 비율이다. 범위를 벗어나거나 한쪽만 있으면 위치 없음으로 본다.
function parseMarker(marker: { x?: unknown; y?: unknown } | null | undefined): HazardMarker | null {
  const { x, y } = marker ?? {}
  if (typeof x !== 'number' || typeof y !== 'number') return null
  if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) return null
  return { x, y }
}

export interface RobotState {
  operationState: 'RUNNING' | 'PAUSED' | 'RELOCATING' | 'UNKNOWN'
  movementState: 'FORWARD' | 'TURNING' | 'BACKWARD' | 'STOPPED' | 'UNKNOWN'
  movementDurationMs: number | null
  movementDistanceM: number | null
  sampledAt: string | null
  receivedAt: string | null
  stale: boolean
}

export interface DashboardData {
  child: { childId: string; name: string }
  device: DashboardDevice | null
  robotState?: RobotState | null
  currentProfile: DashboardProfile
  activeHazards: DashboardHazard[]
  reportSummary: { reportId: string | null; month: string; available: boolean } | null
}

export interface DashboardSnapshot extends DashboardData {
  isMock: boolean
}

function mockDashboard(child: RegisteredChild): DashboardSnapshot {
  const previewState = new URLSearchParams(window.location.search).get('mockDevice')
  // 목업(5174)은 기본으로 위험물이 감지된 상태다.
  // mockHazard=구슬|동전|배터리(삼킴 위험물) · 전선|콘센트(생활공간 위험요소) · none(위험물 없음), 기본값은 동전
  const searchParams = new URLSearchParams(window.location.search)
  const hazardPreview = searchParams.get('mockHazard')
  const requestedHazardCount = Number(searchParams.get('mockHazardCount') ?? 1)
  const hazardCount = Number.isInteger(requestedHazardCount) ? Math.min(Math.max(requestedHazardCount, 1), 10) : 1
  const previewName = hazardPreview === 'living' ? '전선' : !hazardPreview || hazardPreview === 'swallow' ? '동전' : hazardPreview
  const previewHazard = hazardPreview === 'none'
    ? null
    : { hazardId: `preview-${previewName}`, objectName: previewName }
  const showHazard = previewHazard !== null
  const paused = showHazard || previewState === 'paused'
  const connectionState: ConnectionState = previewState === 'offline' ? 'OFFLINE' : previewState === 'unknown' ? 'UNKNOWN' : 'ONLINE'
  const today = new Date()
  const month = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`

  return {
    isMock: true,
    child: { childId: child.childId, name: child.name },
    currentProfile: child.safetyProfile,
    device: {
      deviceId: 'preview-device',
      name: 'LG 로니 AI 베이비 케어',
      commandsAvailable: true,
      connectionState,
      operationState: connectionState === 'ONLINE' ? paused ? 'PAUSED' : 'RUNNING' : 'UNKNOWN',
      batteryPercent: connectionState === 'ONLINE' ? 82 : null,
      lastSeenAt: null,
      safetyModeEnabled: connectionState === 'ONLINE',
    },
    robotState: connectionState === 'ONLINE' ? {
      operationState: paused ? 'PAUSED' : 'RUNNING',
      movementState: paused ? 'STOPPED' : 'FORWARD',
      movementDurationMs: null,
      movementDistanceM: null,
      sampledAt: today.toISOString(),
      receivedAt: today.toISOString(),
      stale: false,
    } : null,
    activeHazards: previewHazard && connectionState === 'ONLINE' ? Array.from({ length: hazardCount }, (_, index) => {
      const mockNames = hazardPreview === 'living'
        ? ['전선', '콘센트']
        : hazardPreview === 'mixed'
          ? ['동전', '전선', '배터리', '콘센트', '구슬']
          : hazardPreview === 'swallow' || !hazardPreview
            ? ['동전', '구슬', '배터리']
            : [previewName]
      const objectName = mockNames[index % mockNames.length]
      return {
        hazardId: `preview-${objectName}-${index + 1}`,
        objectName,
        riskLevel: 'HIGH',
        detectedAt: new Date(today.getTime() - index * 30_000).toISOString(),
      }
    }) : [],
    reportSummary: { reportId: `preview-${month}`, month, available: true },
  }
}

// 서버가 `/api/v1/...` 같은 상대 경로를 주면 API 서버 주소를 붙여 브라우저가 바로 읽을 수 있게 한다.
function resolveImageUrl(url: string | null | undefined, baseUrl: string): string | null {
  const value = url?.trim()
  if (!value) return null
  return value.startsWith('/') ? `${baseUrl}${value}` : value
}

export async function getHazardDetail(hazard: DashboardHazard, isMock: boolean): Promise<HazardDetail> {
  if (isMock) return {
    ...hazard,
    riskReason: classifyHazard(hazard.objectName) === 'LIVING'
      ? '아이가 만지거나 걸릴 수 있는 생활공간 위험 요소입니다. 아이가 접근하기 전에 확인해 주세요.'
      : '아이가 삼킬 수 있는 작은 물체입니다. 아이가 접근하기 전에 바닥에서 치워 주세요.',
    captureImageUrl: null,
    marker: { x: 0.296, y: 0.429 },
  }

  const baseUrl = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '')
  if (!baseUrl) throw new Error('API URL is missing')
  const response = await fetch(`${baseUrl}/api/v1/hazards/${encodeURIComponent(hazard.hazardId)}`)
  if (!response.ok) throw await apiErrorFromResponse(response, '위험 상세 정보를 불러오지 못했어요.')
  const data = await response.json() as {
    hazardId: string; object?: { name?: string }; riskLevel: string; riskReason?: string | null
    detectedAt: string
    captureImageUrl?: string | null
    location?: { marker?: { x?: unknown; y?: unknown } | null } | null
  }
  return {
    hazardId: data.hazardId,
    objectName: data.object?.name ?? hazard.objectName,
    riskLevel: data.riskLevel,
    detectedAt: data.detectedAt,
    riskReason: data.riskReason ?? null,
    captureImageUrl: resolveImageUrl(data.captureImageUrl, baseUrl),
    marker: parseMarker(data.location?.marker),
  }
}

export async function sendDeviceCommand(deviceId: string, action: 'pause' | 'resume', isMock: boolean): Promise<OperationState> {
  if (isMock) {
    await new Promise((resolve) => setTimeout(resolve, 600))
    return action === 'pause' ? 'PAUSED' : 'RUNNING'
  }

  const baseUrl = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '')
  if (!baseUrl) throw new Error('API URL is missing')
  const path = `${baseUrl}/api/v1/devices/${encodeURIComponent(deviceId)}/commands`
  const response = await fetch(`${path}/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': generateId() },
    body: '{}',
  })
  if (!response.ok) throw await apiErrorFromResponse(response, '기기 명령을 보내지 못했어요.')
  const { commandId, deliveryState } = await response.json() as { commandId: string; deliveryState?: string }
  if (deliveryState === 'NOT_CONNECTED') throw new Error('요청은 기록됐지만 기기 전달 기능은 아직 연결되지 않았어요.')

  for (let attempt = 0; attempt < 8; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 800))
    const result = await fetch(`${path}/${encodeURIComponent(commandId)}`)
    if (!result.ok) throw await apiErrorFromResponse(result, '기기 명령 상태를 확인하지 못했어요.')
    const command = await result.json() as { status: 'REQUESTED' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'; deviceOperationState?: OperationState }
    if (command.status === 'SUCCEEDED' && command.deviceOperationState) return command.deviceOperationState
    if (command.status === 'FAILED') throw new Error('기기에서 명령을 거부했어요.')
  }
  throw new Error('기기 명령 결과를 확인하는 데 시간이 오래 걸리고 있어요.')
}

export async function getDashboard(child: RegisteredChild): Promise<DashboardSnapshot> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  if (!baseUrl) return mockDashboard(child)

  const query = new URLSearchParams({ childId: child.childId })
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/dashboard?${query}`)
  if (!response.ok) throw await apiErrorFromResponse(response, '홈 정보를 불러오지 못했어요.')
  const data = await response.json() as DashboardData
  let robotState: DashboardData['robotState'] = null
  if (data.device) {
    const stateResponse = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/devices/${encodeURIComponent(data.device.deviceId)}/robot-state`)
    if (!stateResponse.ok) throw await apiErrorFromResponse(stateResponse, '로봇 동작 정보를 불러오지 못했어요.')
    robotState = await stateResponse.json() as NonNullable<DashboardData['robotState']>
  }
  return { ...data, robotState, isMock: false }
}
