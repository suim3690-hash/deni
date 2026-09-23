import { AlertTriangle, ArrowRight } from 'lucide-react'

interface Props {
  badge: string
  urgent: boolean
  title: string
  riskLabel?: string | null
  subtitle?: string
  onClick?: () => void
  ariaLabel?: string
  role?: 'status' | 'alert'
}

// 위험 감지 알림 박스: 항상 빨간색, 경고 아이콘은 깜빡인다.
export default function HazardAlertBox({ badge, urgent, title, riskLabel, subtitle, onClick, ariaLabel, role = 'status' }: Props) {
  const content = (
    <>
      <span className="grid size-10 shrink-0 place-items-center rounded-[12px] bg-white text-[#d61f3f]">
        <AlertTriangle size={22} fill="currentColor" stroke="white" strokeWidth={1.8} aria-hidden="true" className="animate-blink motion-reduce:animate-none" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5">
          <span className={`shrink-0 rounded-full px-2 py-[2px] text-[11px] font-bold ${urgent ? 'bg-white text-[#c8102e]' : 'bg-[#fef08a] text-[#854d0e]'}`}>{badge}</span>
          {riskLabel && <span className="shrink-0 rounded-full bg-white/20 px-2 py-[2px] text-[10px] font-semibold text-white">위험도 {riskLabel}</span>}
        </span>
        <strong className="mt-1 block break-keep text-[14px] leading-[1.3]">{title}</strong>
        {subtitle && <span className="mt-0.5 block text-[11px] text-white/85">{subtitle}</span>}
      </span>
      {onClick && <ArrowRight size={16} className="shrink-0 text-white/85" aria-hidden="true" />}
    </>
  )
  const boxClass = 'flex w-full items-center gap-3 rounded-[16px] bg-gradient-to-r from-[#c8102e] to-[#e11d48] p-3 text-left text-white shadow-[0_6px_16px_rgba(200,16,46,0.3)]'
  if (onClick) return <button type="button" onClick={onClick} aria-label={ariaLabel} className={`${boxClass} focus-visible:outline-[#a50034]`}>{content}</button>
  return <section role={role} aria-label={ariaLabel} className={boxClass}>{content}</section>
}
