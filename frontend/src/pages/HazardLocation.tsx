import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, CheckCircle2, Loader2, ShieldCheck } from 'lucide-react'
import floorPlanPreview from '../assets/figma/safety-profile/floor-plan-clean.png'
import capturePreview from '../assets/figma/hazard/capture.png'
import robotIcon from '../assets/figma/home/imgVector5.svg'
import { sendDeviceCommand, type DashboardHazard, type HazardDetail, type HazardMarker } from '../services/dashboard'
import { apiErrorMessage } from '../services/apiError'
import { getSafetyAction, requestRelocation, requestRemovalCheck } from '../services/operations'
import { describeHazard, riskLabels, riskStyles, withTopicParticle } from '../lib/hazardRisk'
import HazardAlertBox from '../components/HazardAlertBox'
import type { Stage } from '../lib/stages'

type RealActionState = 'idle' | 'submitting' | 'pending' | 'done'
// idle: 조치 선택 전 / relocated: 안전한 장소로 이동 완료(청소 재개 대기) / removal-guide: 직접 제거 후 "제거 완료" 대기 / running: 청소 재개 후 작동 중
type Flow = 'idle' | 'relocating' | 'relocated' | 'removal-guide' | 'checking' | 'checked' | 'running'

// 목업 안전 이송 때 위험 물체 마커가 옮겨 가는 안전한 장소(지도의 현관 구역)와 이동 시간
const SAFE_ZONE_MARKER: HazardMarker = { x: 0.843, y: 0.585 }
const RELOCATE_MOCK_MS = 3000
const MARKER_GLIDE_MS = 1200

interface Props {
  hazard: DashboardHazard | null
  deviceId: string
  stage: Stage | null
  operationState: string
  detail: HazardDetail | null
  error: string
  errorStatus: number | null
  isMock: boolean
  onBack: () => void
  onRetry: () => void
}

const statusBoxClass = 'flex min-h-[54px] flex-1 items-center justify-center gap-2 rounded-[20px] px-2 text-center'
const greenBox = `${statusBoxClass} bg-[#10b981] text-white`
const grayBox = `${statusBoxClass} bg-[#e5e7eb] text-[#6b7280]`
const outlineButton = 'min-h-[54px] flex-1 rounded-[20px] border border-[#3755ff] bg-white px-2 text-[13px] font-semibold text-[#2948dd]'
const primaryButton = 'min-h-[54px] flex-1 rounded-[20px] bg-[#b9003d] px-2 text-[13px] font-bold leading-4 text-white shadow-[0_3px_8px_rgba(185,0,61,0.25)]'

export default function HazardLocation({ hazard, deviceId, stage, operationState, detail, error, errorStatus, isMock, onBack, onRetry }: Props) {
  const name = detail?.objectName ?? hazard?.objectName ?? ''
  const detectedAt = detail?.detectedAt ?? hazard?.detectedAt
  const displayTime = detectedAt ? new Intl.DateTimeFormat('ko-KR', {
    hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'Asia/Seoul',
  }).format(new Date(detectedAt)) : ''
  const [actionMessage, setActionMessage] = useState('')
  const [autoTransport, setAutoTransport] = useState(false)
  const [flow, setFlow] = useState<Flow>('idle')
  const [redetected, setRedetected] = useState(false)
  const [failedCaptureUrl, setFailedCaptureUrl] = useState<string | null>(null)
  const [relocationState, setRelocationState] = useState<RealActionState>('idle')
  const [relocationActionId, setRelocationActionId] = useState<string | null>(null)
  const [removalState, setRemovalState] = useState<RealActionState>('idle')
  const [resuming, setResuming] = useState(false)
  // ?mockRedetect=1 : 목업에서 첫 번째 제거 확인 때 위험 물체가 다시 감지되는 상황을 보여준다.
  const redetectOnce = useRef(new URLSearchParams(window.location.search).get('mockRedetect') === '1')
  const restricted = errorStatus === 403 || errorStatus === 404

  // 목업에서는 조치가 끝나면(running) 위험 물체가 해결된 것으로 본다.
  const activeHazard = flow === 'running' ? null : hazard
  const alert = activeHazard ? describeHazard({ objectName: name, riskLevel: detail?.riskLevel ?? activeHazard.riskLevel }, stage, !isMock) : null
  const alertStyle = riskStyles[alert?.risk ?? 'HIGH']
  const isLiving = alert?.category === 'LIVING'
  const riskLabel = alert?.risk ? riskLabels[alert.risk] : '확인 전'
  // 목업 사진은 목업 화면에서만 쓴다. 실제 모드는 서버가 준 감지 사진만 보여주고, 없거나 불러오지 못하면 안내 문구를 표시한다.
  const captureLoading = !isMock && !detail && !error
  const serverCaptureUrl = detail?.captureImageUrl ?? null
  const captureSrc = isMock ? capturePreview : serverCaptureUrl && serverCaptureUrl !== failedCaptureUrl ? serverCaptureUrl : null
  // 위험 물체가 안전한 장소로 이동을 마친 상태(목업 흐름 또는 서버가 이송 완료를 알려준 경우)
  const relocationDone = flow === 'relocated' || relocationState === 'done'
  const marker: HazardMarker | null = isMock && (flow === 'relocating' || flow === 'relocated') ? SAFE_ZONE_MARKER : detail?.marker ?? null
  const markerGlideMs = flow === 'relocating' ? RELOCATE_MOCK_MS : MARKER_GLIDE_MS
  const defaultStatus = operationState === 'RUNNING' ? '로봇청소기 작동 중' : operationState === 'PAUSED' ? '로봇청소기 일시 정지' : '로봇청소기 상태 확인 전'

  useEffect(() => {
    if (!isMock) return
    const next: Partial<Record<Flow, [Flow, number]>> = {
      relocating: ['relocated', RELOCATE_MOCK_MS],
      checked: ['running', 1500],
    }
    if (flow === 'checking') {
      const timer = setTimeout(() => {
        if (redetectOnce.current) {
          redetectOnce.current = false
          setRedetected(true)
          setFlow('idle')
        } else {
          setFlow('checked')
        }
      }, 3000)
      return () => clearTimeout(timer)
    }
    const step = next[flow]
    if (!step) return
    const timer = setTimeout(() => setFlow(step[0]), step[1])
    return () => clearTimeout(timer)
  }, [flow, isMock])

  // 실제 모드: 안전 이송 요청 접수 후 처리 결과를 주기적으로 조회한다. 서버가 이송 완료를 알려줄 때만 완료로 표시한다.
  useEffect(() => {
    if (isMock || !relocationActionId || relocationState !== 'pending') return
    let active = true
    async function check(actionId: string) {
      try {
        const result = await getSafetyAction(actionId)
        if (!active) return
        if (result.status === 'TEMPORARY_COMPLETED' || result.treatmentStatus === 'TEMPORARY_COMPLETED') {
          setRelocationState('done')
        } else if (result.status === 'FAILED') {
          setRelocationState('idle')
          setRelocationActionId(null)
          setActionMessage('위험 물체 이송에 실패했어요. 위험 물체를 직접 치워 주세요.')
        }
      } catch {
        // 조회에 실패해도 이송 결과를 알 수 없을 뿐이므로 다음 주기에 다시 조회한다.
      }
    }
    void check(relocationActionId)
    const timer = window.setInterval(() => void check(relocationActionId), 3000)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [isMock, relocationActionId, relocationState])

  function toggleAutoTransport() {
    if (!isMock) {
      setActionMessage('자동 이송 모드는 아직 지원되지 않아요. 위험 물체 안전 이송 또는 직접 제거를 이용해 주세요.')
      return
    }
    const enabled = !autoTransport
    setAutoTransport(enabled)
    if (enabled && flow === 'idle') {
      setRedetected(false)
      setFlow('relocating')
    }
  }

  async function startRelocation() {
    if (isMock) {
      setRedetected(false)
      setFlow('relocating')
      return
    }
    if (relocationState !== 'idle') return
    setRelocationState('submitting')
    try {
      const receipt = await requestRelocation(hazard?.hazardId ?? '')
      setRelocationActionId(receipt.actionId)
      setRelocationState('pending')
    } catch (err) {
      setRelocationState('idle')
      setActionMessage(apiErrorMessage(err, '위험 물체 안전 이송 요청을 접수하지 못했어요.'))
    }
  }

  async function resumeCleaning() {
    if (resuming) return
    setResuming(true)
    try {
      await sendDeviceCommand(deviceId, 'resume', isMock)
      setFlow('running')
    } catch (err) {
      setActionMessage(apiErrorMessage(err, '청소를 다시 시작하지 못했어요. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setResuming(false)
    }
  }

  function startDirectRemoval() {
    setRedetected(false)
    setFlow('removal-guide')
  }

  async function confirmRemoval() {
    if (isMock) {
      setFlow('checking')
      return
    }
    if (removalState !== 'idle') return
    setRemovalState('submitting')
    try {
      await requestRemovalCheck(hazard?.hazardId ?? '')
      setRemovalState('pending')
    } catch (err) {
      setRemovalState('idle')
      setActionMessage(apiErrorMessage(err, '위험 물체 확인 요청을 접수하지 못했어요.'))
    }
  }

  function acknowledgeLivingHazard() {
    if (!isMock) {
      setActionMessage('위험 요소 확인 결과를 기기에 전달하는 기능은 아직 연동 전이에요.')
      return
    }
    setFlow('running')
  }

  function renderFooter() {
    if (!activeHazard) return <div role="status" className={grayBox}><strong className="text-[14px] font-semibold">{flow === 'running' ? '로봇청소기 작동 중' : defaultStatus}</strong></div>

    if (resuming) return <div role="status" className={greenBox}><Loader2 size={18} className="animate-spin" aria-hidden="true" /><strong className="text-[15px] font-bold">청소 재개 중</strong></div>
    if (relocationDone) {
      return (
        <>
          <div role="status" className={`${statusBoxClass} flex-col bg-[#10b981] text-white`}>
            <strong className="text-[14px] font-bold">위험물을 처리했습니다</strong>
            <span className="text-[11px]">안전한 장소로 이동을 완료했어요</span>
          </div>
          <button type="button" onClick={() => void resumeCleaning()} className={primaryButton}>청소 재개</button>
        </>
      )
    }

    if (!isMock) {
      if (relocationState === 'submitting') return <div className={grayBox}><Loader2 size={16} className="animate-spin" aria-hidden="true" /><strong className="text-[14px] font-semibold">위험 물체 이송 요청 중</strong></div>
      if (relocationState === 'pending') return <div role="status" className={`${statusBoxClass} flex-col bg-[#eef2ff] text-[#3730a3]`}><strong className="text-[13px] font-bold">위험 물체 이송 요청이 접수됐어요</strong><span className="text-[11px]">이송 결과는 아직 확인되지 않아요. 위험 물체가 보이면 직접 치워 주세요.</span></div>
      if (removalState === 'submitting') return <div className={grayBox}><Loader2 size={16} className="animate-spin" aria-hidden="true" /><strong className="text-[14px] font-semibold">위험 물체 확인 요청 중</strong></div>
      if (removalState === 'pending') return <div role="status" className={`${statusBoxClass} flex-col bg-[#eef2ff] text-[#3730a3]`}><strong className="text-[13px] font-bold">위험 물체 확인 중</strong><span className="text-[11px]">기기의 확인 결과를 아직 받지 못했어요.</span></div>
    }

    if (flow === 'relocating') return <div role="status" className={greenBox}><Loader2 size={18} className="animate-spin" aria-hidden="true" /><strong className="text-[15px] font-bold">위험 물체 이송 중</strong></div>
    if (flow === 'checking') return <div role="status" className={greenBox}><Loader2 size={18} className="animate-spin" aria-hidden="true" /><strong className="text-[15px] font-bold">위험 물체 확인 중</strong></div>
    if (flow === 'checked') return <div role="status" className={greenBox}><CheckCircle2 size={18} aria-hidden="true" /><strong className="text-[15px] font-bold">위험 물체 확인 완료</strong></div>
    if (flow === 'removal-guide') return <button type="button" onClick={() => void confirmRemoval()} className={primaryButton}>위험 물체 제거 완료</button>

    if (isLiving) return <button type="button" onClick={acknowledgeLivingHazard} className={primaryButton}>위험 요소 확인</button>
    return (
      <>
        <button type="button" onClick={() => void startRelocation()} className={outlineButton}>위험 물체 안전 이송</button>
        <button type="button" onClick={startDirectRemoval} className={primaryButton}>사용자 직접 제거</button>
      </>
    )
  }

  return (
    <div className="min-h-screen bg-[#f0f5fd] text-[#1e293b] [zoom:clamp(0.85,calc(100vw/402px),1.4)]">
      <div className="mx-auto min-h-screen max-w-[402px] pb-[112px]">
        <header className="sticky top-0 z-10 flex min-h-[66px] items-center gap-4 bg-[#f7f9ff] px-7 py-2">
          <button type="button" onClick={onBack} aria-label="홈으로 돌아가기" className="grid size-6 shrink-0 place-items-center focus-visible:outline-[#a50034]"><ArrowLeft size={22} /></button>
          <h1 className="min-w-0 flex-1 text-[18px] font-bold leading-5">스마트 안심 케어 맵</h1>
          <span className="shrink-0 rounded-full bg-[#e1fff2] px-3 py-1.5 text-[12px] font-medium text-[#167359]">⊙ 드니 모드 ON</span>
        </header>

        {restricted ? <main className="px-4 pt-6"><div role="alert" className="rounded-[16px] border border-[#f2c5cb] bg-white p-5 text-[14px] text-[#9d1237]">{error}</div></main> : <main className="space-y-[17px] px-4 pt-3">
          {redetected && activeHazard && (
            <HazardAlertBox role="alert" ariaLabel="위험 물체 재감지 알림" badge="재감지" urgent title="위험 물체가 다시 감지되었어요" subtitle={`확인 중 ${name}이(가) 남아 있어요. 다시 조치해 주세요.`} />
          )}
          {activeHazard && alert && (
            <HazardAlertBox ariaLabel="위험 물체 감지 알림" badge={alert.urgencyLabel} urgent={alert.urgent} riskLabel={alert.risk ? riskLabel : null} title={alert.title} subtitle={displayTime ? `감지 시간 ${displayTime}` : undefined} />
          )}

          {error && (
            <div role="alert" className="rounded-[16px] border border-[#f2c5cb] bg-white p-4 text-[13px] text-[#9d1237]">
              <p>{error}</p>
              <button type="button" onClick={onRetry} className="mt-2 font-semibold underline">다시 시도</button>
            </div>
          )}

          <section aria-label="스마트 안심 케어 맵" className="rounded-[22px] bg-white p-4 shadow-[0_2px_8px_rgba(48,60,90,0.06)]">
            <h2 className="mb-3 flex items-center gap-2 text-[15px] font-bold"><span className="size-[10px] rounded-full bg-[#2958c7]" />스마트 안심 케어 맵</h2>
            {!isMock && hazard && !detail && !error ? <p className="rounded-[16px] bg-[#f3f6fc] p-5 text-center text-[13px] text-[#64748b]">지도를 불러오고 있어요.</p> : (
              <>
                <div className="relative overflow-hidden rounded-[20px] border border-black/10">
                  <img src={floorPlanPreview} alt="스마트 안심 케어 맵" className="block w-full" />
                  {activeHazard && marker && (
                    <span
                      role="img"
                      aria-label={relocationDone ? '안전한 장소로 이동한 위험 물체 위치' : '위험 물체 위치'}
                      className={`absolute w-[7.5%] -translate-x-1/2 -translate-y-1/2 motion-reduce:animate-none ${relocationDone ? '' : 'animate-blink'}`}
                      style={{ left: `${marker.x * 100}%`, top: `${marker.y * 100}%`, transition: `left ${markerGlideMs}ms ease-in-out, top ${markerGlideMs}ms ease-in-out` }}
                    >
                      <span className={`grid aspect-square place-items-center rounded-full ${relocationDone ? 'bg-[#10b981]/20' : 'bg-[#e11d48]/20'}`}>
                        <span className={`grid size-[62%] place-items-center rounded-full border-[3px] border-white text-[11px] font-bold leading-none text-white ${relocationDone ? 'bg-[#10b981]' : 'bg-[#a5003a]'}`}>{relocationDone ? '✓' : '!'}</span>
                      </span>
                    </span>
                  )}
                </div>
                {isMock && <p className="mt-2 text-center text-[11px] text-[#94a3b8]">지도는 화면 확인용 예시입니다.</p>}
              </>
            )}
          </section>

          <section aria-label="드니 AI 실시간 캡처" className="rounded-[22px] bg-white px-[18px] pb-[18px] pt-[16px] shadow-[0_2px_8px_rgba(48,60,90,0.06)]">
            <h2 className="flex items-center gap-2 text-[16px] font-bold text-[#171c25]"><img src={robotIcon} alt="" className="size-5" />드니 AI 실시간 캡처</h2>
            {activeHazard && alert ? (
              <>
                <div className="relative mx-auto mt-3 w-[210px] overflow-hidden rounded-[12px] border-2 border-dashed border-[#e11d48]/70 bg-[#e5e7eb]">
                  {captureLoading
                    ? <div className="grid aspect-[35/24] place-items-center text-[13px] text-[#64748b]">감지 사진을 불러오고 있어요</div>
                    : captureSrc
                      ? <img src={captureSrc} onError={() => setFailedCaptureUrl(captureSrc)} alt={`${name} 감지 사진`} className="aspect-[35/24] w-full object-cover" />
                      : <div role="status" className="grid aspect-[35/24] place-items-center px-3 text-center text-[13px] text-[#64748b]">감지 사진을 불러오지 못했어요</div>}
                  <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-2 bg-[#141414]/90 px-2 py-1.5 text-white">
                    <strong className="min-w-0 text-[11px] leading-4">{name}</strong>
                    <span className={`shrink-0 rounded-full px-1.5 py-0.5 text-[10px] font-bold ${alertStyle.chip}`}>위험도 {riskLabel}</span>
                  </div>
                </div>
                <div className="mt-3 break-keep rounded-[12px] border border-[#d7e2ff] bg-[#f4f7ff] px-3 py-3 text-[13px] leading-5">
                  <p><span className="mr-2 font-bold text-[#b9003d]">ⓘ</span>{withTopicParticle(name)} 아이의 성장단계에서 {isLiving ? '만지면 위험한' : '삼킬 위험이 있는'} 물체입니다.</p>
                </div>
                {!isLiving && (
                  <div className="mt-4 flex items-center justify-between gap-2 border-t border-[#eef0f5] pt-3">
                    <strong className="flex items-center gap-2 text-[15px]"><ShieldCheck size={19} className="text-[#2958c7]" />위험 구역 진입시 자동 이송 모드</strong>
                    <button
                      type="button"
                      onClick={toggleAutoTransport}
                      aria-pressed={autoTransport}
                      aria-label={autoTransport ? '자동 이송 모드 켜짐' : '자동 이송 모드 꺼짐'}
                      className={`flex h-[24px] w-[46px] shrink-0 items-center rounded-full p-1 transition-colors focus-visible:outline-[#a50034] ${autoTransport ? 'justify-end bg-[#b9003d]' : 'justify-start bg-[#d1d5db]'}`}
                    >
                      <span className="size-4 shrink-0 rounded-full bg-white shadow-sm" />
                    </button>
                  </div>
                )}
              </>
            ) : (
              <div className="mt-3 rounded-[12px] border border-[#e2e8f0] bg-[#f8fafc] px-3 py-5 text-center text-[13px] text-[#64748b]">감지된 위험 물체가 없습니다.</div>
            )}
          </section>
          {isMock && <p className="text-center text-[11px] text-[#94a3b8]">지도·사진·위험 정보는 화면 확인용 예시입니다.</p>}
        </main>}
      </div>

      {!restricted && (
        <footer className="fixed bottom-0 left-1/2 z-10 flex w-full max-w-[402px] -translate-x-1/2 gap-3 border-t border-[#e2e8f0] bg-white px-3 pb-5 pt-3">
          {renderFooter()}
        </footer>
      )}
      {actionMessage && <div role="alert" className="fixed bottom-[85px] left-1/2 z-20 w-[calc(100%-32px)] max-w-[370px] -translate-x-1/2 rounded-xl bg-[#25252b] p-3 text-[12px] text-white shadow-lg" onClick={() => setActionMessage('')}>{actionMessage}</div>}
    </div>
  )
}
