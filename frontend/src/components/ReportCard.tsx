import reportIconToggle from '../assets/icons/home/report-icon-toggle.svg'
import reportIconChart from '../assets/icons/home/report-icon-chart.svg'

interface ReportCardProps {
  childName: string
  onOpenReport?: () => void
}

export default function ReportCard({ childName, onOpenReport }: ReportCardProps) {
  return (
    <div className="w-full rounded-[24px] border border-[#f1f5f9]/80 bg-white p-[21px] shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
      <div className="flex items-start justify-between">
        <div className="flex items-center">
          <span className="flex items-center rounded-full border border-[#fee2e2]/70 bg-[#fef2f2] px-[11px] py-[3px] text-[11px] font-medium tracking-[-0.32px] text-[#a50034]">
            이번 달(9월) 리포트 발행 완료
          </span>
          <span className="pl-2 text-[10px] font-medium tracking-[-0.32px] text-[#94a3b8]">
            매월 1일 자동 업데이트
          </span>
        </div>
        <img src={reportIconToggle} alt="" className="h-[9px] w-[15px]" />
      </div>

      <div className="mt-3 flex flex-col gap-[3px]">
        <div className="flex items-center">
          <img src={reportIconChart} alt="" className="size-[15px]" />
          <h3 className="pl-2 text-[18px] font-medium tracking-[-0.32px] text-[#0f172a]">
            우리 아이 맞춤 성장 리포트
          </h3>
        </div>
        <p className="text-[12px] leading-[19.5px] tracking-[-0.32px] text-[#475569]">
          {childName} 아동의 9월 행동 반경 및 위험물 접촉 분석 데이터가
          <br />
          포함된 심층 리포트를 확인해보세요.
        </p>
      </div>

      <div className="mt-[14px] border-t border-[#f1f5f9] pt-[14px]">
        <button
          type="button"
          onClick={onOpenReport}
          className="flex h-[43px] w-full items-center justify-center rounded-full bg-[#a50034] text-[17px] font-bold tracking-[-0.6px] text-white"
        >
          리포트 보러가기 →
        </button>
      </div>
    </div>
  )
}
