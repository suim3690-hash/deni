import bannerBg from '../assets/icons/home/banner-bg.svg'
import bannerBadge from '../assets/icons/home/banner-badge.svg'
import bannerSubtitle from '../assets/icons/home/banner-subtitle.svg'
import bannerSmiley from '../assets/icons/home/banner-smiley.svg'
import bannerTexture from '../assets/icons/home/banner-texture.svg'
import bannerDetailLink from '../assets/icons/home/banner-detail-link.svg'
import bannerDetailLinkUnderline from '../assets/icons/home/banner-detail-link-underline.svg'

interface GrowthStageBannerProps {
  onViewDetail?: () => void
}

export default function GrowthStageBanner({ onViewDetail }: GrowthStageBannerProps) {
  return (
    <button
      type="button"
      onClick={onViewDetail}
      className="relative block h-[185px] w-full overflow-hidden rounded-[24px] text-left"
    >
      <img src={bannerBg} alt="" className="absolute inset-0 size-full" />
      <img src={bannerTexture} alt="" className="absolute left-[20px] top-[138px] h-[27px] w-[310px]" />
      <img src={bannerBadge} alt="" className="absolute left-[20px] top-[20px] h-6 w-[201px]" />
      <img src={bannerSmiley} alt="" className="absolute left-[284.6px] top-[20px] h-12 w-[45.4px]" />
      <h3 className="absolute left-[20px] top-[51px] text-[24px] font-bold leading-[32px] tracking-[-0.6px] text-white">
        18개월 걸음마 시기
      </h3>
      <img src={bannerSubtitle} alt="모서리 충돌 방지 및 바닥 전선 걸림 집중 감지 모드" className="absolute left-[21px] top-[89px] h-7 w-[246px]" />
      <img src={bannerDetailLink} alt="상세 보기" className="absolute left-[276.4px] top-[153px] h-[10px] w-[52px]" />
      <img src={bannerDetailLinkUnderline} alt="" className="absolute left-[276px] top-[164px] h-[1.3px] w-[54px]" />
    </button>
  )
}
