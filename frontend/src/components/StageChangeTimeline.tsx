import { ArrowRight } from 'lucide-react'
import type { ProfileStageChange } from '../services/reports'
import { stageFocusLabels, stageLabels, stageLowerBoundMonths, type Stage } from '../lib/stages'

interface Props {
  changes: ProfileStageChange[]
  month: string
  isCurrentMonth: boolean
  fallbackStage: Stage | null
  ageMonths: number
}

const OUT_OF_RANGE = '지원 범위 밖'

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

function StageCard({ stage, range, badge, active }: { stage: Stage | null; range: string; badge: string; active: boolean }) {
  return (
    <div className={`relative min-w-0 flex-1 rounded-[14px] px-3 pb-3 pt-4 ${active ? 'border-2 border-[#2958c7] bg-[#f5f8ff]' : 'border border-[#d8dee8] bg-white'}`}>
      {active && <span className="absolute -top-2.5 right-3 rounded-full bg-[#2958c7] px-2 py-0.5 text-[10px] font-bold text-white">{badge}</span>}
      <div className="flex items-center justify-between gap-1">
        <p className={`text-[11px] ${active ? 'font-bold text-[#2958c7]' : 'text-[#94a3b8]'}`}>{range}</p>
        {!active && <span className="shrink-0 rounded-full bg-[#eef0f4] px-2 py-0.5 text-[10px] font-semibold text-[#64748b]">{badge}</span>}
      </div>
      <p className={`mt-1.5 break-keep text-[15px] font-extrabold leading-tight ${active ? 'text-[#1e3a8a]' : 'text-[#334155]'}`}>{stageName(stage)}</p>
      <p className={`mt-1 break-keep text-[11px] leading-[1.35] ${active ? 'text-[#2958c7]' : 'text-[#94a3b8]'}`}>{stage ? stageFocusLabels[stage] : 'Safety Profile 미적용'}</p>
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

export default function StageChangeTimeline({ changes, month, isCurrentMonth, fallbackStage, ageMonths }: Props) {
  const monthNumber = Number(month.slice(5))
  const lastDay = new Date(Number(month.slice(0, 4)), monthNumber, 0).getDate()
  const periodEnd = isCurrentMonth ? '현재' : `${monthNumber}/${lastDay}`
  const activeBadge = isCurrentMonth ? '현재 적용' : '월말 적용'

  if (changes.length === 0 && !fallbackStage) {
    return <p className="rounded-[16px] bg-[#f8fafc] p-4 text-[12px] text-[#64748b]">선택한 월에 저장된 성장단계 변경 이력이 없습니다.</p>
  }

  return (
    <div className="space-y-3 rounded-[20px] border border-[#e6eefc] bg-[#f8fbff] p-3.5">
      {changes.length > 0 ? changes.map((change, index) => {
        const at = kstDate(change.changedAt)
        const previous = index > 0 ? kstDate(changes[index - 1].changedAt) : null
        const next = index < changes.length - 1 ? kstDate(changes[index + 1].changedAt) : null
        const isLast = index === changes.length - 1
        const fromStart = previous ? `${previous.month}/${previous.day}` : `${monthNumber}/1`
        const fromRange = at.day > 1 ? `${fromStart} ~ ${at.month}/${at.day - 1}` : '이전 달까지'
        const toEnd = next ? (next.day > 1 ? `${next.month}/${next.day - 1}` : `${at.month}/${at.day}`) : periodEnd
        return (
          <div key={`${change.changedAt}:${index}`} className="space-y-3">
            <div className="flex items-stretch gap-2">
              <StageCard stage={change.from} range={fromRange} badge="완료" active={false} />
              <ArrowRight size={16} className="shrink-0 self-center text-[#2958c7]" aria-hidden="true" />
              <StageCard stage={change.to} range={`${at.month}/${at.day} ~ ${toEnd}`} badge={isLast ? activeBadge : '완료'} active={isLast} />
            </div>
            <InfoBox><ChangeMessage change={change} month={at.month} day={at.day} /></InfoBox>
          </div>
        )
      }) : (
        <>
          <div className="flex">
            <StageCard stage={fallbackStage} range={`${monthNumber}/1 ~ ${periodEnd}`} badge={activeBadge} active />
          </div>
          <InfoBox>
            아이가 생후 {ageMonths}개월이어서 로봇청소기의 <strong className="text-[#a50034]">Safety Profile이 {stageName(fallbackStage)} 그대로 유지</strong>되었습니다. {monthNumber}월에는 성장단계 변화가 없습니다.
          </InfoBox>
        </>
      )}
    </div>
  )
}
