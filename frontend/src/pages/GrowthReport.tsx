import { useState } from 'react'
import { ArrowLeft, ArrowRight, Calendar, CheckCircle2, ChevronDown, ChevronLeft, ChevronRight, Info, ShieldCheck, Sparkles, X } from 'lucide-react'
import MonthlyFeedbackButton from '../components/MonthlyFeedbackButton'
import type { RegisteredChild } from '../services/children'
import { stageByOrder, stageCriteriaDescriptions, stageCriteriaTitles, stageFocusLabels, stageLabels, stageLowerBoundMonths, stageOrder } from '../lib/stages'

interface Props {
  child: RegisteredChild
  month: string
  onBack: () => void
}

const topHazards = [
  { name: '레고 브릭', count: 3, location: '거실', color: '#a50034', badge: '최다' },
  { name: '100원 동전', count: 2, location: '소파 밑', color: '#ea580c', badge: null },
  { name: '작은 자석', count: 1, location: '냉장고 앞', color: '#0369a1', badge: null },
]
const otherHazardCount = 6
const totalHazardCount = topHazards.reduce((sum, item) => sum + item.count, otherHazardCount)

function addMonths(birthDate: string, months: number) {
  const [year, month, day] = birthDate.split('-').map(Number)
  return new Date(year, month - 1 + months, day)
}

function formatMonthDay(date: Date) {
  return `${date.getMonth() + 1}/${date.getDate()}`
}

function monthKey(year: number, monthIndex1: number) {
  return `${year}-${String(monthIndex1).padStart(2, '0')}`
}

function mockAvoidanceCount(key: string) {
  let hash = 0
  for (const char of key) hash = (hash * 31 + char.charCodeAt(0)) % 97
  return (hash % 12) + 5
}

export default function GrowthReport({ child, month: initialMonth, onBack }: Props) {
  const stage = child.safetyProfile.stage
  const today = new Date()
  const currentMonthKey = monthKey(today.getFullYear(), today.getMonth() + 1)

  const [selectedMonth, setSelectedMonth] = useState(initialMonth)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerYear, setPickerYear] = useState(Number(selectedMonth.slice(0, 4)))
  const [pendingMonth, setPendingMonth] = useState(selectedMonth)

  const monthNumber = Number(selectedMonth.slice(5))
  const yearNumber = Number(selectedMonth.slice(0, 4))

  function openPicker() {
    setPickerYear(Number(selectedMonth.slice(0, 4)))
    setPendingMonth(selectedMonth)
    setPickerOpen(true)
  }

  function confirmPicker() {
    setSelectedMonth(pendingMonth)
    setPickerOpen(false)
  }

  const prevStage = stage ? stageByOrder[stageOrder[stage] - 1] ?? null : null
  const nextStage = stage ? stageByOrder[stageOrder[stage] + 1] ?? null : null
  const currentStageStart = stage ? addMonths(child.birthDate, stageLowerBoundMonths[stage]) : null
  const prevStageStart = prevStage ? addMonths(child.birthDate, stageLowerBoundMonths[prevStage]) : null
  const prevStageEnd = currentStageStart ? new Date(currentStageStart.getFullYear(), currentStageStart.getMonth(), currentStageStart.getDate() - 1) : null

  let angle = 0
  const donutStops = [...topHazards, { name: '기타', count: otherHazardCount, color: '#9ca3af' }].map((item) => {
    const start = angle
    angle += (item.count / totalHazardCount) * 360
    return `${item.color} ${start}deg ${angle}deg`
  })

  return (
    <div className="min-h-screen bg-[#f4f6f9] text-[#111827]">
      <div className="mx-auto min-h-screen max-w-[402px] pb-[40px]">
        <header className="sticky top-0 z-10 flex items-center justify-between border-b border-[#eef2f6] bg-[#f4f6f9]/95 px-4 py-[10px] backdrop-blur-md">
          <div className="flex items-center gap-2">
            <button type="button" onClick={onBack} aria-label="홈으로 돌아가기" className="grid size-9 place-items-center focus-visible:outline-[#a50034]"><ArrowLeft size={20} /></button>
            <h1 className="text-[18px] font-bold tracking-[-0.45px]">우리 아이 성장 리포트</h1>
          </div>
          <button type="button" onClick={openPicker} className="flex shrink-0 items-center gap-1.5 rounded-full border border-[#e5e7eb] bg-white px-[13px] py-[7px] text-[12px] font-semibold text-[#374151] shadow-sm focus-visible:outline-[#a50034]">
            <Calendar size={12} aria-hidden="true" />{yearNumber}년 {monthNumber}월<ChevronDown size={11} aria-hidden="true" />
          </button>
        </header>

        <main className="space-y-[14px] px-4 pt-[8px]">
          <div className="flex items-center justify-between px-1">
            <div className="flex items-center gap-1.5">
              <span className="size-[6px] shrink-0 rounded-full bg-[#a50034]" />
              <span className="text-[11px] font-bold tracking-[0.05px] text-gray-600">AI Safety Care × 성장단계 연동 리포트</span>
            </div>
            <span className="text-[11px] text-[#9ca3af]">매월 1일 자동 업데이트</span>
          </div>

          <section aria-label="이번 달 안전 요약" className="rounded-[24px] p-5 text-white shadow-[0_8px_20px_rgba(165,0,52,0.22)]" style={{ backgroundImage: 'linear-gradient(92deg, #ca1048 5%, #fb90b0 99%)' }}>
            <div className="flex items-start justify-between">
              <span className="flex items-center gap-1.5 rounded-full bg-black/20 px-3 py-1 text-[11px] font-semibold backdrop-blur-md"><ShieldCheck size={12} aria-hidden="true" />월간 정기 안전 결산</span>
              <MonthlyFeedbackButton shapeClassName="size-10 rounded-full border border-white/20" idleBgClassName="bg-white/15" iconSize={20} />
            </div>
            <h2 className="pt-[11px] text-[22px] font-extrabold tracking-[-0.55px]">{child.name} 님의 {monthNumber}월 리포트</h2>
            <p className="pb-[13px] pt-1 text-[12.5px] leading-[1.6] text-white/85">
              이번 달, 아이의 성장에 맞춰<br />로봇청소기가 이만큼 더 안전하게 지켜냈어요!
            </p>
            <div className="flex items-center gap-3 rounded-[16px] border border-white/15 bg-white/10 px-[15px] py-[13px] backdrop-blur-md">
              <span className="grid size-10 shrink-0 place-items-center rounded-[12px] border border-white/25 bg-white/20"><ShieldCheck size={20} aria-hidden="true" /></span>
              <div>
                <p className="text-[11px] text-white/85">안전 감지 및 즉시 회피</p>
                <p className="flex items-baseline gap-1.5"><span className="text-[20px] font-extrabold tracking-[-0.5px]">총 {totalHazardCount}건</span><span className="text-[11px] font-semibold text-[#6ee7b7]">(회피율 100%)</span></p>
              </div>
            </div>
          </section>

          <section aria-label="이번 달 성장단계 변화" className="space-y-[14px] rounded-[24px] border border-[#eef2f6] bg-white p-[21px] shadow-[0_2px_6px_rgba(0,0,0,0.03)]">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="size-2 shrink-0 rounded-full bg-[#2563eb]" />
                <h2 className="text-[15px] font-bold tracking-[-0.3px]">이번 달 성장단계 변화</h2>
              </div>
              {prevStage && <span className="shrink-0 rounded-full bg-[#eff6ff] px-[10px] py-[2px] text-[11px] font-semibold text-[#2563eb]">자동 프로필 갱신됨</span>}
            </div>

            {stage && prevStage && prevStageStart && prevStageEnd && currentStageStart ? (
              <div className="space-y-3 rounded-[16px] border border-[#f1f5f9] bg-[#f8fafc]/70 p-[15px]">
                <div className="flex items-center gap-1.5">
                  <div className="flex-1 rounded-[12px] border border-[#e5e7eb] bg-white p-[11px]">
                    <div className="flex items-center justify-between">
                      <span className="text-[10px] text-[#9ca3af]">{formatMonthDay(prevStageStart)} ~ {formatMonthDay(prevStageEnd)}</span>
                      <span className="rounded-[4px] bg-[#f3f4f6] px-[6px] text-[9px] text-[#6b7280]">완료</span>
                    </div>
                    <p className="pt-0.5 text-[12px] font-bold text-[#374151]">{stageLabels[prevStage]}</p>
                    <p className="text-[10px] text-[#9ca3af]">{stageFocusLabels[prevStage]}</p>
                  </div>
                  <ArrowRight size={16} className="shrink-0 text-[#9ca3af]" aria-hidden="true" />
                  <div className="relative flex-1 rounded-[12px] border-2 border-[#2563eb] bg-[#eff6ff]/30 p-3">
                    <span className="absolute -top-2.5 right-2 rounded-full bg-[#2563eb] px-2 py-[0.5px] text-[9px] font-bold text-white">현재 적용</span>
                    <span className="text-[10px] font-bold text-[#2563eb]">{formatMonthDay(currentStageStart)} ~ 현재</span>
                    <p className="pt-0.5 text-[12px] font-extrabold text-[#172554]">{stageLabels[stage]}</p>
                    <p className="text-[10px] text-[#1d4ed8]/80">{stageFocusLabels[stage]}</p>
                  </div>
                </div>
                <div className="flex items-start gap-2 rounded-[12px] border border-[#dbeafe]/60 bg-[#eff6ff]/80 p-[11px]">
                  <Info size={14} className="mt-0.5 shrink-0 text-[#1e3a8a]" aria-hidden="true" />
                  <p className="text-[11.5px] leading-[1.5] text-[#1e3a8a]">
                    {formatMonthDay(currentStageStart)}, 아이가 생후 {stageLowerBoundMonths[stage]}개월을 맞아 로봇청소기의{' '}
                    <span className="text-[#a50034]">Safety Profile이 {stageLabels[stage]}로 자동 전환</span>되었습니다.
                  </p>
                </div>
              </div>
            ) : stage ? (
              <div className="rounded-[16px] border-2 border-[#2563eb] bg-[#eff6ff]/30 p-[15px]">
                <span className="text-[10px] font-bold text-[#2563eb]">현재 적용 중</span>
                <p className="pt-0.5 text-[13px] font-extrabold text-[#172554]">{stageLabels[stage]}</p>
                <p className="text-[11px] text-[#1d4ed8]/80">{stageFocusLabels[stage]}</p>
              </div>
            ) : (
              <p className="rounded-[16px] border border-[#f1f5f9] bg-[#f8fafc]/70 p-[15px] text-[12px] text-[#64748b]">현재 지원하는 성장 단계 범위를 벗어났어요.</p>
            )}
          </section>

          <section aria-label="이번 달 위험물 감지 통계" className="space-y-[10px] rounded-[24px] border border-[#eef2f6] bg-white p-[21px] shadow-[0_2px_6px_rgba(0,0,0,0.03)]">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="size-2 shrink-0 rounded-full bg-[#a50034]" />
                <h2 className="text-[15px] font-bold tracking-[-0.3px]">이번 달 위험물 감지 통계</h2>
              </div>
              <span className="shrink-0 rounded-full bg-[#fef2f2] px-[10px] py-[2px] text-[11px] font-bold text-[#a50034]">사전 감지 {totalHazardCount}건</span>
            </div>
            <div className="flex items-center gap-3.5 rounded-[16px] border border-[#f1f5f9] bg-[#f8fafc]/70 px-[13px] py-[17px]">
              <div className="relative size-24 shrink-0">
                <div className="size-full rounded-full" style={{ background: `conic-gradient(${donutStops.join(', ')})` }} />
                <div className="absolute inset-[13px] grid place-items-center rounded-full bg-[#f8fafc] text-center">
                  <span className="text-[9px] text-[#9ca3af]">위험 완구</span>
                  <span className="text-[12px] font-extrabold text-[#111827]">TOP 1</span>
                </div>
              </div>
              <div className="flex-1 space-y-1.5">
                {topHazards.map((item) => (
                  <div key={item.name} className="flex items-center justify-between rounded-[12px] border border-[#e5e7eb]/80 bg-white px-[11px] py-[7px]">
                    <span className="flex items-center gap-1.5">
                      <span className="size-2 shrink-0 rounded-full" style={{ backgroundColor: item.color }} />
                      <span className="text-[11.5px] font-bold text-[#111827]">{item.name}</span>
                      {item.badge && <span className="rounded-[4px] bg-[#fef2f2] px-1 text-[9px] font-bold text-[#a50034]">{item.badge}</span>}
                    </span>
                    <span className="text-[12px]"><strong className="font-bold text-[#111827]">{item.count}건</strong> <span className="text-[10px] text-[#9ca3af]">({item.location})</span></span>
                  </div>
                ))}
              </div>
            </div>
          </section>

          {stage && (
            <section aria-label="이번 전환으로 달라진 점" className="space-y-[14px] rounded-[24px] border border-[#eef2f6] bg-white p-[21px] shadow-[0_2px_6px_rgba(0,0,0,0.03)]">
              <div className="flex items-center gap-2">
                <span className="size-2 shrink-0 rounded-full bg-[#a50034]" />
                <h2 className="text-[15px] font-bold tracking-[-0.3px]">이번 전환으로 달라진 점</h2>
              </div>
              <div className="space-y-2.5">
                <div className="flex items-start gap-3 rounded-[16px] border border-[#e5e7eb] bg-[#f8fafc]/80 p-[13px]">
                  <span className="grid size-7 shrink-0 place-items-center rounded-full bg-[#d1fae5]/90"><CheckCircle2 size={14} className="text-[#059669]" aria-hidden="true" /></span>
                  <div>
                    <p className="text-[12px] font-bold leading-[1.4] text-[#111827]">{stageCriteriaTitles[stage]}가 새로 활성화됐어요</p>
                    <p className="pt-0.5 text-[11px] leading-[1.6] text-[#6b7280]">{stageCriteriaDescriptions[stage]}</p>
                  </div>
                </div>
                <div className="flex items-start gap-3 rounded-[16px] border border-[#e5e7eb] bg-[#f8fafc]/80 p-[13px]">
                  <span className="grid size-7 shrink-0 place-items-center rounded-full bg-[#d1fae5]/90"><CheckCircle2 size={14} className="text-[#059669]" aria-hidden="true" /></span>
                  <div>
                    <p className="text-[12px] font-bold leading-[1.4] text-[#111827]">삼킴 위험물 탐지 우선순위가 조정됐어요</p>
                    <p className="pt-0.5 text-[11px] leading-[1.6] text-[#6b7280]">성장 단계에 맞춰 집중 스캔 대상과 범위가 자동으로 재조정되었습니다.</p>
                  </div>
                </div>
              </div>
            </section>
          )}

          <section aria-label="다음 성장 단계 예고" className="rounded-[24px] border-2 border-dashed border-[#e2e8f0] bg-[#f8fafc]/50 p-[18px]">
            <div className="flex items-start gap-3">
              <span className="grid size-8 shrink-0 place-items-center rounded-full border border-[#e5e7eb] bg-white"><Sparkles size={14} className="text-[#9ca3af]" aria-hidden="true" /></span>
              <div className="space-y-0.5">
                <p className="text-[10px] font-bold tracking-[0.5px] text-[#9ca3af]">NEXT STAGE PREVIEW</p>
                {nextStage ? (
                  <>
                    <p className="text-[12px] font-bold text-[#1f2937]">다음 예정: {stageLabels[nextStage]}로 자동 전환돼요</p>
                    <p className="pt-0.5 text-[11px] leading-[1.6] text-[#6b7280]">그때는 <span className="font-semibold text-[#374151]">{stageCriteriaDescriptions[nextStage]}</span>이 시작돼요.</p>
                  </>
                ) : (
                  <p className="text-[12px] font-bold text-[#1f2937]">현재 지원되는 마지막 성장 단계를 적용하고 있어요</p>
                )}
              </div>
            </div>
          </section>

          <p className="py-2 text-center text-[11px] text-[#9ca3af]">LG ThinQ AI 센서 기록을 바탕으로 매일 밤 동기화됩니다</p>
        </main>
      </div>

      {pickerOpen && (
        <div className="fixed inset-0 z-30 flex items-center justify-center bg-[#0f172a]/45 p-4" onMouseDown={(event) => { if (event.target === event.currentTarget) setPickerOpen(false) }}>
          <div role="dialog" aria-modal="true" aria-labelledby="month-picker-title" className="w-full max-w-[358px] rounded-[24px] bg-white p-5 shadow-2xl">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-1.5">
                <h2 id="month-picker-title" className="text-[17px] font-bold text-[#0f172a]">조회 월 선택</h2>
                <span className="rounded-full bg-[#fff1f1] px-2 py-[2px] text-[11px] font-semibold text-[#be1845]">성장 리포트</span>
              </div>
              <button type="button" onClick={() => setPickerOpen(false)} aria-label="닫기" className="grid size-7 place-items-center text-[#808080] focus-visible:outline-[#a50034]"><X size={16} /></button>
            </div>

            <div className="mt-4 flex items-center justify-between px-1">
              <button type="button" onClick={() => setPickerYear((year) => year - 1)} aria-label="이전 연도" className="grid size-7 place-items-center rounded-full text-[#474747] hover:bg-[#f3f4f6] focus-visible:outline-[#a50034]"><ChevronLeft size={18} /></button>
              <span className="text-[15px] font-semibold text-[#111827]">{pickerYear}년</span>
              <button type="button" onClick={() => setPickerYear((year) => Math.min(year + 1, today.getFullYear()))} disabled={pickerYear >= today.getFullYear()} aria-label="다음 연도" className="grid size-7 place-items-center rounded-full text-[#afafaf] hover:bg-[#f3f4f6] disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline-[#a50034]"><ChevronRight size={18} /></button>
            </div>

            <div className="mt-3 grid grid-cols-3 gap-2.5">
              {Array.from({ length: 12 }, (_, index) => index + 1).map((monthIndex) => {
                const key = monthKey(pickerYear, monthIndex)
                const isFuture = key > currentMonthKey
                const isCurrentRealMonth = key === currentMonthKey
                const isPending = key === pendingMonth
                return (
                  <button
                    key={key}
                    type="button"
                    disabled={isFuture}
                    onClick={() => setPendingMonth(key)}
                    className={`rounded-[17px] border p-2.5 text-center disabled:cursor-not-allowed ${isPending ? 'border-[#b50031] bg-[#b50031] shadow-md' : isFuture ? 'border-[#eaeaea] bg-[#fcfcfd]' : 'border-[#c5c7cb] bg-[#fcfcfd] hover:border-[#b50031]'}`}
                  >
                    <p className={`text-[15px] font-bold ${isPending ? 'text-white' : isFuture ? 'text-[#c2c2c2]' : 'text-[#111827]'}`}>{monthIndex}월</p>
                    <p className={`mt-0.5 text-[10px] font-bold ${isPending ? 'text-white' : isFuture ? 'text-[#c6c6c6]' : 'text-[#a1a8b4]'}`}>
                      {isFuture ? '예정' : isCurrentRealMonth ? `${mockAvoidanceCount(key)}건 (현재)` : `${mockAvoidanceCount(key)}건 회피`}
                    </p>
                  </button>
                )
              })}
            </div>

            <button type="button" onClick={confirmPicker} className="mt-4 flex h-[49px] w-full items-center justify-center rounded-[19px] bg-[#c6002b] text-[16px] font-bold text-white shadow-[0_4px_4px_rgba(0,0,0,0.25)] focus-visible:outline-[#a50034]">
              선택 완료 <ArrowRight size={18} className="ml-1.5" />
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
