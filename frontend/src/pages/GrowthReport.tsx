import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, ArrowRight, Calendar, ChevronDown, ChevronLeft, ChevronRight, ShieldCheck, X } from 'lucide-react'
import MonthlyFeedbackButton from '../components/MonthlyFeedbackButton'
import StageChangeTimeline from '../components/StageChangeTimeline'
import { computeSafetyProfile, type RegisteredChild } from '../services/children'
import { currentReportMonth, getMonthlyReport, type MonthlyReport } from '../services/reports'
import { ApiRequestError, apiErrorMessage } from '../services/apiError'

interface Props {
  child: RegisteredChild
  month: string
  onBack: () => void
}

const chartColors = ['#a50034', '#ea580c', '#0369a1', '#9ca3af', '#059669']

function monthKey(year: number, month: number) {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`
}

export default function GrowthReport({ child, month: initialMonth, onBack }: Props) {
  const currentMonth = currentReportMonth()
  const currentYear = Number(currentMonth.slice(0, 4))
  const [selectedMonth, setSelectedMonth] = useState(initialMonth)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerYear, setPickerYear] = useState(Number(initialMonth.slice(0, 4)))
  const [pendingMonth, setPendingMonth] = useState(initialMonth)
  const [reloadKey, setReloadKey] = useState(0)
  const [monthCounts, setMonthCounts] = useState<Record<string, number | null>>({})
  const requestedMonths = useRef(new Set<string>())
  const requestKey = `${child.childId}:${selectedMonth}:${reloadKey}`
  const [request, setRequest] = useState<{ key: string; report: MonthlyReport | null; error: string } | null>(null)
  const report = request?.key === requestKey ? request.report : null
  const error = request?.key === requestKey ? request.error : ''
  const loading = request?.key !== requestKey
  const isMock = !import.meta.env.VITE_API_BASE_URL
  const monthNumber = Number(selectedMonth.slice(5))
  const yearNumber = Number(selectedMonth.slice(0, 4))

  useEffect(() => {
    const controller = new AbortController()
    let current = true
    void getMonthlyReport(child.childId, child.name, child.birthDate, selectedMonth, controller.signal)
      .then((result) => {
        if (current) setRequest({ key: requestKey, report: result, error: '' })
      })
      .catch((failure: unknown) => {
        if (!current) return
        const status = failure instanceof ApiRequestError ? failure.status : null
        const message = status === 404 ? '아이 정보를 찾을 수 없어요. 홈으로 돌아가 다시 확인해 주세요.'
          : status === 400 ? '조회 월이 올바르지 않아요. 현재 월 또는 과거 월을 선택해 주세요.'
            : '리포트를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'
        setRequest({ key: requestKey, report: null, error: apiErrorMessage(failure, message) })
      })
    return () => { current = false; controller.abort() }
  }, [child.childId, child.name, child.birthDate, selectedMonth, requestKey])

  // 조회 월 선택창에 월별 위험 물체 감지 건수를 보여주기 위해 선택한 연도의 월별 리포트를 미리 조회한다.
  useEffect(() => {
    if (!pickerOpen) return
    for (let month = 1; month <= 12; month += 1) {
      const key = monthKey(pickerYear, month)
      if (key > currentMonth || requestedMonths.current.has(key)) continue
      requestedMonths.current.add(key)
      getMonthlyReport(child.childId, child.name, child.birthDate, key)
        .then((result) => setMonthCounts((counts) => ({ ...counts, [key]: result.summary.detectionCount })))
        .catch(() => {
          requestedMonths.current.delete(key)
          setMonthCounts((counts) => ({ ...counts, [key]: null }))
        })
    }
  }, [pickerOpen, pickerYear, currentMonth, child.childId, child.name, child.birthDate])

  function openPicker() {
    setPickerYear(yearNumber)
    setPendingMonth(selectedMonth)
    setPickerOpen(true)
  }

  const monthLabel = selectedMonth === currentMonth ? '이번 달' : `${monthNumber}월`
  const totalCount = report?.summary.detectionCount ?? 0
  const stageChanges = report?.stageChanges ?? (report?.stageChange ? [report.stageChange] : [])
  // 변경 이력이 없을 때 보여줄 기준 단계: 이번 달은 오늘, 지난 달은 그 달 말일의 월령으로 계산한다.
  const referenceDate = selectedMonth === currentMonth ? new Date() : new Date(yearNumber, monthNumber, 0)
  const referenceProfile = computeSafetyProfile(child.birthDate, referenceDate)
  const referenceStage = referenceProfile.ageMonths >= 0 ? referenceProfile.stage : null
  let angle = 0
  const donutStops = report?.detectionsByObject.map((item, index) => {
    const start = angle
    angle += totalCount > 0 ? (item.count / totalCount) * 360 : 0
    return `${chartColors[index % chartColors.length]} ${start}deg ${angle}deg`
  }) ?? []

  return (
    <div className="min-h-screen bg-[#f4f6f9] text-[#111827] [zoom:clamp(0.85,calc(100vw/402px),1.4)]">
      <div className="mx-auto min-h-screen max-w-[402px] pb-[40px]">
        <header className="sticky top-0 z-10 flex items-center justify-between gap-2 border-b border-[#eef2f6] bg-[#f4f6f9]/95 px-4 py-[10px] backdrop-blur-md">
          <div className="flex min-w-0 items-center gap-2">
            <button type="button" onClick={onBack} aria-label="홈으로 돌아가기" className="grid shrink-0 size-9 place-items-center focus-visible:outline-[#a50034]"><ArrowLeft size={20} /></button>
            <h1 className="break-keep text-[16px] font-bold leading-[1.25] tracking-[-0.45px]">우리 아이 맞춤 안전 리포트</h1>
          </div>
          <button type="button" onClick={openPicker} className="flex shrink-0 items-center gap-1.5 rounded-full border border-[#e5e7eb] bg-white px-3 py-[7px] text-[12px] font-semibold text-[#374151] shadow-sm focus-visible:outline-[#a50034]">
            <Calendar size={12} aria-hidden="true" />{yearNumber}년 {monthNumber}월<ChevronDown size={11} aria-hidden="true" />
          </button>
        </header>

        <main className="space-y-[14px] px-4 pt-[8px]" aria-busy={loading}>
          {isMock && <p className="rounded-[12px] border border-[#dbeafe] bg-[#eff6ff] px-3 py-2 text-center text-[11px] text-[#1e3a8a]">화면 확인용 예시 리포트입니다. 실제 기기·DB 분석 결과가 아닙니다.</p>}
          {loading ? <p role="status" className="rounded-[20px] bg-white p-6 text-center text-[13px] text-[#64748b]">선택 월의 리포트를 불러오고 있어요.</p>
            : error ? <div role="alert" className="rounded-[20px] border border-[#fecdd3] bg-white p-5 text-[13px] text-[#9f1239]">
              <p>{error}</p>
              <button type="button" onClick={() => setReloadKey((value) => value + 1)} className="mt-3 font-bold underline underline-offset-2 focus-visible:outline-[#a50034]">다시 시도</button>
            </div> : report && <>
              <section aria-label={`${monthLabel} 안전 요약`} className="rounded-[24px] p-5 text-white shadow-[0_8px_20px_rgba(165,0,52,0.22)]" style={{ backgroundImage: 'linear-gradient(92deg, #ca1048 5%, #fb90b0 99%)' }}>
                <div className="flex items-start justify-between">
                  <span className="flex min-w-0 items-center gap-1.5 rounded-full bg-black/20 px-3 py-1 text-[11px] font-semibold"><ShieldCheck size={12} className="shrink-0" aria-hidden="true" /><span className="">{report.isMock ? '화면 예시' : '저장된 위험 기록 집계'}</span></span>
                  {report.isMock && <MonthlyFeedbackButton shapeClassName="size-10 rounded-full border border-white/20" wrapperClassName="shrink-0" idleBgClassName="bg-white/15" iconSize={20} />}
                </div>
                <h2 className="pt-[11px] text-[22px] font-extrabold tracking-[-0.55px]">{report.childName} 님의 {monthNumber}월 리포트</h2>
                <p className="pt-1 text-[12px] leading-[1.6] text-white/85">선택한 월의 성장단계 변화와 위험 물체 감지 기록을 확인해 보세요.</p>
              </section>

              <section aria-label={`${monthLabel} 성장단계`} className="space-y-3 rounded-[24px] border border-[#eef2f6] bg-white p-5 shadow-sm">
                <div className="flex items-center justify-between gap-2">
                  <h2 className="flex min-w-0 items-center gap-2 text-[15px] font-bold"><span className="size-[10px] shrink-0 rounded-full bg-[#2958c7]" aria-hidden="true" />{monthLabel} 성장단계</h2>
                  <span className="shrink-0 rounded-full bg-[#eef3ff] px-3 py-1 text-[11px] font-semibold text-[#2958c7]">
                    {stageChanges.length === 0 ? (referenceProfile.ageMonths < 0 ? '출생 전' : '변화 없음') : stageChanges.some((change) => change.reason === 'AGE_CHANGED') ? '자동 프로필 갱신됨' : '프로필 변경됨'}
                  </span>
                </div>
                <StageChangeTimeline changes={stageChanges} month={selectedMonth} isCurrentMonth={selectedMonth === currentMonth} referenceDate={referenceDate} referenceAgeMonths={referenceProfile.ageMonths} fallbackStage={referenceStage} nextStage={report.nextStagePreview ?? null} birthDate={child.birthDate} />
                <p className="text-[10px] text-[#9ca3af]">{stageChanges.length > 0
                  ? '백엔드에서 변경을 기록한 시각입니다. 생일 경계의 실제 전환 시각이나 기기 적용 완료 시각이 아닙니다.'
                  : `이력 수집 시작 이전의 변경은 포함되지 않습니다. 위 단계는 생년월일로 계산한 ${monthNumber}월 기준 단계입니다.`} 다음 단계 시점은 생년월일 기준 예상 날짜입니다.</p>
              </section>

              <section aria-label={`${monthLabel} 위험물 감지 통계`} className="space-y-3 rounded-[24px] border border-[#eef2f6] bg-white p-5 shadow-sm">
                <div className="flex items-center justify-between">
                  <h2 className="text-[15px] font-bold">{monthLabel} 위험물 감지 통계</h2>
                  <span className="rounded-full bg-[#fef2f2] px-2 py-1 text-[11px] font-bold text-[#a50034]">{totalCount}건</span>
                </div>
                {report.detectionsByObject.length === 0 ? <p className="rounded-[16px] bg-[#f8fafc] p-5 text-center text-[12px] text-[#64748b]">선택한 월에 저장된 위험 탐지 기록이 없습니다.<br />탐지 모델 연동 전에는 자동으로 기록이 쌓이지 않습니다.</p> : <>
                  <div aria-hidden="true" className="relative mx-auto size-24">
                    <div className="size-full rounded-full" style={{ background: `conic-gradient(${donutStops.join(', ')})` }} />
                    <div className="absolute inset-[13px] grid place-items-center rounded-full bg-white text-[13px] font-bold">{totalCount}건</div>
                  </div>
                  <div className="space-y-2">
                    {report.detectionsByObject.map((item, index) => <div key={`${item.objectType}:${item.label}`} className="flex items-center justify-between gap-2 rounded-[12px] border border-[#e5e7eb] bg-[#f8fafc] p-3">
                      <span className="flex min-w-0 items-center gap-2"><span className="size-2 shrink-0 rounded-full" style={{ backgroundColor: chartColors[index % chartColors.length] }} /><span className="text-[12px] font-bold">{item.label}</span></span>
                      <span className="shrink-0 text-[12px]">{item.count}건</span>
                    </div>)}
                  </div>
                </>}
              </section>
            </>}
        </main>
      </div>

      {pickerOpen && <div className="fixed inset-0 z-30 flex items-center justify-center bg-[#0f172a]/45 p-4" onMouseDown={(event) => { if (event.target === event.currentTarget) setPickerOpen(false) }}>
        <div role="dialog" aria-modal="true" aria-labelledby="month-picker-title" className="w-full max-w-[358px] rounded-[24px] bg-white p-5 shadow-2xl">
          <div className="flex items-center justify-between">
            <h2 id="month-picker-title" className="text-[17px] font-bold">조회 월 선택</h2>
            <button type="button" onClick={() => setPickerOpen(false)} aria-label="닫기" className="grid size-7 place-items-center text-[#808080] focus-visible:outline-[#a50034]"><X size={16} /></button>
          </div>
          <div className="mt-4 flex items-center justify-between">
            <button type="button" onClick={() => setPickerYear((year) => Math.max(1, year - 1))} disabled={pickerYear <= 1} aria-label="이전 연도" className="grid size-7 place-items-center disabled:opacity-40"><ChevronLeft size={18} /></button>
            <span className="text-[15px] font-semibold">{pickerYear}년</span>
            <button type="button" onClick={() => setPickerYear((year) => Math.min(year + 1, currentYear))} disabled={pickerYear >= currentYear} aria-label="다음 연도" className="grid size-7 place-items-center disabled:opacity-40"><ChevronRight size={18} /></button>
          </div>
          <div className="mt-3 grid grid-cols-3 gap-2.5">
            {Array.from({ length: 12 }, (_, index) => index + 1).map((month) => {
              const key = monthKey(pickerYear, month)
              const isFuture = key > currentMonth
              const isPending = key === pendingMonth
              return <button key={key} type="button" disabled={isFuture} onClick={() => setPendingMonth(key)} className={`rounded-[17px] border p-2.5 text-center focus-visible:outline-[#a50034] disabled:cursor-not-allowed disabled:opacity-40 ${isPending ? 'border-[#b50031] bg-[#b50031] text-white' : 'border-[#c5c7cb] bg-[#fcfcfd]'}`}>
                <p className="text-[15px] font-bold">{month}월</p>
                <p className="mt-0.5 text-[10px]">{isFuture ? '예정' : monthCounts[key] === undefined ? '불러오는 중' : monthCounts[key] === null ? '조회 실패' : `${monthCounts[key]}건`}</p>
              </button>
            })}
          </div>
          <button type="button" onClick={() => { setSelectedMonth(pendingMonth); setPickerOpen(false) }} className="mt-4 flex min-h-[49px] w-full items-center justify-center rounded-[19px] bg-[#c6002b] text-[16px] font-bold text-white focus-visible:outline-[#a50034]">선택 완료 <ArrowRight size={18} className="ml-1.5" /></button>
        </div>
      </div>}
    </div>
  )
}
