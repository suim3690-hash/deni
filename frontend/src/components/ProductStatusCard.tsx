import cardShape from '../assets/icons/home/product-card-shape.svg'
import cardBorder from '../assets/icons/home/product-card-border.svg'
import iconCluster from '../assets/icons/home/product-icon-cluster.svg'
import iconDetail1 from '../assets/icons/home/product-icon-detail-1.svg'
import iconDetail2 from '../assets/icons/home/product-icon-detail-2.svg'
import iconDetail3 from '../assets/icons/home/product-icon-detail-3.svg'
import iconDetail4 from '../assets/icons/home/product-icon-detail-4.svg'
import iconDetail5 from '../assets/icons/home/product-icon-detail-5.svg'
import iconDetail6 from '../assets/icons/home/product-icon-detail-6.svg'
import iconDetail7 from '../assets/icons/home/product-icon-detail-7.svg'
import iconDetail8 from '../assets/icons/home/product-icon-detail-8.svg'

interface ProductStatusCardProps {
  onOpenRiskMap?: () => void
}

export default function ProductStatusCard({ onOpenRiskMap }: ProductStatusCardProps) {
  return (
    <div className="relative h-[246px] w-full overflow-hidden rounded-[24px]">
      <img src={cardShape} alt="" className="absolute inset-0 size-full" />
      <img src={cardBorder} alt="" className="absolute inset-0 size-full" />

      {/* icon cluster: robot avatar, verified badge, safety-mode pill, battery */}
      <div className="absolute left-[9px] top-[15px] h-[85px] w-[331px]">
        <img src={iconCluster} alt="" className="absolute inset-0 size-full" />
        <img src={iconDetail1} alt="" className="absolute left-[8px] top-[15.4px] h-[43px] w-[42.1px]" />
        <img src={iconDetail2} alt="" className="absolute left-[16.2px] top-[26.8px] h-[19.8px] w-[25.7px]" />
        <img src={iconDetail3} alt="" className="absolute left-[37.1px] top-[19.7px] h-[7.2px] w-[8px]" />
        <img src={iconDetail4} alt="" className="absolute left-[222px] top-[8.3px] h-[12.5px] w-[14.7px]" />
        <img src={iconDetail5} alt="" className="absolute left-[242px] top-[0.3px] h-[28.6px] w-[86px]" />
        <img src={iconDetail6} alt="" className="absolute left-[250.1px] top-[10.2px] h-[8.7px] w-[9.75px]" />
        <img src={iconDetail7} alt="" className="absolute left-[285px] top-[59.2px] h-[10.4px] w-[7.6px]" />
        <img src={iconDetail8} alt="" className="absolute left-[297.2px] top-[60.6px] h-[7.6px] w-[23.2px]" />
      </div>

      <h3 className="absolute left-[68px] top-[26px] text-[20px] font-bold tracking-[-0.6px] text-black">
        LG 로니 AI 베이비 케어
      </h3>
      <span className="absolute left-[273px] top-[17px] text-[12px] font-semibold tracking-[-0.6px] text-[#186953]">
        안심모드 ON
      </span>
      <span className="absolute left-[273px] top-[50px] flex h-[18px] items-center rounded-full bg-[#fef2f2] px-2.5 text-[10px] font-medium tracking-[-0.32px] text-[#a50034]">
        일시 정지
      </span>
      <span className="absolute left-[69px] top-[68px] flex h-[18px] items-center rounded-full bg-[#d1feee] px-1.5 text-[10px] font-medium tracking-[-0.32px] text-[#1a5b48]">
        온라인
      </span>

      <div className="absolute left-[50px] top-[105px] h-[85px] w-[121px] rounded-[18px] bg-[#f5f8ff]" />
      <div className="absolute left-[187px] top-[105px] h-[85px] w-[121px] rounded-[18px] bg-[#f5f8ff]" />
      <p className="absolute left-[75px] top-[109px] text-[12px] font-semibold tracking-[-0.6px] text-[#5a4042]">
        장애물 정밀 감지
      </p>
      <p className="absolute left-[73px] top-[132px] text-[25px] font-semibold tracking-[-0.6px] text-[#161c25]">
        4개
      </p>
      <p className="absolute left-[113px] top-[139px] text-[12px] font-semibold tracking-[-0.6px] text-[#c0063d]">
        소형 완구
      </p>
      <p className="absolute left-[214px] top-[109px] text-[12px] font-semibold tracking-[-0.6px] text-[#5a4042]">
        공기 청정 연동
      </p>
      <p className="absolute left-[205px] top-[128px] text-[25px] font-semibold tracking-[-0.6px] text-[#186953]">
        좋음
      </p>
      <p className="absolute left-[250px] top-[136px] text-[12px] font-semibold tracking-[-0.6px] text-[#186953]">
        퓨리케어
      </p>
      <p className="absolute left-[231px] top-[157px] text-[12px] font-semibold tracking-[-0.6px] text-[#186953]">
        가동중
      </p>

      <button
        type="button"
        onClick={onOpenRiskMap}
        className="absolute left-[80px] top-[200px] flex h-[38.5px] w-[205px] items-center justify-center rounded-full bg-[#a50034] text-[17px] font-bold tracking-[-0.6px] text-white"
      >
        실시간 위험 감지 맵 →
      </button>
    </div>
  )
}
