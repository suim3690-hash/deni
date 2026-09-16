import { Bell, ChevronDown, MoreVertical, Plus } from 'lucide-react'

interface HeaderProps {
  title: string
  hasNotification?: boolean
  onTitleClick?: () => void
  onAddClick?: () => void
  onNotificationClick?: () => void
  onMenuClick?: () => void
}

export default function Header({
  title,
  hasNotification = false,
  onTitleClick,
  onAddClick,
  onNotificationClick,
  onMenuClick,
}: HeaderProps) {
  return (
    <header className="flex h-16 items-center justify-between bg-[#f0f5fd] px-4">
      <button
        type="button"
        onClick={onTitleClick}
        className="flex items-center gap-1 text-[17px] font-semibold text-[#1e293b]"
      >
        {title}
        <ChevronDown size={16} strokeWidth={2} className="text-[#1e293b]" />
      </button>

      <div className="flex items-center gap-4">
        <button type="button" onClick={onAddClick} aria-label="추가" className="text-[#1e293b]">
          <Plus size={20} strokeWidth={2} />
        </button>
        <button
          type="button"
          onClick={onNotificationClick}
          aria-label="알림"
          className="relative text-[#1e293b]"
        >
          <Bell size={20} strokeWidth={2} />
          {hasNotification && (
            <span className="absolute -right-0.5 -top-0.5 size-2 rounded-full bg-[#ef4444]" />
          )}
        </button>
        <button type="button" onClick={onMenuClick} aria-label="메뉴" className="text-[#1e293b]">
          <MoreVertical size={20} strokeWidth={2} />
        </button>
      </div>
    </header>
  )
}
