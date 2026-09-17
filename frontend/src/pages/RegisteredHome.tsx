import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, ArrowRight, Smile, X } from 'lucide-react'
import Header from '../components/Header'
import HazardLocation from './HazardLocation'
import homeIcon from '../assets/figma/home/imgButtonNavItemActive.svg'
import deviceIcon from '../assets/figma/home/imgIcon.svg'
import careIcon from '../assets/figma/home/imgIcon1.svg'
import menuIcon from '../assets/figma/home/imgIcon2.svg'
import robotIcon from '../assets/figma/home/imgVector5.svg'
import robotDot from '../assets/figma/home/imgVector6.svg'
import reportIcon from '../assets/figma/home/imgContainer1.svg'
import powerButton from '../assets/figma/home/power-button.png'
import pauseButton from '../assets/figma/home/pause-button.png'
import resumeButton from '../assets/figma/home/resume-button.png'
import type { RegisteredChild } from '../services/children'
import { getDashboard, getHazardDetail, HazardDetailError, sendDeviceCommand, type DashboardHazard, type DashboardSnapshot, type HazardDetail } from '../services/dashboard'

const stageLabels = {
  INFANT: '바닥 탐색 시기',
  TODDLER: '걸음마 시기',
  ACTIVE_CHILD: '유아 활동기',
}

const stageTitles = {
  INFANT: '바닥을 탐색하는 시기예요',
  TODDLER: '두발로 집안을 탐험하는 시기에요',
  ACTIVE_CHILD: '활동 범위가 넓어지는 시기예요',
}

type Modal = 'device' | 'profile' | 'hazards' | 'avoidance' | 'report' | null

export default function RegisteredHome({ child }: { child: RegisteredChild }) {
  const [dashboard, setDashboard] = useState<DashboardSnapshot | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [modal, setModal] = useState<Modal>(null)
  const [commandPending, setCommandPending] = useState(false)
  const [commandError, setCommandError] = useState('')
  const [hazardDetail, setHazardDetail] = useState<HazardDetail | null>(null)
  const [hazardError, setHazardError] = useState('')
  const [hazardErrorStatus, setHazardErrorStatus] = useState<number | null>(null)
  const [selectedHazard, setSelectedHazard] = useState<DashboardHazard | null>(null)
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    let current = true
    let inFlight = false
    const refresh = async () => {
      if (!current || inFlight || document.visibilityState === 'hidden') return
      inFlight = true
      try {
        const result = await getDashboard(child)
        if (current) {
          setDashboard(result)
          setLoadError(false)
        }
      } catch {
        if (current) setLoadError(true)
      } finally {
        inFlight = false
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
  }, [child])

  useEffect(() => {
    if (!modal) return
    closeButtonRef.current?.focus()
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setModal(null) }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [modal])

  const device = dashboard?.device
  const connection = loadError ? 'UNKNOWN' : device?.connectionState ?? 'UNKNOWN'
  const isOnline = connection === 'ONLINE'
  const isOffline = connection === 'OFFLINE'
  const profile = child.safetyProfile
  const isSupported = profile.status === 'APPLIED' && profile.stage !== null
  const report = dashboard?.reportSummary
  const isPaused = device?.operationState === 'PAUSED'
  const activeHazard = dashboard?.activeHazards[0]

  async function openHazardDetail(hazard: DashboardHazard) {
    if (!dashboard) return
    setSelectedHazard(hazard)
    setHazardDetail(null)
    setHazardError('')
    setHazardErrorStatus(null)
    setModal(null)
    try {
      setHazardDetail(await getHazardDetail(hazard, dashboard.isMock))
    } catch (error) {
      const status = error instanceof HazardDetailError ? error.status : null
      setHazardErrorStatus(status)
      setHazardError(status === 403 ? '이 위험 정보를 볼 권한이 없어요.' : status === 404 ? '해당 위험 감지 건을 찾을 수 없어요.' : '위험 상세 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')
    }
  }

  async function handleDeviceCommand() {
    if (!device || !dashboard || !isOnline || commandPending) return
    const action = isPaused ? 'resume' : 'pause'
    if (action === 'resume' && dashboard.activeHazards.length > 0) {
      setCommandError('위험물 처리가 확인될 때까지 청소를 다시 시작할 수 없어요.')
      return
    }
    setCommandError('')
    setCommandPending(true)
    try {
      const operationState = await sendDeviceCommand(device.deviceId, action, dashboard.isMock)
      setDashboard((current) => current?.device?.deviceId === device.deviceId
        ? { ...current, device: { ...current.device, operationState } }
        : current)
    } catch {
      setCommandError(action === 'resume' ? '기기를 다시 시작하지 못했어요. 위험물 처리 상태와 연결을 확인해 주세요.' : '기기를 일시정지하지 못했어요. 연결 상태를 확인해 주세요.')
    } finally {
      setCommandPending(false)
    }
  }

  if (selectedHazard) return <HazardLocation hazard={selectedHazard} detail={hazardDetail} error={hazardError} errorStatus={hazardErrorStatus} isMock={dashboard?.isMock ?? false} onBack={() => setSelectedHazard(null)} onRetry={() => void openHazardDetail(selectedHazard)} />

  return (
    <div className="min-h-screen bg-[#f0f5fd] text-[#1e293b]">
      <div className="mx-auto min-h-screen max-w-[402px] pb-[85px]">
        <Header title="손지아 홈" hasNotification />
        <main className="px-6 pt-[10px]">
          {activeHazard && (
            <section aria-label="위험 물체 감지 알림" role="status" className="mb-3 rounded-[16px] border border-[#ffc5c5] bg-[#fff9f9] px-4 pb-4 pt-[17px] text-[#25252b]">
              <div className="flex items-start gap-3">
                <span className="grid size-10 shrink-0 place-items-center rounded-[12px] bg-[#ffe5e7] text-[#ba1729]"><AlertTriangle size={22} fill="currentColor" stroke="white" strokeWidth={1.8} aria-hidden="true" /></span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-start justify-between gap-1">
                    <h2 className="text-[16px] font-bold text-[#b42330]">위험 물체 감지 알림</h2>
                    <span className="shrink-0 rounded-full bg-[#ffe8e9] px-2 py-[3px] text-[10px] font-semibold text-[#b42330]">{isPaused ? '일시정지 중' : '상태 확인 중'}</span>
                  </div>
                  <p className="mt-1 text-[12px] leading-[1.4]">
                    {activeHazard.locationLabel} <strong className="text-[#b42330]">위험 물체({activeHazard.objectName}) 1개</strong>가 감지되어 로봇청소기 운행이 {isPaused ? '즉시 정지되었습니다.' : '정지 확인 중입니다.'}
                  </p>
                </div>
              </div>
              <div className="mt-8 flex justify-end gap-2 border-t border-[#f9e7e7] pt-3">
                <button type="button" onClick={() => setModal('avoidance')} className="h-[32px] rounded-full border border-[#e9c6ca] bg-white px-[14px] text-[12px] font-semibold focus-visible:outline-[#a50034]">우회 청소</button>
                <button type="button" onClick={() => void openHazardDetail(activeHazard)} className="inline-flex h-[32px] items-center rounded-full bg-[#b9003d] px-[14px] text-[12px] font-semibold text-white focus-visible:outline-[#a50034]">위치 확인하기 <ArrowRight size={14} className="ml-1" /></button>
              </div>
            </section>
          )}
          <div className="mb-2 flex items-center justify-between">
            <h1 className="text-[18px] font-semibold">즐겨 찾는 제품</h1>
            <button type="button" onClick={() => setModal('device')} className="text-[12px] text-[#475569] hover:underline focus-visible:outline-[#a50034]">전체보기</button>
          </div>

          <section aria-label="로봇청소기 상태" className={`rounded-[20px] border border-[#e8edf5] bg-white p-4 shadow-sm ${isOnline ? 'min-h-[246px]' : 'min-h-[149px]'}`}>
            <div className="flex items-start justify-between gap-2">
              <div className="flex min-w-0 items-center gap-2.5">
                <div className="relative flex size-[42px] shrink-0 items-center justify-center rounded-[14px] bg-[#f0f5fd]">
                  <img src={robotIcon} alt="" className="h-[20px] w-[26px]" />
                  <img src={robotDot} alt="" className="absolute right-[5px] top-[4px] size-2" />
                </div>
                <div className="min-w-0">
                  <h2 className="truncate text-[16px] font-bold text-black">{device ? 'LG 로니 AI 베이비 케어' : loadError ? '기기 상태 조회 실패' : dashboard ? '등록된 기기가 없어요' : '기기 상태 확인 중'}</h2>
                  <span className="mt-1 inline-flex rounded-full bg-[#d1feee] px-[7px] py-[1px] text-[10px] text-[#166b58]">{device ? '로봇' : '연결 전'}</span>
                </div>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-[6px]">
                <span className="rounded-full bg-[#e1fff2] px-2 py-1 text-[11px] text-[#167359]">⊙ {device?.safetyModeEnabled === true ? '안심모드 ON' : '안심모드 확인 전'}</span>
                <span className={`rounded-full px-2 py-[2px] text-[11px] font-medium ${isOnline ? 'bg-[#dcfcef] text-[#15805f]' : 'bg-[#fff0f1] text-[#b4233b]'}`}>
                  {isOnline ? '온라인' : isOffline ? '오프라인' : '상태 확인 전'}
                </span>
                {isPaused && <span className="rounded-full bg-[#fff0f1] px-2 py-[2px] text-[11px] text-[#b4233b]">일시 정지</span>}
                {device?.batteryPercent != null && <span className="text-[11px] text-[#475569]">배터리 {device.batteryPercent}%</span>}
              </div>
            </div>

            <div className="mt-3 border-t border-[#e8edf5] pt-3">
              {isOnline ? (
                <>
                  <div className="mx-auto grid max-w-[258px] grid-cols-2 gap-4">
                    <div className="flex h-[85px] flex-col items-center justify-center rounded-[18px] bg-[#f5f8ff] text-center">
                      <span className="text-[11px] text-[#475569]">장애물 정밀 감지</span>
                      <div className="mt-1 flex items-baseline gap-1">
                        <span className="text-[22px] font-semibold">{dashboard?.obstacleCount ?? dashboard?.activeHazards.length ?? 0}개</span>
                        {dashboard?.obstacleLabel && <span className="text-[10px] font-medium text-[#bd003f]">{dashboard.obstacleLabel}</span>}
                      </div>
                    </div>
                    <div className="flex h-[85px] flex-col items-center justify-center rounded-[18px] bg-[#f5f8ff] text-center">
                      <span className="text-[11px] text-[#475569]">공기 청정 연동</span>
                      <div className="mt-1 flex items-baseline gap-1">
                        <span className="text-[21px] font-semibold text-[#275b52]">{device?.airQualityLabel ?? '확인 전'}</span>
                        {device?.purifierStateLabel && <span className="text-[10px] text-[#275b52]">퓨리케어 {device.purifierStateLabel}</span>}
                      </div>
                    </div>
                  </div>
                  <div className="relative mt-2 flex items-center justify-center">
                    <button type="button" onClick={() => setModal('hazards')} className="flex h-[38px] w-[205px] items-center justify-center rounded-full bg-[#b9003d] text-[14px] font-semibold text-white focus-visible:outline-[#a50034]">
                      실시간 위험 감지 맵 <ArrowRight size={15} className="ml-1" />
                    </button>
                    <button type="button" onClick={handleDeviceCommand} disabled={commandPending} aria-label={isPaused ? '청소 재개' : '청소 일시정지'} className="absolute right-0 h-[46px] w-[50px] disabled:cursor-wait disabled:opacity-60">
                      <img src={isPaused ? resumeButton : pauseButton} alt="" className="h-full w-full" />
                    </button>
                  </div>
                </>
              ) : (
                <div className="flex items-center justify-end gap-2">
                  <span className="rounded-full bg-[#fff0f1] px-3 py-1 text-[11px] font-medium text-[#b4233b]">
                    {isOffline ? '기기 연결을 확인해주세요' : '기기 연결 상태를 확인할 수 없어요'}
                  </span>
                  <button type="button" onClick={() => setModal('device')} aria-label="기기 상태 안내" className="h-[46px] w-[44px] shrink-0 focus-visible:outline-[#a50034]"><img src={powerButton} alt="" className="h-full w-full" /></button>
                </div>
              )}
            </div>
          </section>
          {commandError && <p role="alert" className="mt-2 text-center text-[12px] text-[#a50034]">{commandError}</p>}

          <section aria-label="아이 안전 프로필" className="mt-[18px] min-h-[185px] rounded-[24px] bg-gradient-to-r from-[#d9064d] via-[#ee4f7e] to-[#fa80a5] p-5 text-white shadow-[0_6px_15px_rgba(174,0,57,0.14)]">
            <div className="flex items-start justify-between">
              <span className="rounded-full bg-white/20 px-[10px] py-[5px] text-[11px] font-medium">✦ {dashboard?.isMock && isSupported ? '현재 Safety Profile 자동 적용 중' : isSupported ? 'Safety Profile 등록 완료' : '지원 범위 밖'}</span>
              <span className="grid size-[44px] place-items-center rounded-[14px] bg-white/20"><Smile size={22} aria-hidden="true" /></span>
            </div>
            <h2 className="-mt-1 max-w-[260px] text-[21px] font-bold leading-[1.2]">
              {isSupported && profile.stage ? stageTitles[profile.stage] : '현재 지원하는 연령이 아니에요'}
            </h2>
            <p className="mt-1 max-w-[280px] text-[12px] leading-[1.4] text-white/95">
              {isSupported && profile.stage ? dashboard?.isMock && profile.stage === 'TODDLER' ? '모서리 충돌 방지 및 바닥 전선 걸림 집중 감지 모드' : `${child.name}의 ${stageLabels[profile.stage]} 안전 프로필을 등록했어요.` : '안전 프로필이 적용되지 않았어요.'}
            </p>
            <div className="mt-3 flex justify-end border-t border-white/25 pt-2">
              <button type="button" onClick={() => setModal('profile')} className="text-[11px] underline underline-offset-2 focus-visible:outline-white">상세 보기 &gt;</button>
            </div>
          </section>

          <section aria-label="월간 성장 리포트" className="mt-[24px] min-h-[190px] rounded-[24px] border border-[#e8edf5] bg-white p-5 shadow-sm">
            <div className="flex items-center justify-between gap-2">
              <span className="rounded-full border border-[#fee2e2] bg-[#fef2f2] px-[10px] py-[3px] text-[11px] text-[#a50034]">{report?.available ? `이번 달(${Number(report.month.slice(5))}월) 리포트 발행 완료` : '리포트 준비 중'}</span>
              <span className="text-[10px] text-[#94a3b8]">매월 1일 자동 업데이트</span>
            </div>
            <div className="mt-3 flex items-center gap-2">
              <img src={reportIcon} alt="" className="size-[15px]" />
              <h2 className="text-[18px] font-semibold">우리 아이 맞춤 성장 리포트</h2>
            </div>
            <p className="mt-1 text-[12px] leading-[1.6] text-[#475569]">
              {report?.available ? `${child.name} 아동의 ${Number(report.month.slice(5))}월 행동 반경 및 위험물 접촉 분석 데이터가 포함된 심층 리포트를 확인해보세요.` : '기기 데이터가 쌓이면 월간 성장 리포트를 확인할 수 있어요.'}
            </p>
            <div className="mt-3 border-t border-[#f1f5f9] pt-2 text-center">
              <button type="button" onClick={() => setModal('report')} disabled={!report?.available} className="inline-flex h-[43px] w-[205px] items-center justify-center rounded-full bg-[#b9003d] text-[15px] font-semibold text-white disabled:cursor-not-allowed disabled:opacity-45">
                리포트 보러가기 <ArrowRight size={16} className="ml-1" />
              </button>
            </div>
          </section>
          {dashboard?.isMock && <p className="mt-3 text-center text-[11px] text-[#94a3b8]">현재 기기·리포트 수치는 화면 확인용 예시입니다.</p>}
          {loadError && <p role="alert" className="mt-3 text-center text-[12px] text-[#a50034]">홈 데이터를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.</p>}
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
              <h2 id="home-dialog-title" className="text-[18px] font-semibold">{modal === 'profile' ? 'Safety Profile' : modal === 'hazards' ? '실시간 위험 감지' : modal === 'avoidance' ? '우회 청소' : modal === 'report' ? '우리 아이 맞춤 성장 리포트' : '기기 연결 상태'}</h2>
              <button ref={closeButtonRef} type="button" onClick={() => setModal(null)} aria-label="닫기" className="rounded-full p-1 text-[#475569] focus-visible:outline-[#a50034]"><X size={20} /></button>
            </div>
            {modal === 'profile' ? (
              <div className="mt-4 space-y-2 text-[14px] text-[#475569]">
                <p>아이: <strong className="text-[#1e293b]">{child.name}</strong></p>
                <p>월령: <strong className="text-[#1e293b]">{profile.ageMonths}개월</strong></p>
                <p>성장 단계: <strong className="text-[#1e293b]">{isSupported && profile.stage ? stageLabels[profile.stage] : '지원 범위 밖'}</strong></p>
                <p className="pt-2 text-[12px]">기기에 안전 기준이 적용됐는지는 서버 연결 후 확인할 수 있어요.</p>
              </div>
            ) : modal === 'avoidance' ? (
              <p className="mt-4 text-[14px] leading-6 text-[#475569]">우회 청소는 기기가 위험물을 피해 안전하게 이동하는 방식이 확정된 뒤 사용할 수 있어요. 현재 로봇청소기는 정지 상태를 유지합니다. 위치를 확인하고 위험물을 직접 치워 주세요.</p>
            ) : modal === 'hazards' ? (
              <div className="mt-4 text-[14px] text-[#475569]">
                {dashboard?.activeHazards.length ? dashboard.activeHazards.map((hazard) => <button key={hazard.hazardId} type="button" onClick={() => void openHazardDetail(hazard)} className="block w-full border-b border-[#e2e8f0] py-2 text-left focus-visible:outline-[#a50034]">{hazard.objectName} · {hazard.locationLabel} <ArrowRight size={14} className="inline" /></button>) : <p>현재 표시할 위험 감지 내역이 없어요.</p>}
              </div>
            ) : modal === 'report' ? (
              <div className="mt-4 space-y-2 text-[14px] leading-6 text-[#475569]">
                <p>{child.name}의 {report ? Number(report.month.slice(5)) : ''}월 리포트입니다.</p>
                <p>{dashboard?.isMock ? '현재 표시된 리포트는 화면 확인용 예시입니다. 실제 분석 결과는 서버 연결 후 제공됩니다.' : '리포트 상세 화면에서 월별 분석 결과를 확인할 수 있어요.'}</p>
              </div>
            ) : (
              <p className="mt-4 text-[14px] leading-6 text-[#475569]">
                {isOnline ? '기기가 온라인 상태예요.' : isOffline ? '기기가 오프라인이에요. 로봇청소기의 전원과 네트워크 연결을 확인해 주세요.' : '서버와 기기 연결 상태를 확인할 수 없어요. 연동 후 상태가 표시됩니다.'}
              </p>
            )}
          </section>
        </div>
      )}
    </div>
  )
}
