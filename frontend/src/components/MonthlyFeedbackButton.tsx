import { useState } from 'react'
import { Frown, Meh, Smile } from 'lucide-react'

const feedbackOptions = [
  { key: 'good', label: '좋아요', Icon: Smile, ring: 'bg-[#dcfce7]', tone: 'text-[#16a34a]' },
  { key: 'neutral', label: '보통이에요', Icon: Meh, ring: 'bg-[#fef9c3]', tone: 'text-[#ca8a04]' },
  { key: 'bad', label: '아쉬워요', Icon: Frown, ring: 'bg-[#fee2e2]', tone: 'text-[#dc2626]' },
] as const

type FeedbackKey = (typeof feedbackOptions)[number]['key']

interface Props {
  shapeClassName: string
  wrapperClassName?: string
  idleBgClassName?: string
  iconSize?: number
}

export default function MonthlyFeedbackButton({ shapeClassName, wrapperClassName = '', idleBgClassName = 'bg-white/20', iconSize = 22 }: Props) {
  const [feedback, setFeedback] = useState<FeedbackKey | null>(null)
  const [open, setOpen] = useState(false)
  const selected = feedbackOptions.find((option) => option.key === feedback) ?? null

  return (
    <div className={`relative ${wrapperClassName}`}>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label="이번 달 활동 리포트 평가하기"
        className={`grid place-items-center focus-visible:outline-white ${shapeClassName} ${selected ? selected.ring : idleBgClassName}`}
      >
        {selected ? <selected.Icon size={iconSize} className={selected.tone} aria-hidden="true" /> : <Smile size={iconSize} aria-hidden="true" />}
      </button>

      {open && (
        <>
          <button type="button" aria-label="평가 닫기" onClick={() => setOpen(false)} className="fixed inset-0 z-10 cursor-default" />
          <div className="absolute right-0 top-[calc(100%+8px)] z-20 w-[236px] rounded-[18px] bg-white p-4 text-[#0f172a] shadow-[0_10px_30px_rgba(15,23,42,0.18)]">
            <span className="absolute -top-1.5 right-4 size-3 rotate-45 rounded-[2px] bg-white" />
            <p className="text-center text-[13px] font-semibold leading-[1.3]">이번달 활동 리포트는 어떠셨나요?</p>
            <div className="mt-3 flex items-center justify-between">
              {feedbackOptions.map(({ key, label, Icon, ring, tone }) => (
                <button key={key} type="button" onClick={() => { setFeedback(key); setOpen(false) }} className="flex flex-col items-center gap-1.5 rounded-xl px-1 py-1 focus-visible:outline-[#a50034]">
                  <span className={`grid size-11 place-items-center rounded-full ${ring}`}><Icon size={22} className={tone} aria-hidden="true" /></span>
                  <span className="text-[11px] text-[#475569]">{label}</span>
                </button>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  )
}
