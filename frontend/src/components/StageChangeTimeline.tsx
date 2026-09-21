import { ArrowRight } from 'lucide-react'
import type { MonthlyReport, ProfileStageChange } from '../services/reports'
import { dateReachingAgeMonths } from '../services/children'
import { stageFocusLabels, stageLabels, stageLowerBoundMonths, type Stage } from '../lib/stages'

interface Props {
  changes: ProfileStageChange[]
  month: string
  isCurrentMonth: boolean
  referenceDate: Date
  referenceAgeMonths: number
  fallbackStage: Stage | null
  nextStage: MonthlyReport['nextStagePreview'] | null
  birthDate: string
}

const OUT_OF_RANGE = '지원 범위 밖'
const DAY_MS = 24 * 60 * 60 * 1000

function stageName(stage: Stage | null) {
  return stage ? stageLabels[stage] : OUT_OF_RANGE
}

// 받침이 없거나 ㄹ 받침이면 "로", 그 밖의 받침이면 "으로"
function directionParticle(word: string) {
  const code = word.trim().charCodeAt(word.trim().length - 1)
  const isHangul = code >= 0xac00 && code <= 0xd7a3
  const finalConsonant = (code - 0xac00) % 28
  return isHangul && finalConsonant !== 0 && finalConsonant !== 8 ? '으로' : '로'
}

function kstDate(iso: string) {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' }).formatToParts(new Date(iso))
  const read = (type: string) => Number(parts.find((part) => part.type === type)?.value)
  return { month: read('month'), day: read('day') }
}

function startOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

function shortDate(date: Date, reference: Date) {
  return date.getFullYear() === reference.getFullYear()
    ? `${date.getMonth() + 1}/${date.getDate()}`
    : `${String(date.getFullYear()).slice(2)}.${date.getMonth() + 1}.${date.getDate()}`
}

function StageCard({ stage, range, badge, active, title, focus }: {
  stage: Stage | null; range: string; badge: string; active: boolean; title?: string; focus?: string
}) {
  return (
    <div className={`relative min-w-0 flex-1 rounded-[14px] px-3 pb-3 pt-4 ${active ? 'border-2 border-[#2958c7] bg-[#f5f8ff]' : 'border border-[#d8dee8] bg-white'}`}>
      {active && <span className="absolute -top-2.5 right-3 rounded-full bg-[#2958c7] px-2 py-0.5 text-[10px] font-bold text-white">{badge}</span>}
      <div className="flex items-center justify-between gap-1">
        <p className={`text-[11px] ${active ? 'font-bold text-[#2958c7]' : 'text-[#94a3b8]'}`}>{range}</p>
        {!active && <span className="shrink-0 rounded-full bg-[#eef0f4] px-2 py-0.5 text-[10px] font-semibold text-[#64748b]">{badge}</span>}
      </div>
      <p className={`mt-1.5 break-keep text-[15px] font-extrabold leading-tight ${active ? 'text-[#1e3a8a]' : 'text-[#334155]'}`}>{title ?? stageName(stage)}</p>
      <p className={`mt-1 break-keep text-[11px] leading-[1.35] ${active ? 'text-[#2958c7]' : 'text-[#94a3b8]'}`}>{focus ?? (stage ? stageFocusLabels[stage] : 'Safety Profile 미적용')}</p>
    </div>
  )
}

function InfoBox({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex gap-2 rounded-[14px] border border-[#dbe8fb] bg-[#eef5ff] p-3 text-[12px] leading-[1.6] text-[#334155]">
      <span aria-hidden="true" className="mt-0.5 grid size-4 shrink-0 place-items-center rounded-full bg-[#2958c7] text-[10px] font-bold text-white">i</span>
      <p className="min-w-0 break-keep">{children}</p>
    </div>
  )
}

function ChangeMessage({ change, month, day }: { change: ProfileStageChange; month: number; day: number }) {
  const to = stageName(change.to)
  const particle = directionParticle(to)
  const date = <strong className="text-[#1e3a8a]">{month}월 {day}일</strong>
  if (change.reason === 'BIRTH_DATE_UPDATED') {
    return <>{date}, 생년월일이 수정되어 Safety Profile이 <strong className="text-[#a50034]">{stageName(change.from)}에서 {to}{particle}</strong> 변경되었습니다.</>
  }
  const months = change.to ? stageLowerBoundMonths[change.to] : 96
  return <>{date}, 아이가 생후 {months}개월을 맞아 로봇청소기의 <strong className="text-[#a50034]">Safety Profile이 {to}{particle} 자동 전환</strong>되었습니다.</>
}

// 조회 월의 현재 성장단계와 다음 단계(전환 예정일·D-day)를 보여준다. 그 달에 단계가 바뀌었다면 전환 안내를 함께 보여준다.
export default function StageChangeTimeline({ changes, month, isCurrentMonth, referenceDate, referenceAgeMonths, fallbackStage, nextStage, birthDate }: Props) {
  const monthNumber = Number(month.slice(5))
  const lastDay = new Date(Number(month.slice(0, 4)), monthNumber, 0).getDate()
  const periodEnd = isCurrentMonth ? '현재' : `${monthNumber}/${lastDay}`
  const activeBadge = isCurrentMonth ? '현재 적용' : '월말 적용'
  const lastChange = changes.length > 0 ? changes[changes.length - 1] : null
  const [birthYear, birthMonth, birthDay] = birthDate.split('-').map(Number)
  const born = new Date(birthYear, birthMonth - 1, birthDay)

  // 조회 월 기준일에 아직 태어나기 전이면, 출생 전 카드 → 첫 성장단계(출생일) 카드로 같은 형태를 유지한다.
  if (!lastChange && referenceAgeMonths < 0) {
    const daysToBirth = Math.max(Math.round((startOfDay(born) - startOfDay(referenceDate)) / DAY_MS), 0)
    const firstStage: Stage = 'INFANT'
    const firstName = stageLabels[firstStage]
    return (
      <div className="space-y-3 rounded-[20px] border border-[#e6eefc] bg-[#f8fbff] p-3.5">
        <div className="flex items-stretch gap-2">
          <StageCard stage={null} title="출생 전" focus="Safety Profile 적용 전" range={`${monthNumber}/1 ~ ${periodEnd}`} badge="출생 전" active />
          <ArrowRight size={16} className="shrink-0 self-center text-[#2958c7]" aria-hidden="true" />
          <StageCard stage={firstStage} range={`${shortDate(born, referenceDate)} ~`} badge={daysToBirth === 0 ? 'D-Day' : `D-${daysToBirth}`} active={false} />
        </div>
        <InfoBox>
          등록된 생년월일은 <strong className="text-[#1e3a8a]">{birthYear}년 {birthMonth}월 {birthDay}일</strong>이에요. {isCurrentMonth ? '' : `${monthNumber}월 말 기준 `}아직 태어나기 전이라 Safety Profile이 적용되기 전입니다. 출생일부터 <strong className="text-[#a50034]">{firstName}{directionParticle(firstName)} 적용</strong>되며, 출생까지 <strong className="text-[#1e3a8a]">{daysToBirth}일</strong> 남았어요.
        </InfoBox>
      </div>
    )
  }

  const changedAt = lastChange ? kstDate(lastChange.changedAt) : null
  const currentStage = lastChange ? lastChange.to : fallbackStage
  const isBirthMonth = birthYear === Number(month.slice(0, 4)) && birthMonth === monthNumber
  const currentRange = changedAt ? `${changedAt.month}/${changedAt.day} ~ ${periodEnd}` : `${monthNumber}/${isBirthMonth ? birthDay : 1} ~ ${periodEnd}`

  const next = nextStage?.stage ?? null
  const nextMonths = next ? stageLowerBoundMonths[next] : 0
  const nextDate = next ? dateReachingAgeMonths(birthDate, nextMonths) : null
  const daysLeft = nextDate ? Math.max(Math.round((startOfDay(nextDate) - startOfDay(referenceDate)) / DAY_MS), 0) : 0
  const nextName = next ? stageLabels[next] : ''
  const nextRange = nextDate ? `${shortDate(nextDate, referenceDate)} ~` : ''

  return (
    <div className="space-y-3 rounded-[20px] border border-[#e6eefc] bg-[#f8fbff] p-3.5">
      <div className="flex items-stretch gap-2">
        <StageCard stage={currentStage} range={currentRange} badge={activeBadge} active />
        {next && nextDate && (
          <>
            <ArrowRight size={16} className="shrink-0 self-center text-[#2958c7]" aria-hidden="true" />
            <StageCard stage={next} range={nextRange} badge={daysLeft === 0 ? 'D-Day' : `D-${daysLeft}`} active={false} />
          </>
        )}
      </div>
      {lastChange && changedAt && <InfoBox><ChangeMessage change={lastChange} month={changedAt.month} day={changedAt.day} /></InfoBox>}
      {next && nextDate ? (
        <InfoBox>
          <strong className="text-[#1e3a8a]">{nextDate.getFullYear()}년 {nextDate.getMonth() + 1}월 {nextDate.getDate()}일</strong>, 아이가 생후 {nextMonths}개월을 맞아 로봇청소기의 <strong className="text-[#a50034]">Safety Profile이 {nextName}{directionParticle(nextName)} 자동 전환</strong>될 예정입니다. {isCurrentMonth ? '' : `${monthNumber}월 말 기준 `}전환까지 <strong className="text-[#1e3a8a]">{daysLeft}일</strong> 남았어요.
        </InfoBox>
      ) : nextStage && <InfoBox>{nextStage.description}</InfoBox>}
    </div>
  )
}
