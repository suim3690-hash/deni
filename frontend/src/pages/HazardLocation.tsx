import { useEffect, useState } from 'react'
import { ArrowLeft, AlertTriangle, CheckCircle2, Loader2, ShieldCheck, SlidersHorizontal } from 'lucide-react'
import floorPlanPreview from '../assets/figma/safety-profile/floor-plan.png'
import capturePreview from '../assets/figma/hazard/capture.png'
import robotIcon from '../assets/figma/home/imgVector5.svg'
import type { DashboardHazard, HazardDetail } from '../services/dashboard'

interface Props {
  hazard: DashboardHazard
  detail: HazardDetail | null
  error: string
  errorStatus: number | null
  isMock: boolean
  onBack: () => void
  onRetry: () => void
}

function HazardMarker({ x, y, label }: { x: number, y: number, label: string }) {
  return (
    <span role="img" aria-label={label} className="absolute -translate-x-1/2 -translate-y-1/2" style={{ left: `${x * 100}%`, top: `${y * 100}%` }}>
      <span className="relative flex size-5">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-[#e11d48] opacity-75" />
        <span className="relative inline-flex size-5 rounded-full border-2 border-white bg-[#e11d48] shadow-md" />
      </span>
    </span>
  )
}

export default function HazardLocation({ hazard, detail, error, errorStatus, isMock, onBack, onRetry }: Props) {
  const name = detail?.objectName ?? hazard.objectName
  const location = detail?.locationLabel ?? hazard.locationLabel
  const detectedAt = detail?.detectedAt ?? hazard.detectedAt
  const displayTime = new Intl.DateTimeFormat('ko-KR', {
    hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'Asia/Seoul',
  }).format(new Date(detectedAt))
  const [actionMessage, setActionMessage] = useState('')
  const [mapFailed, setMapFailed] = useState(false)
  const [captureFailed, setCaptureFailed] = useState(false)
  const [autoTransport, setAutoTransport] = useState(false)
  const [transportPhase, setTransportPhase] = useState<'moving' | 'done'>('moving')
  const [manualPhase, setManualPhase] = useState<'idle' | 'moving' | 'removed' | 'done'>('idle')
  const [removePhase, setRemovePhase] = useState<'idle' | 'verifying' | 'clear' | 'temp-safe' | 'done'>('idle')
  const restricted = errorStatus === 403 || errorStatus === 404
  const riskLabel = isMock ? '삼킴 고위험' : detail?.riskLevel === 'VERY_HIGH' ? '매우 높은 위험' : detail?.riskLevel === 'HIGH' ? '높은 위험' : '위험 감지'
  const showSuccessBanner = isMock && ((autoTransport && transportPhase === 'done') || manualPhase === 'done' || removePhase !== 'idle')
  const showMovingAlert = isMock && ((autoTransport && transportPhase === 'moving') || manualPhase === 'moving' || manualPhase === 'removed')

  useEffect(() => {
    if (!isMock || !autoTransport) return
    const timer = setTimeout(() => setTransportPhase('done'), 3000)
    return () => clearTimeout(timer)
  }, [autoTransport, isMock])

  useEffect(() => {
    if (!isMock) return
    if (manualPhase === 'moving') {
      const timer = setTimeout(() => setManualPhase('removed'), 6000)
      return () => clearTimeout(timer)
    }
    if (manualPhase === 'removed') {
      const timer = setTimeout(() => setManualPhase('done'), 1500)
      return () => clearTimeout(timer)
    }
  }, [manualPhase, isMock])

  useEffect(() => {
    if (removePhase === 'verifying') {
      const timer = setTimeout(() => setRemovePhase('clear'), 4000)
      return () => clearTimeout(timer)
    }
    if (removePhase === 'clear') {
      const timer = setTimeout(() => setRemovePhase('temp-safe'), 3000)
      return () => clearTimeout(timer)
    }
    if (removePhase === 'temp-safe') {
      const timer = setTimeout(() => setRemovePhase('done'), 2000)
      return () => clearTimeout(timer)
    }
  }, [removePhase])

  function toggleAutoTransport() {
    if (!isMock) {
      setActionMessage('실제 기기 제어 기능은 아직 연결되지 않았습니다.')
      return
    }
    const enabled = !autoTransport
    if (enabled) setTransportPhase('moving')
    setAutoTransport(enabled)
  }

  function startManualTransport() {
    if (!isMock) {
      setActionMessage('실제 기기 제어 기능은 아직 연결되지 않았습니다.')
      return
    }
    setManualPhase('moving')
  }

  function startRemove() {
    if (!isMock) {
      setActionMessage('실제 기기 제어 기능은 아직 연결되지 않았습니다.')
      return
    }
    setRemovePhase('verifying')
  }

  return (
    <div className="min-h-screen bg-[#f0f5fd] text-[#1e293b] [zoom:clamp(0.85,calc(100vw/402px),1.4)]">
      <div className="mx-auto min-h-screen max-w-[402px] pb-[112px]">
        <header className="flex h-[66px] items-center gap-5 bg-[#f7f9ff] px-7">
          <button type="button" onClick={onBack} aria-label="홈으로 돌아가기" className="grid size-6 shrink-0 place-items-center focus-visible:outline-[#a50034]"><ArrowLeft size={22} /></button>
          <div className="min-w-0 flex-1">
            <p className="truncate text-[12px] font-semibold tracking-wide text-[#ae1245]"><span aria-hidden="true">●</span> BABY CARE MODE{isMock ? ' · 화면 예시' : ''}</p>
            <h1 className="truncate text-[18px] font-bold leading-5">실시간 위험 감지 &amp; 맵</h1>
          </div>
          <SlidersHorizontal size={20} aria-hidden="true" className="shrink-0 text-[#1e293b]" />
        </header>

        {restricted ? <main className="px-4 pt-6"><div role="alert" className="rounded-[16px] border border-[#f2c5cb] bg-white p-5 text-[14px] text-[#9d1237]">{error}</div></main> : <main className="space-y-[17px] px-4 pt-3">
          {showSuccessBanner ? (
            <section aria-label="위험 감지 상태" role="status" className="flex min-h-[74px] items-center gap-3 rounded-[20px] border border-[#a7f3d0] bg-[#ecfdf5] px-4 text-[#065f46] shadow-[0_2px_8px_rgba(6,95,70,0.06)]">
              <span className="grid size-10 shrink-0 place-items-center rounded-[12px] bg-[#10b981] text-white"><CheckCircle2 size={22} aria-hidden="true" /></span>
              <div className="min-w-0 flex-1">
                <strong className="text-[14px]">안전 확인됨</strong>
                <p className="mt-0.5 text-[12px] leading-[1.4]">위험물이 안전한 구역으로 이송완료 되었습니다.<br />이제 청소를 다시 진행중입니다.</p>
              </div>
            </section>
          ) : showMovingAlert ? (
            <section aria-label="위험 감지 상태" role="status" className="flex min-h-[74px] items-center gap-3 rounded-[20px] bg-gradient-to-r from-[#2e323b] to-[#4e2e3f] px-4 text-white shadow-[0_7px_17px_rgba(54,49,60,0.1)]">
              <span className="grid size-10 shrink-0 place-items-center rounded-[12px] bg-[#b9003d]"><Loader2 size={20} className="animate-spin" aria-hidden="true" /></span>
              <strong className="text-[14px]">로봇청소기가 위험물 안전위치 이동중입니다</strong>
            </section>
          ) : (
            <section aria-label="위험 감지 상태" className="flex min-h-[74px] items-center gap-3 rounded-[20px] bg-gradient-to-r from-[#2e323b] to-[#4e2e3f] px-4 text-white shadow-[0_7px_17px_rgba(54,49,60,0.1)]">
              <span className="grid size-10 shrink-0 place-items-center rounded-[12px] bg-[#b9003d]"><AlertTriangle size={22} aria-hidden="true" /></span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="shrink-0 rounded-full bg-[#b9003d] px-2 py-1 text-[11px] font-bold">긴급 주의</span>
                  <strong className="min-w-0 truncate text-[14px]">{isMock ? '영유아 삼킴 위험 물체 발견' : `${name} 위험 물체 발견`}</strong>
                </div>
                <p className="mt-1 text-right text-[12px] text-white/75">감지 시간: {displayTime}</p>
              </div>
            </section>
          )}

          {error && (
            <div role="alert" className="rounded-[16px] border border-[#f2c5cb] bg-white p-4 text-[13px] text-[#9d1237]">
              <p>{error}</p>
              <button type="button" onClick={onRetry} className="mt-2 font-semibold underline">다시 시도</button>
            </div>
          )}

          <section aria-label="스마트 안심 케어 맵" className="rounded-[22px] bg-white p-4 shadow-[0_2px_8px_rgba(48,60,90,0.06)]">
            <h2 className="mb-3 flex items-center gap-2 text-[15px] font-bold"><span className="size-[10px] rounded-full bg-[#2958c7]" />스마트 안심 케어 맵</h2>
            {isMock ? (
              <>
                <div className="overflow-hidden rounded-[20px] border border-black/10">
                  <img src={floorPlanPreview} alt="거실 집중 안전관리구역에 위험물 표시가 있는 예시 지도" className="block w-full" />
                </div>
                <div className="mt-2 flex items-center justify-center gap-1.5 rounded-full border border-[#f1f5f9] bg-white py-2 text-[12px] font-medium text-[#b4233b] shadow-sm">
                  <span className="size-2 shrink-0 rounded-full bg-[#b4233b]" />위험물체
                </div>
                <p className="mt-2 text-center text-[11px] text-[#94a3b8]">지도는 화면 확인용 예시입니다.</p>
                <p className="mt-1 text-[12px] text-[#475569]">위험물 위치 · {location}</p>
              </>
            ) : (
              <>
                {detail?.mapImageUrl && !mapFailed ? (
                  <div className="relative overflow-hidden rounded-[16px] border border-[#e2e8f0] bg-[#fbf8f1]">
                    <img src={detail.mapImageUrl} onError={() => setMapFailed(true)} alt="위험물이 감지된 집안 지도" className="block w-full" />
                    {detail.marker && <HazardMarker x={detail.marker.x} y={detail.marker.y} label={`${location} 위험물 위치`} />}
                  </div>
                ) : <p className="rounded-[16px] bg-[#f3f6fc] p-5 text-center text-[13px] text-[#64748b]">{!detail && !error ? '지도를 불러오고 있어요.' : `지도 이미지를 확인할 수 없어요. 감지 위치: ${location}`}</p>}
                <p className="mt-3 text-[12px] text-[#475569]">위험물 위치 · {location}</p>
              </>
            )}
          </section>

          <section aria-label="위험물 감지 상세" className="rounded-[22px] bg-white px-[18px] pb-[18px] pt-[16px] shadow-[0_2px_8px_rgba(48,60,90,0.06)]">
            <h2 className="flex items-center gap-2 text-[16px] font-bold text-[#171c25]"><img src={robotIcon} alt="" className="size-5" />드니 AI 실시간 캡처</h2>
            <div className="relative mx-auto mt-3 w-[210px] overflow-hidden rounded-[12px] border-2 border-dashed border-[#e11d48]/70 bg-[#e5e7eb]">
              {(isMock || detail?.captureImageUrl) && !captureFailed ? <img src={isMock ? capturePreview : detail?.captureImageUrl ?? ''} onError={() => setCaptureFailed(true)} alt={`${name}만 확대 촬영된 감지 사진 (프라이버시 보호를 위해 주변 공간은 표시하지 않음)`} className="aspect-[35/24] w-full object-cover" /> : <div className="grid aspect-[35/24] place-items-center text-[13px] text-[#64748b]">{!detail && !error ? '감지 사진을 불러오고 있어요' : '감지 사진을 확인할 수 없어요'}</div>}
              <span className="absolute left-2 top-2 max-w-[186px] truncate rounded-[4px] bg-[#e11d48] px-2 py-0.5 text-[10px] font-bold text-white shadow">{name}</span>
              <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-2 bg-[#141414]/90 px-2 py-1.5 text-white">
                <strong className="min-w-0 truncate text-[11px] leading-4">{name}</strong>
                <span className="shrink-0 rounded-full bg-[#ffdad9] px-1.5 py-0.5 text-[10px] font-bold text-[#b42330]">{riskLabel}</span>
              </div>
            </div>
            <p className="mt-2 text-center text-[11px] text-[#94a3b8]">프라이버시 보호를 위해 위험물만 확대해서 보여드려요 · {location}</p>
            <div className="mt-3 rounded-[12px] border border-[#d7e2ff] bg-[#f4f7ff] px-3 py-3 text-[13px] leading-5">
              <p><span className="mr-2 font-bold text-[#b9003d]">ⓘ</span>{detail?.riskReason ?? '위험물 상세 정보를 확인하고 있어요. 아이가 접근하기 전에 바닥에서 치워 주세요.'}</p>
            </div>
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
          </section>
          {isMock && <p className="text-center text-[11px] text-[#94a3b8]">지도·사진·위험 정보는 화면 확인용 예시입니다.</p>}
        </main>}
      </div>

      {!restricted && (
        <footer className="fixed bottom-0 left-1/2 z-10 flex w-full max-w-[402px] -translate-x-1/2 gap-3 border-t border-[#e2e8f0] bg-white px-3 pb-5 pt-3">
          {autoTransport ? (
            <button type="button" disabled className="flex min-h-[54px] flex-1 items-center justify-center gap-2 rounded-[20px] bg-[#e5e7eb] px-2 text-[14px] font-semibold text-[#6b7280] disabled:cursor-default">
              {transportPhase === 'moving' ? <><Loader2 size={16} className="animate-spin" aria-hidden="true" />안전 위치로 이동 중</> : '로봇청소기 작동중'}
            </button>
          ) : manualPhase === 'moving' || manualPhase === 'removed' ? (
            <div role="status" className="flex min-h-[54px] flex-1 flex-col items-center justify-center rounded-[20px] bg-[#10b981] px-2 text-center text-white">
              <strong className="text-[14px] font-bold">{manualPhase === 'moving' ? '위험물을 치우는 중입니다' : '위험물을 치웠어요'}</strong>
              <span className="text-[11px] text-white/90">기기가 위험물이 없어진 것을 확인하면 청소를 재개해요.</span>
            </div>
          ) : manualPhase === 'done' ? (
            <button type="button" disabled className="flex min-h-[54px] flex-1 items-center justify-center rounded-[20px] bg-[#e5e7eb] px-2 text-[14px] font-semibold text-[#6b7280] disabled:cursor-default">로봇청소기 작동중</button>
          ) : removePhase === 'verifying' ? (
            <div role="status" className="flex min-h-[54px] flex-1 items-center justify-center gap-2 rounded-[20px] bg-[#10b981] px-2 text-white">
              <Loader2 size={18} className="animate-spin" aria-hidden="true" />
              <strong className="text-[16px] font-bold">재확인 중</strong>
            </div>
          ) : removePhase === 'clear' ? (
            <div role="status" className="flex min-h-[54px] flex-1 flex-col items-center justify-center rounded-[20px] bg-[#10b981] px-2 text-center text-white">
              <strong className="text-[14px] font-bold">위험물이 더 이상 감지되지 않습니다</strong>
              <span className="text-[11px] text-white/90">청소를 재개합니다</span>
            </div>
          ) : removePhase === 'temp-safe' ? (
            <div role="status" className="flex min-h-[54px] flex-1 items-center justify-center rounded-[20px] bg-[#10b981] px-2 text-center text-white">
              <strong className="text-[15px] font-bold">임시 안전조치 완료 · 청소 재개 가능</strong>
            </div>
          ) : removePhase === 'done' ? (
            <button type="button" disabled className="flex min-h-[54px] flex-1 items-center justify-center rounded-[20px] bg-[#e5e7eb] px-2 text-[14px] font-semibold text-[#6b7280] disabled:cursor-default">로봇청소기 작동중</button>
          ) : (
            <>
              <button type="button" onClick={startManualTransport} className="min-h-[54px] flex-1 rounded-[20px] border border-[#3755ff] bg-white px-2 text-[12px] font-semibold text-[#2948dd]">안전 위치로 이동</button>
              <button type="button" onClick={startRemove} className="min-h-[54px] flex-1 rounded-[20px] bg-[#b9003d] px-2 text-[13px] font-bold leading-4 text-white shadow-[0_3px_8px_rgba(185,0,61,0.25)]">사용자 직접 제거<br />(위험물 우회 청소)</button>
            </>
          )}
        </footer>
      )}
      {actionMessage && <div role="alert" className="fixed bottom-[85px] left-1/2 z-20 w-[calc(100%-32px)] max-w-[370px] -translate-x-1/2 rounded-xl bg-[#25252b] p-3 text-[12px] text-white shadow-lg" onClick={() => setActionMessage('')}>{actionMessage}</div>}
    </div>
  )
}
