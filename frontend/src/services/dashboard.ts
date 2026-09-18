import type { RegisteredChild } from './children'
import { generateId } from '../lib/id'

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
  airQualityLabel?: string | null
  purifierStateLabel?: string | null
}

export interface DashboardHazard {
  hazardId: string
  objectName: string
  riskLevel: string
  locationLabel: string
  detectedAt: string
}

export interface DashboardProfile {
  status: RegisteredChild['safetyProfile']['status']
  stage: RegisteredChild['safetyProfile']['stage']
  ageMonths: number
}

export interface HazardDetail extends DashboardHazard {
  riskReason: string | null
  captureImageUrl: string | null
  mapImageUrl: string | null
  marker: { x: number; y: number } | null
}

export class HazardDetailError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`Hazard detail request failed: ${status}`)
    this.status = status
  }
}

export class DashboardRequestError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`Dashboard request failed: ${status}`)
    this.status = status
  }
}

export interface DashboardData {
  child: { childId: string; name: string }
  device: DashboardDevice | null
  currentProfile: DashboardProfile
  activeHazards: DashboardHazard[]
  reportSummary: { reportId: string | null; month: string; available: boolean } | null
  obstacleCount?: number | null
  obstacleLabel?: string | null
}

export interface DashboardSnapshot extends DashboardData {
  isMock: boolean
}

function mockDashboard(child: RegisteredChild): DashboardSnapshot {
  const previewState = new URLSearchParams(window.location.search).get('mockDevice')
  // Explicit preview only. The normal mock home starts with no active hazard.
  const showHazard = new URLSearchParams(window.location.search).get('mockHazard') === 'lego'
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
      operationState: connectionState === 'ONLINE' ? showHazard || previewState === 'paused' ? 'PAUSED' : 'RUNNING' : 'UNKNOWN',
      batteryPercent: connectionState === 'ONLINE' ? 82 : null,
      lastSeenAt: null,
      safetyModeEnabled: connectionState === 'ONLINE',
      airQualityLabel: connectionState === 'ONLINE' ? '좋음' : null,
      purifierStateLabel: connectionState === 'ONLINE' ? '가동중' : null,
    },
    activeHazards: showHazard && connectionState === 'ONLINE' ? [{
      hazardId: 'preview-lego',
      objectName: '레고 브릭',
      riskLevel: 'VERY_HIGH',
      locationLabel: '거실 러그 위',
      detectedAt: new Date().toISOString(),
    }] : [],
    obstacleCount: 4,
    obstacleLabel: '소형 완구',
    reportSummary: { reportId: `preview-${month}`, month, available: true },
  }
}

export async function getHazardDetail(hazard: DashboardHazard, isMock: boolean): Promise<HazardDetail> {
  if (isMock) return {
    ...hazard,
    riskReason: '아이의 성장단계에서 삼킬 위험이 있는 작은 완구입니다.',
    captureImageUrl: null,
    mapImageUrl: null,
    marker: null,
  }

  const baseUrl = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '')
  if (!baseUrl) throw new Error('API URL is missing')
  const response = await fetch(`${baseUrl}/api/v1/hazards/${encodeURIComponent(hazard.hazardId)}`)
  if (!response.ok) throw new HazardDetailError(response.status)
  const data = await response.json() as {
    hazardId: string; object?: { name?: string }; riskLevel: string; riskReason?: string | null
    detectedAt: string; location?: { label?: string; mapImageUrl?: string | null; marker?: { x: number; y: number } | null }
    captureImageUrl?: string | null
  }
  return {
    hazardId: data.hazardId,
    objectName: data.object?.name ?? hazard.objectName,
    riskLevel: data.riskLevel,
    locationLabel: data.location?.label ?? hazard.locationLabel,
    detectedAt: data.detectedAt,
    riskReason: data.riskReason ?? null,
    captureImageUrl: data.captureImageUrl ?? null,
    mapImageUrl: data.location?.mapImageUrl ?? null,
    marker: data.location?.marker ?? null,
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
  if (!response.ok) throw new Error(`Device command failed: ${response.status}`)
  const { commandId, deliveryState } = await response.json() as { commandId: string; deliveryState?: string }
  if (deliveryState === 'NOT_CONNECTED') throw new Error('Command was recorded but device delivery is not connected')

  for (let attempt = 0; attempt < 8; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 800))
    const result = await fetch(`${path}/${encodeURIComponent(commandId)}`)
    if (!result.ok) throw new Error(`Device command status failed: ${result.status}`)
    const command = await result.json() as { status: 'REQUESTED' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'; deviceOperationState?: OperationState }
    if (command.status === 'SUCCEEDED' && command.deviceOperationState) return command.deviceOperationState
    if (command.status === 'FAILED') throw new Error('Device command was rejected')
  }
  throw new Error('Device command confirmation timed out')
}

export async function getDashboard(child: RegisteredChild): Promise<DashboardSnapshot> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  if (!baseUrl) return mockDashboard(child)

  const query = new URLSearchParams({ childId: child.childId })
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/dashboard?${query}`)
  if (!response.ok) throw new DashboardRequestError(response.status)
  const data = await response.json() as DashboardData
  return { ...data, isMock: false }
}
