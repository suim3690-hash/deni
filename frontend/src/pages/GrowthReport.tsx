import { useEffect, useState } from 'react'
import { ArrowLeft, ArrowRight, Calendar, ChevronDown, ChevronLeft, ChevronRight, Info, ShieldCheck, Sparkles, X } from 'lucide-react'
import MonthlyFeedbackButton from '../components/MonthlyFeedbackButton'
import type { RegisteredChild } from '../services/children'
import { currentReportMonth, getMonthlyReport, MonthlyReportRequestError, type MonthlyReport } from '../services/reports'
import { stageLabels } from '../lib/stages'

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
    void getMonthlyReport(child.childId, child.name, selectedMonth, controller.signal)
      .then((result) => {
        if (current) setRequest({ key: requestKey, report: result, error: '' })
      })
      .catch((failure: unknown) => {
        if (!current) return
        const status = failure instanceof MonthlyReportRequestError ? failure.status : null
        const message = status === 404 ? '아이 정보를 찾을 수 없어요. 홈으로 돌아가 다시 확인해 주세요.'
          : status === 400 ? '조회 월이 올바르지 않아요. 현재 월 또는 과거 월을 선택해 주세요.'
            : '리포트를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'
        setRequest({ key: requestKey, report: null, error: message })
      })
    return () => { current = false; controller.abort() }
  }, [child.childId, child.name, selectedMonth, requestKey])

  function openPicker() {
    setPickerYear(yearNumber)
    setPendingMonth(selectedMonth)
    setPickerOpen(true)
  }

  const totalCount = report?.summary.detectionCount ?? 0
  const stageChanges = report?.stageChanges ?? (report?.stageChange ? [report.stageChange] : [])
  let angle = 0
  const donutStops = report?.detectionsByObject.map((item, index) => {
    const start = angle
    angle += totalCount > 0 ? (item.count / totalCount) * 360 : 0
    return `${chartColors[index % chartColors.length]} ${start}deg ${angle}deg`
  }) ?? []

  return (
    <div className="min-h-screen bg-[#f4f6f9] text-[#111827] [zoom:max(0.85,calc(100vw/402px))]">
      <div className="mx-auto min-h-screen max-w-[402px] pb-[40px]">
        <header className="sticky top-0 z-10 flex items-center justify-between border-b border-[#eef2f6] bg-[#f4f6f9]/95 px-4 py-[10px] backdrop-blur-md">
          <div className="flex items-center gap-2">
            <button type="button" onClick={onBack} aria-label="홈으로 돌아가기" className="grid size-9 place-items-center focus-visible:outline-[#a50034]"><ArrowLeft size={20} /></button>
            <h1 className="text-[18px] font-bold tracking-[-0.45px]">우리 아이 성장 리포트</h1>
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
              <section aria-label="이번 달 안전 요약" className="rounded-[24px] p-5 text-white shadow-[0_8px_20px_rgba(165,0,52,0.22)]" style={{ backgroundImage: 'linear-gradient(92deg, #ca1048 5%, #fb90b0 99%)' }}>
                <div className="flex items-start justify-between">
                  <span className="flex items-center gap-1.5 rounded-full bg-black/20 px-3 py-1 text-[11px] font-semibold"><ShieldCheck size={12} aria-hidden="true" />{report.isMock ? '화면 예시' : '저장된 위험 기록 집계'}</span>
                  {report.isMock && <MonthlyFeedbackButton shapeClassName="size-10 rounded-full border border-white/20" idleBgClassName="bg-white/15" iconSize={20} />}
                </div>
                <h2 className="pt-[11px] text-[22px] font-extrabold tracking-[-0.55px]">{report.childName} 님의 {monthNumber}월 리포트</h2>
                <p className="pb-[13px] pt-1 text-[12px] leading-[1.6] text-white/85">선택한 월에 저장된 탐지 기록을 확인해 보세요.<br />탐지 건수는 실제 안전 처리 완료 건수가 아닙니다.</p>
                <div className="rounded-[16px] border border-white/15 bg-white/10 px-[15px] py-[13px]">
                  <p className="text-[11px] text-white/85">위험물 탐지</p>
                  <p className="text-[20px] font-extrabold">총 {totalCount}건</p>
                  {report.summary.avoidanceRatePercent !== null && <p className="mt-1 text-[12px]">회피율 {report.summary.avoidanceRatePercent}%</p>}
                  {report.summary.safeCleanedAreaSquareMeters !== null && <p className="mt-1 text-[12px]">안전 청소 면적 {report.summary.safeCleanedAreaSquareMeters}㎡</p>}
                </div>
              </section>

              <section aria-label="이번 달 성장단계 변화" className="space-y-3 rounded-[24px] border border-[#eef2f6] bg-white p-5 shadow-sm">
                <h2 className="text-[15px] font-bold">이번 달 성장단계 변화</h2>
                {stageChanges.length > 0
                  ? stageChanges.map((change, index) => <div key={`${change.changedAt}:${index}`} className="rounded-[16px] bg-[#eff6ff] p-4 text-[12px]">
                    <p className="font-bold">{change.from ? stageLabels[change.from] : '지원 범위 밖'} → {change.to ? stageLabels[change.to] : '지원 범위 밖'}</p>
                    <p className="mt-1">{new Date(change.changedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} · {change.reason === 'BIRTH_DATE_UPDATED' ? '생년월일 수정' : '월령 기준 갱신'}</p>
                  </div>) : <p className="rounded-[16px] bg-[#f8fafc] p-4 text-[12px] text-[#64748b]">선택한 월에 저장된 성장단계 변경 이력이 없습니다. 이력 수집 시작 이전의 변경은 포함되지 않습니다.</p>}
                <p className="text-[10px] text-[#9ca3af]">백엔드에서 변경을 기록한 시각입니다. 생일 경계의 실제 전환 시각이나 기기 적용 완료 시각이 아닙니다.</p>
              </section>

              <section aria-label="이번 달 위험물 감지 통계" className="space-y-3 rounded-[24px] border border-[#eef2f6] bg-white p-5 shadow-sm">
                <div className="flex items-center justify-between">
                  <h2 className="text-[15px] font-bold">이번 달 위험물 감지 통계</h2>
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

              <section aria-label="안전 기준 변경" className="space-y-3 rounded-[24px] border border-[#eef2f6] bg-white p-5 shadow-sm">
                <h2 className="text-[15px] font-bold">안전 기준 변경</h2>
                {report.criteriaChanges.length > 0 ? report.criteriaChanges.map((change, index) => <div key={`${change.title}:${index}`} className="rounded-[16px] bg-[#f8fafc] p-4">
                  <p className="text-[12px] font-bold">{change.title}</p><p className="mt-1 text-[11px] text-[#64748b]">{change.description}</p>
                  <p className="mt-2 text-[10px] text-[#9ca3af]">{new Date(change.changedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} 기록</p>
                </div>) : <p className="text-[12px] text-[#64748b]">선택한 월에 저장된 기준 변경 이력이 없습니다. 현재 프로필을 과거 적용 이력으로 표시하지 않습니다.</p>}
                <p className="text-[10px] text-[#9ca3af]">기록 당시 백엔드 프로필의 기준 문구이며 실제 기기 적용 확인 결과가 아닙니다.</p>
              </section>

              <section aria-label="다음 성장 단계 예고" className="rounded-[24px] border-2 border-dashed border-[#e2e8f0] bg-[#f8fafc] p-[18px]">
                <div className="flex items-start gap-3">
                  <Sparkles size={18} className="shrink-0 text-[#9ca3af]" aria-hidden="true" />
                  <div>
                    <h2 className="text-[13px] font-bold">{report.nextStagePreview.stage ? `다음 단계 안내: ${stageLabels[report.nextStagePreview.stage]}` : '다음 성장 단계 안내'}</h2>
                    <p className="mt-1 text-[12px] leading-[1.6] text-[#64748b]">{report.nextStagePreview.description}</p>
                    {!report.isMock && <p className="mt-2 text-[10px] text-[#9ca3af]">현재 등록된 생년월일 기준 안내입니다. 과거 월은 월말, 이번 달은 오늘 월령을 사용하며 실제 기기 적용 이력이 아닙니다.</p>}
                  </div>
                </div>
              </section>
              {!report.isMock && <div className="flex items-start gap-2 rounded-[16px] bg-[#eff6ff] p-3 text-[11px] leading-[1.6] text-[#334155]">
                <Info size={16} className="mt-0.5 shrink-0" aria-hidden="true" /><p>회피율·안전 청소 면적은 원천 데이터와 산식이 확정되지 않아 표시하지 않습니다. 리포트 평가는 아직 저장하지 않습니다.</p>
              </div>}
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
                <p className="mt-0.5 text-[10px]">{isFuture ? '예정' : key === currentMonth ? '현재 월' : '조회 가능'}</p>
              </button>
            })}
          </div>
          <button type="button" onClick={() => { setSelectedMonth(pendingMonth); setPickerOpen(false) }} className="mt-4 flex h-[49px] w-full items-center justify-center rounded-[19px] bg-[#c6002b] text-[16px] font-bold text-white focus-visible:outline-[#a50034]">선택 완료 <ArrowRight size={18} className="ml-1.5" /></button>
        </div>
      </div>}
    </div>
  )
}
