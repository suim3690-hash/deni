import navHomeActive from '../assets/icons/home/nav-home-active.svg'
import navDevice from '../assets/icons/home/nav-device.svg'
import navCare from '../assets/icons/home/nav-care.svg'
import navMenu from '../assets/icons/home/nav-menu.svg'

const TABS = [
  { key: 'device', label: '디바이스', icon: navDevice },
  { key: 'care', label: '케어', icon: navCare },
  { key: 'menu', label: '메뉴', icon: navMenu },
] as const

export default function BottomNav() {
  return (
    <nav className="fixed inset-x-0 bottom-0 mx-auto flex h-[62px] w-full max-w-[402px] items-center justify-between border-t border-[#e2e8f0] bg-white/95 px-6 py-2.5 backdrop-blur-[6px]">
      <button type="button" aria-label="홈" className="flex w-14 items-center justify-center">
        <img src={navHomeActive} alt="" className="h-[31.3px] w-14" />
      </button>
      {TABS.map((tab) => (
        <button
          key={tab.key}
          type="button"
          className="flex w-14 flex-col items-center gap-1 text-[11px] font-medium text-[#94a3b8]"
        >
          <img src={tab.icon} alt="" className="h-4 w-5" />
          {tab.label}
        </button>
      ))}
    </nav>
  )
}
