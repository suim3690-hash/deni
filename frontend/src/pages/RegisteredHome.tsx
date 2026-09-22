import { useEffect, useRef, useState } from 'react'
import { Activity, ArrowRight, BatteryFull, Loader2, Power, Smile, X } from 'lucide-react'
import Header from '../components/Header'
import HazardLocation from './HazardLocation'
import SafetyProfileDetail from './SafetyProfileDetail'
import GrowthReport from './GrowthReport'
import homeIcon from '../assets/figma/home/imgButtonNavItemActive.svg'
import deviceIcon from '../assets/figma/home/imgIcon.svg'
import careIcon from '../assets/figma/home/imgIcon1.svg'
import menuIcon from '../assets/figma/home/imgIcon2.svg'
import robotIcon from '../assets/figma/home/imgVector5.svg'
import robotDot from '../assets/figma/home/imgVector6.svg'
import reportIcon from '../assets/figma/home/imgContainer1.svg'
import type { RegisteredChild } from '../services/children'
import { ApiRequestError, apiErrorMessage } from '../services/apiError'
import { getDashboard, getHazardDetail, sendDeviceCommand, type DashboardHazard, type DashboardSnapshot, type HazardDetail, type RobotState } from '../services/dashboard'
import { stageBannerSubtitles, stageTitles } from '../lib/stages'
import { describeHazard, riskLabels } from '../lib/hazardRisk'
import HazardAlertBox from '../components/HazardAlertBox'

type Modal = 'device' | null

const operationLabels: Record<RobotState['operationState'], string | null> = {
  RUNNING: '작동 중',
  PAUSED: '일시 정지',
  RELOCATING: '이송 중',
  UNKNOWN: null,
}

const movementLabels: Record<RobotState['movementState'], string | null> = {
  FORWARD: '전진',
  TURNING: '회전',
  BACKWARD: '후진',
  STOPPED: '정지',
  UNKNOWN: null,
}

// 기기가 보고한 작업 단계. 전원이 켜진 뒤 로봇이 무엇을 하고 있는지 알려준다.
const taskLabels: Record<string, string> = {
  OFF: '전원 꺼짐',
  RUNNING: '자동 주행 중',
  PAUSED: '일시 정지',
  HAZARD_PAUSED: '위험물 앞에서 정지',
  RECHECKING: '제거 여부 재확인 중',
  PUSHING: '위험물 이송 중',
  BACKING: '이송 후 후진 중',
  TURNING_AROUND: '이송 후 회전 중',
}

function robotStatusText(state: RobotState): string | null {
  const movement = state.operationState === 'PAUSED' ? null : movementLabels[state.movementState]
  const parts = [operationLabels[state.operationState], movement].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : null
}

function PowerButton({ onClick, state, label }: { onClick: () => void, state: 'off' | 'connecting' | 'on', label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={state === 'connecting'}
      aria-label={label}
      title={label}
      className="relative grid size-[46px] shrink-0 place-items-center rounded-full bg-gradient-to-b from-white to-[#d6d6da] p-[3px] shadow-[0_3px_6px_rgba(15,23,42,0.22)] transition-transform active:scale-95 disabled:cursor-default disabled:active:scale-100"
    >
      <span className="grid size-full place-items-center rounded-full bg-[radial-gradient(circle_at_35%_28%,#ffffff_0%,#f4f4f5_45%,#dcdce0_100%)] shadow-[inset_0_1px_1px_rgba(255,255,255,0.95),inset_0_-2px_3px_rgba(15,23,42,0.12)]">
        {state === 'connecting'
          ? <Loader2 size={19} className="animate-spin text-[#64748b]" aria-hidden="true" />
          : <Power size={19} className={state === 'on' ? 'text-[#10b981]' : 'text-[#e11d48]'} strokeWidth={2.4} aria-hidden="true" />}
      </span>
      {state === 'on' && <span className="absolute -right-0.5 -top-0.5 size-3 rounded-full border-2 border-white bg-[#10b981]" />}
    </button>
  )
}

interface Props {
  child: RegisteredChild
  onUpdateChild: (child: RegisteredChild) => void
  onChildUnavailable: (message: string) => void
}

export default function RegisteredHome({ child, onUpdateChild, onChildUnavailable }: Props) {
  const [dashboard, setDashboard] = useState<DashboardSnapshot | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [loadErrorMessage, setLoadErrorMessage] = useState('')
  const [lastResponseAt, setLastResponseAt] = useState<Date | null>(null)
  const [isRefreshing, setIsRefreshing] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [modal, setModal] = useState<Modal>(null)
  const [connecting, setConnecting] = useState(false)
  const [connectError, setConnectError] = useState('')
  const [mockPowered, setMockPowered] = useState(false)
  const [hazardDetail, setHazardDetail] = useState<HazardDetail | null>(null)
  const [hazardError, setHazardError] = useState('')
  const [hazardErrorStatus, setHazardErrorStatus] = useState<number | null>(null)
  const [selectedHazard, setSelectedHazard] = useState<DashboardHazard | null>(null)
  const [showMap, setShowMap] = useState(false)
  const [showSafetyProfile, setShowSafetyProfile] = useState(false)
  const [showReport, setShowReport] = useState(false)
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    let current = true
    let inFlight = false
    const refresh = async () => {
      if (!current || inFlight || document.visibilityState === 'hidden') return
      inFlight = true
      setIsRefreshing(true)
      try {
        const result = await getDashboard(child)
        if (current) {
          setDashboard(result)
          setLastResponseAt(new Date())
          setLoadError(false)
          setLoadErrorMessage('')
          if (!result.isMock && result.child.childId === child.childId && result.child.name.trim() && result.child.name !== child.name) {
            onUpdateChild({ ...child, name: result.child.name })
          }
        }
      } catch (error) {
        if (current && error instanceof ApiRequestError && error.status === 404) {
          onChildUnavailable(`${apiErrorMessage(error, '저장된 아이 정보를 찾을 수 없어요.')} 아이 정보를 다시 등록해 주세요.`)
        } else if (current) {
          setLoadError(true)
          setLoadErrorMessage(apiErrorMessage(error, '홈 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'))
        }
      } finally {
        inFlight = false
        if (current) setIsRefreshing(false)
      }
    }

    void refresh()
    // The backend updates activeHazards after a device detection. Refresh the
    // dashboard while this screen is open so a newly active hazard appears.
    const pollTimer = import.meta.env.VITE_API_BASE_URL ? window.setInterval(() => void refresh(), 5000) : null
    const refreshWhenVisible = () => { if (document.visibilityState === 'visible') void refresh() }
    document.addEventListener('visibilitychange', refreshWhenVisible)
    window.addEventListener('focus', refreshWhenVisible)
    return () => {
      current = false
      if (pollTimer !== null) window.clearInterval(pollTimer)
      document.removeEventListener('visibilitychange', refreshWhenVisible)
      window.removeEventListener('focus', refreshWhenVisible)
    }
  }, [child, onChildUnavailable, onUpdateChild, refreshKey])

  // 실제 모드에서 지도 화면이 열려 있는 동안 위험 상세를 다시 조회해, 서버 좌표가 바뀌면 지도의 마커가 따라 움직이게 한다.
  useEffect(() => {
    if (!showMap || !selectedHazard || dashboard?.isMock !== false) return
    const hazard = selectedHazard
    let active = true
    const timer = window.setInterval(() => {
      getHazardDetail(hazard, false)
        .then((next) => { if (active) setHazardDetail(next) })
        .catch(() => {
          // 조회에 실패하면 마지막으로 받은 상세를 그대로 유지한다.
        })
    }, 5000)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [showMap, selectedHazard, dashboard?.isMock])

  useEffect(() => {
    if (!modal) return
    closeButtonRef.current?.focus()
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setModal(null) }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [modal])

  const device = dashboard?.device
  const isOnline = !loadError && device?.connectionState === 'ONLINE'
  // 목업은 전원 버튼을 눌러야 연결된 상태가 되고, 실제 API는 서버가 보고한 연결 상태를 그대로 따른다.
  const connected = dashboard?.isMock ? mockPowered && isOnline : isOnline
  const profile = dashboard?.currentProfile ?? child.safetyProfile
  const isSupported = profile.status === 'APPLIED' && profile.stage !== null
  const stage = isSupported ? profile.stage : null
  const report = dashboard?.reportSummary
  const reportAvailable = Boolean(report?.available)
  const exampleReportAvailable = Boolean(dashboard?.isMock && report?.available)
  const activeHazard = dashboard?.activeHazards[0]
  const activeHazardCount = dashboard?.activeHazards.length ?? 0
  const alert = activeHazard ? describeHazard(activeHazard, stage, !dashboard?.isMock) : null
  const robotState = loadError ? null : dashboard?.robotState
  // 오래된 보고(stale)는 현재 상태의 근거가 아니므로 전원·작업 표시에 쓰지 않는다.
  const liveRobotState = robotState && !robotState.stale ? robotState : null
  const robotStatus = connected && liveRobotState ? robotStatusText(liveRobotState) : null
  // 전원은 통신 연결과 별개다. 전원을 끄면 모터와 탐지만 멈추고 통신은 유지된다.
  const powered = dashboard?.isMock ? mockPowered && isOnline : liveRobotState?.powerEnabled === true
  const powerReported = Boolean(dashboard?.isMock) || liveRobotState?.powerEnabled != null
  const taskLabel = connected && typeof liveRobotState?.taskState === 'string' ? taskLabels[liveRobotState.taskState] ?? null : null
  const operationState = dashboard?.isMock ? 'RUNNING' : robotState && !robotState.stale ? robotState.operationState : device?.operationState ?? 'UNKNOWN'
  const displayName = dashboard?.child.childId === child.childId && dashboard.child.name.trim() ? dashboard.child.name : child.name
  const lastResponseTime = lastResponseAt?.toLocaleTimeString('ko-KR', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })

  async function openHazardDetail(hazard: DashboardHazard) {
    if (!dashboard) return
    setSelectedHazard(hazard)
    setShowMap(true)
    setHazardDetail(null)
    setHazardError('')
    setHazardErrorStatus(null)
    setModal(null)
    try {
      setHazardDetail(await getHazardDetail(hazard, dashboard.isMock))
    } catch (error) {
      const status = error instanceof ApiRequestError ? error.status : null
      setHazardErrorStatus(status)
      setHazardError(apiErrorMessage(error, '위험 상세 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'))
    }
  }

  function openMap() {
    if (activeHazard) {
      void openHazardDetail(activeHazard)
      return
    }
    setSelectedHazard(null)
    setHazardDetail(null)
    setHazardError('')
    setHazardErrorStatus(null)
    setShowMap(true)
  }

  function closeMap() {
    setShowMap(false)
    setSelectedHazard(null)
  }

  async function refreshDashboard() {
    const result = await getDashboard(child)
    setDashboard(result)
    setLastResponseAt(new Date())
    setLoadError(false)
    setLoadErrorMessage('')
    return result
  }

  // 목업은 전원 버튼 하나로 ThinQ 연결까지 함께 보여주고, 실제 모드는 연결 확인과 전원 명령을 나눠서 보낸다.
  async function handleMockPower() {
    if (powered) {
      setMockPowered(false)
      return
    }
    setConnecting(true)
    try {
      await new Promise((resolve) => setTimeout(resolve, 1200))
      if (dashboard?.device?.connectionState === 'ONLINE') setMockPowered(true)
      else setConnectError('ThinQ에 연결하지 못했어요. 로봇청소기 전원과 네트워크를 확인해 주세요.')
    } finally {
      setConnecting(false)
    }
  }

  async function handlePower() {
    if (connecting || !dashboard) return
    setConnectError('')
    if (dashboard.isMock) {
      await handleMockPower()
      return
    }
    setConnecting(true)
    try {
      // 연결이 확인되지 않은 상태에서는 전원 명령 대신 최신 연결 상태부터 다시 조회한다.
      if (!isOnline) {
        const result = await refreshDashboard()
        if (!result.device) setConnectError('등록된 로봇청소기를 찾을 수 없어요.')
        else if (result.device.connectionState !== 'ONLINE') setConnectError('ThinQ에 연결하지 못했어요. 로봇청소기 전원과 네트워크를 확인해 주세요.')
        return
      }
      if (!device) {
        setConnectError('등록된 로봇청소기를 찾을 수 없어요.')
        return
      }
      // 기기가 보고한 전원 상태 없이는 켜기/끄기를 고를 수 없다. PC 런타임이 꺼져 있으면 보고가 오지 않는다.
      let current: boolean | null = powerReported ? powered : null
      if (current === null) {
        const state = (await refreshDashboard()).robotState
        if (!state || state.stale || state.powerEnabled == null) {
          setConnectError('기기가 보고한 전원 상태를 아직 받지 못했어요. PC 런타임이 실행 중인지 확인해 주세요.')
          return
        }
        current = state.powerEnabled
      }
      await sendDeviceCommand(device.deviceId, current ? 'power-off' : 'power-on', false)
      // 기기가 보고한 전원 상태를 다시 읽어 화면과 실제 상태를 맞춘다.
      await refreshDashboard()
    } catch (error) {
      setConnectError(apiErrorMessage(error, powered
        ? '전원을 끄지 못했어요. 잠시 후 다시 시도해 주세요.'
        : '전원을 켜지 못했어요. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setConnecting(false)
    }
  }

  function handleProfileChildUpdate(updated: RegisteredChild) {
    setDashboard((current) => current?.child.childId === updated.childId
      ? {
        ...current,
        child: { ...current.child, name: updated.name },
        currentProfile: {
          status: updated.safetyProfile.status,
          stage: updated.safetyProfile.stage,
          ageMonths: updated.safetyProfile.ageMonths,
        },
      }
      : current)
    onUpdateChild(updated)
  }

  if (showMap) return <HazardLocation hazard={selectedHazard} hazards={dashboard?.activeHazards ?? []} deviceId={device?.deviceId ?? ''} stage={stage} operationState={operationState} detail={hazardDetail} error={hazardError} errorStatus={hazardErrorStatus} isMock={dashboard?.isMock ?? false} onBack={closeMap} onRetry={() => { if (selectedHazard) void openHazardDetail(selectedHazard) }} />
  if (showSafetyProfile) return <SafetyProfileDetail child={child} onBack={() => setShowSafetyProfile(false)} onUpdateChild={handleProfileChildUpdate} isMock={dashboard?.isMock ?? !import.meta.env.VITE_API_BASE_URL} />
  if (showReport && report && reportAvailable) return <GrowthReport child={child} month={report.month} onBack={() => setShowReport(false)} />

  return (
    <div className="min-h-screen bg-[#f0f5fd] text-[#1e293b] [zoom:clamp(0.85,calc(100vw/402px),1.4)]">
      <div className="mx-auto min-h-screen max-w-[402px] pb-[85px]">
        <Header title={`${displayName} 홈`} hasNotification />
        <main className="px-6 pt-[10px]">
          {activeHazard && alert && (
            <div className="mb-3">
              <HazardAlertBox
                onClick={() => void openHazardDetail(activeHazard)}
                ariaLabel={`${alert.urgencyLabel} ${activeHazardCount > 1 ? `위험 물체 ${activeHazardCount}건이 감지되었어요` : alert.title}. 스마트 안심 케어 맵으로 이동`}
                badge={alert.urgencyLabel}
                urgent={alert.urgent}
                riskLabel={alert.risk ? riskLabels[alert.risk] : null}
                title={activeHazardCount > 1 ? `위험 물체 ${activeHazardCount}건이 감지되었어요` : alert.title}
                subtitle={loadError
                  ? '최신 조회 실패 · 마지막으로 확인된 알림이에요'
                  : activeHazardCount > 1
                    ? `대표 감지: ${activeHazard.objectName} · 눌러서 위치 확인`
                    : '눌러서 스마트 안심 케어 맵 확인'}
              />
            </div>
          )}
          <div className="mb-3 rounded-xl border border-[#e2e8f0] bg-white px-3 py-2 text-[11px] text-[#475569]">
            <div className="flex items-center justify-between gap-2">
              <span className={loadError ? 'text-[#a50034]' : ''}>
                {dashboard?.isMock ? '화면 예시 · 서버 데이터 아님' : loadError ? '최신 조회 실패' : isRefreshing ? '홈 데이터 확인 중' : lastResponseAt ? '서버 조회 완료' : '홈 데이터 불러오는 중'}
                {lastResponseAt && !dashboard?.isMock && <time dateTime={lastResponseAt.toISOString()} className="ml-1">· 마지막 응답 {lastResponseTime}</time>}
              </span>
              {import.meta.env.VITE_API_BASE_URL && <button type="button" onClick={() => setRefreshKey((value) => value + 1)} disabled={isRefreshing} className="shrink-0 font-semibold text-[#a50034] disabled:opacity-50">다시 조회</button>}
            </div>
            {loadError && <p role="alert" className="mt-1 text-[#a50034]">{loadErrorMessage}</p>}
          </div>
          <div className="mb-2 flex items-center justify-between gap-2">
            <h1 className="min-w-0 text-[18px] font-semibold">즐겨 찾는 제품</h1>
            <button type="button" onClick={() => setModal('device')} className="shrink-0 text-[12px] text-[#475569] hover:underline focus-visible:outline-[#a50034]">전체보기</button>
          </div>

          <section aria-label="LG RONi 로봇청소기" className="rounded-[20px] border border-[#e8edf5] bg-white p-4 shadow-sm">
            <div className="flex items-center justify-between gap-2">
              <div className="flex min-w-0 items-center gap-2.5">
                <div className="relative flex size-[42px] shrink-0 items-center justify-center rounded-[14px] bg-[#f0f5fd]">
                  <img src={robotIcon} alt="" className="h-[20px] w-[26px]" />
                  <img src={robotDot} alt="" className="absolute right-[5px] top-[4px] size-2" />
                </div>
                <div className="min-w-0">
                  <h2 className="text-[16px] font-bold text-black">LG RONi</h2>
                  <span className="mt-1 inline-flex rounded-full bg-[#d1feee] px-[7px] py-[1px] text-[10px] text-[#166b58]">로봇청소기</span>
                </div>
              </div>
              <PowerButton
                onClick={() => void handlePower()}
                state={connecting ? 'connecting' : powered ? 'on' : 'off'}
                label={connecting ? '전원 명령을 보내는 중' : powered ? '전원 켜짐 · 눌러서 전원 끄기' : connected ? '전원 켜기 · 자동 주행과 위험물 탐지 시작' : '전원 켜고 ThinQ 연결'}
              />
            </div>

            <div className="mt-3 border-t border-[#e8edf5] pt-3">
              {connected ? (
                <div className="flex flex-wrap items-center gap-2">
                  <span className="inline-flex items-center gap-1 rounded-full bg-[#f5f8ff] px-3 py-1.5 text-[12px] font-medium text-[#334155]"><BatteryFull size={15} className="text-[#10b981]" aria-hidden="true" />배터리 {device?.batteryPercent != null ? `${device.batteryPercent}%` : '확인 전'}</span>
                  <span className="rounded-full bg-[#e1fff2] px-3 py-1.5 text-[12px] font-medium text-[#167359]">⊙ 드니 모드 ON</span>
                  <span role="status" className={`rounded-full px-3 py-1.5 text-[12px] font-medium ${powered ? 'bg-[#e1fff2] text-[#167359]' : 'bg-[#fef2f2] text-[#a50034]'}`}>
                    {powered ? '전원 ON' : powerReported ? '전원 OFF · 통신 유지' : '전원 상태 확인 전'}
                  </span>
                  {taskLabel && <span role="status" className="rounded-full bg-[#f5f8ff] px-3 py-1.5 text-[12px] font-medium text-[#334155]">{taskLabel}</span>}
                  {robotStatus && <span role="status" className="inline-flex items-center gap-1 rounded-full bg-[#f5f8ff] px-3 py-1.5 text-[12px] font-medium text-[#334155]"><Activity size={15} className="text-[#2958c7]" aria-hidden="true" />{robotStatus}</span>}
                </div>
              ) : (
                <p className="text-[12px] leading-[1.5] text-[#64748b]">{connecting ? 'ThinQ에 연결하고 있어요…' : '전원 버튼을 누르면 ThinQ에 연결하고 로봇청소기를 가동해요.'}</p>
              )}
              {connectError && <p role="alert" className="mt-2 text-[12px] text-[#a50034]">{connectError}</p>}
              <div className="mt-3 flex justify-center">
                <button
                  type="button"
                  onClick={openMap}
                  disabled={!connected}
                  title={!connected ? '로봇청소기를 연결하면 사용할 수 있어요' : undefined}
                  className={`flex min-h-[38px] min-w-[205px] items-center justify-center rounded-full px-5 py-1.5 text-[14px] font-semibold text-white transition-colors focus-visible:outline-[#a50034] disabled:cursor-not-allowed disabled:opacity-45 ${activeHazard ? 'bg-[#b9003d]' : 'bg-[#167359]'}`}
                >
                  스마트 안심 케어 맵 <ArrowRight size={15} className="ml-1" />
                </button>
              </div>
            </div>
          </section>

          <section aria-label="아이 안전 프로필" className="mt-[18px] min-h-[185px] rounded-[24px] bg-gradient-to-r from-[#d9064d] via-[#ee4f7e] to-[#fa80a5] p-5 text-white shadow-[0_6px_15px_rgba(174,0,57,0.14)]">
            <div className="flex items-start justify-between gap-2">
              <span className="min-w-0 rounded-full bg-white/20 px-[10px] py-[5px] text-[11px] font-medium">✦ {dashboard?.isMock && isSupported ? '현재 Safety Profile 자동 적용 중' : isSupported ? 'Safety Profile 등록 완료' : '지원 범위 밖'}</span>
              <span className="grid size-[44px] shrink-0 place-items-center rounded-[14px] bg-white/20"><Smile size={22} aria-hidden="true" /></span>
            </div>
            <h2 className="-mt-1 text-[21px] font-bold leading-[1.2]">
              {isSupported && profile.stage ? stageTitles[profile.stage] : '현재 지원하는 연령이 아니에요'}
            </h2>
            <p className="mt-1 text-[12px] leading-[1.4] text-white/95">
              {isSupported && profile.stage ? dashboard?.isMock ? stageBannerSubtitles[profile.stage] : '성장 단계별 안전점검 기준을 서버에서 확인했어요. 기기 적용은 연동 전입니다.' : '안전 프로필이 적용되지 않았어요.'}
            </p>
            <div className="mt-3 flex justify-end border-t border-white/25 pt-2">
              <button type="button" onClick={() => setShowSafetyProfile(true)} className="text-[11px] underline underline-offset-2 focus-visible:outline-white">상세 보기 &gt;</button>
            </div>
          </section>

          <section aria-label="월간 안전 리포트" className="mt-[24px] min-h-[190px] rounded-[24px] border border-[#e8edf5] bg-white p-5 shadow-sm">
            <div className="flex items-center justify-between gap-2">
              <span className="rounded-full border border-[#fee2e2] bg-[#fef2f2] px-[10px] py-[3px] text-[11px] text-[#a50034]">{reportAvailable && report ? `${exampleReportAvailable ? '화면 예시 · ' : ''}${Number(report.month.slice(5))}월 리포트 조회 가능` : '리포트 준비 중'}</span>
              <span className="text-[10px] text-[#94a3b8]">{exampleReportAvailable ? '화면 확인용 예시' : reportAvailable ? 'DB 기록 집계' : '데이터 연동 준비 중'}</span>
            </div>
            <div className="mt-3 flex items-center gap-2">
              <img src={reportIcon} alt="" className="size-[15px]" />
              <h2 className="text-[18px] font-semibold">우리 아이 맞춤 안전 리포트</h2>
            </div>
            <p className="mt-1 text-[12px] leading-[1.6] text-[#475569]">
              {reportAvailable ? exampleReportAvailable && report ? `${displayName} 아동의 ${Number(report.month.slice(5))}월 화면 확인용 예시 리포트를 확인해 보세요.` : '저장된 위험 탐지 기록을 월별로 확인해 보세요. 기록이 없는 월도 조회할 수 있어요.' : '리포트 조회 기능을 준비하고 있어요.'}
            </p>
            <div className="mt-3 border-t border-[#f1f5f9] pt-2 text-center">
              <button type="button" onClick={() => setShowReport(true)} disabled={!reportAvailable} className="inline-flex min-h-[43px] min-w-[205px] items-center justify-center px-5 py-1.5 rounded-full bg-[#b9003d] text-[15px] font-semibold text-white disabled:cursor-not-allowed disabled:opacity-45">
                리포트 보러가기 <ArrowRight size={16} className="ml-1" />
              </button>
            </div>
          </section>
          {dashboard?.isMock && <p className="mt-3 text-center text-[11px] text-[#94a3b8]">현재 기기·위험·리포트 정보는 화면 확인용 예시입니다.</p>}
        </main>

        <nav aria-label="하단 메뉴" className="fixed bottom-0 left-1/2 z-10 flex h-[78px] w-full max-w-[402px] -translate-x-1/2 items-center justify-between border-t border-[#e2e8f0] bg-white/95 px-6 pb-2 backdrop-blur-md">
          <button type="button" aria-current="page" className="flex w-14 justify-center"><img src={homeIcon} alt="홈" className="h-[31px] w-14" /></button>
          <button type="button" disabled className="flex w-14 cursor-not-allowed flex-col items-center gap-1 text-[11px] text-[#94a3b8]"><img src={deviceIcon} alt="" className="h-[16px] w-[20px]" />디바이스</button>
          <button type="button" disabled className="flex w-14 cursor-not-allowed flex-col items-center gap-1 text-[11px] text-[#94a3b8]"><img src={careIcon} alt="" className="size-[17px]" />케어</button>
          <button type="button" disabled className="flex w-14 cursor-not-allowed flex-col items-center gap-1 text-[11px] text-[#94a3b8]"><img src={menuIcon} alt="" className="h-[12px] w-[18px]" />메뉴</button>
        </nav>
      </div>

      {modal && (
        <div className="fixed inset-0 z-20 flex items-end justify-center bg-[#0f172a]/40 p-4 sm:items-center" onMouseDown={(event) => { if (event.target === event.currentTarget) setModal(null) }}>
          <section role="dialog" aria-modal="true" aria-labelledby="home-dialog-title" className="w-full max-w-[370px] rounded-[20px] bg-white p-5 shadow-xl">
            <div className="flex items-start justify-between">
              <h2 id="home-dialog-title" className="text-[18px] font-semibold">기기 연결 상태</h2>
              <button ref={closeButtonRef} type="button" onClick={() => setModal(null)} aria-label="닫기" className="rounded-full p-1 text-[#475569] focus-visible:outline-[#a50034]"><X size={20} /></button>
            </div>
            <p className="mt-4 text-[14px] leading-6 text-[#475569]">
              {!connected
                ? '전원 버튼을 누르면 LG RONi를 ThinQ에 연결하고 가동해요.'
                : powered
                  ? 'LG RONi가 ThinQ에 연결되어 있고, 자동 주행과 위험물 탐지가 켜져 있어요.'
                  : '연결은 유지되고 있지만 전원이 꺼져 있어요. 전원을 켜면 자동 주행과 위험물 탐지를 시작해요.'}
            </p>
          </section>
        </div>
      )}
    </div>
  )
}
