import { useState, type ReactNode } from 'react'
import Header from '../components/Header'
import babyFaceIcon from '../assets/icons/baby-face.svg'
import calendarIcon from '../assets/icons/calendar.svg'
import checkIcon from '../assets/icons/check.svg'

type Status = 'idle' | 'submitting' | 'success' | 'failure'

interface ChildRegisterProps {
  onRegistered: (name: string) => void
}

const CHILD_INFO_STORAGE_KEY = 'childInfo'

function validate(name: string, birthday: string): string | null {
  const hasName = name.trim() !== ''
  const hasBirthday = birthday !== ''
  if (!hasName && !hasBirthday) return '아이 이름과 생년월일을 입력해 주세요.'
  if (hasName && !hasBirthday) return '생년월일을 선택해 주세요.'
  if (!hasName && hasBirthday) return '아이 이름 또는 애칭을 입력해 주세요.'
  return null
}

export default function ChildRegister({ onRegistered }: ChildRegisterProps) {
  const [name, setName] = useState('')
  const [birthday, setBirthday] = useState('')
  const [status, setStatus] = useState<Status>('idle')
  const [error, setError] = useState<string | null>(null)
  const [birthdayFocused, setBirthdayFocused] = useState(false)

  const submit = () => {
    setStatus('submitting')
    setTimeout(() => {
      const existingRaw = localStorage.getItem(CHILD_INFO_STORAGE_KEY)
      const existing = existingRaw ? (JSON.parse(existingRaw) as { name: string; birthday: string }) : null
      if (existing && existing.name.trim() === name.trim() && existing.birthday === birthday) {
        setStatus('failure')
        return
      }
      localStorage.setItem(CHILD_INFO_STORAGE_KEY, JSON.stringify({ name, birthday }))
      setStatus('success')
    }, 1200)
  }

  const handlePrimaryAction = () => {
    if (status === 'idle') {
      const validationError = validate(name, birthday)
      if (validationError) {
        setError(validationError)
        return
      }
      setError(null)
      submit()
      return
    }
    if (status === 'failure') {
      submit()
      return
    }
    if (status === 'success') {
      onRegistered(name)
    }
  }

  const inputsDisabled = status !== 'idle'

  let heading = '우리 아이 정보를 등록해보세요'
  let subtext: ReactNode = (
    <>
      생년월일을 입력하면 성장 단계에 맞춰
      <br />
      로봇청소기 안전 기준이 자동으로 바뀌어요
    </>
  )
  let buttonLabel = '정보 등록하고 Safety Care 시작하기'
  let footer: ReactNode = (
    <>
      등록 후 기기 연결 상태를 확인하고
      <br />
      맞춤 안전 프로필을 적용합니다.
    </>
  )
  let footerColorClass = 'text-[#94a3b8]'

  if (status === 'submitting') {
    heading = '아이 정보를 등록하고 있어요'
    subtext = '잠시만 기다려 주세요.'
    buttonLabel = '등록 중…'
    footer = '정보 저장이 완료되면 다음 화면으로 이동합니다.'
  } else if (status === 'success') {
    heading = '아이 정보 등록이 완료됐어요'
    subtext = `${name}의 정보를 확인했어요.`
    buttonLabel = '홈으로 이동하기'
    footer = (
      <>
        안전 프로필의 기기 적용 상태는
        <br />
        홈에서 확인해 주세요.
      </>
    )
  } else if (status === 'failure') {
    heading = '정보를 저장하지 못했어요'
    subtext = '연결 상태를 확인하고 다시 시도해 주세요.'
    buttonLabel = '다시 시도하기'
    footer = '입력한 정보는 유지되어 있어요.'
  } else if (error) {
    footer = error
    footerColorClass = 'text-[#a50034]'
  }

  return (
    <div className="min-h-screen bg-[#f0f5fd]">
      <Header title="손지아 홈" hasNotification />

      <main className="mx-auto max-w-[402px] px-4 pb-6 pt-2">
        <section className="rounded-2xl border-2 border-dashed border-[#ffdfdf] bg-[#fff8f8] px-5 py-5 drop-shadow-[0px_1px_1px_rgba(0,0,0,0.05)]">
          <div className="flex flex-col items-center">
            <div className="flex size-12 items-center justify-center rounded-full bg-[#ffeaeb]">
              <img src={babyFaceIcon} alt="" className="size-[18px]" />
            </div>
            <h2 className="mt-3 text-center text-[17px] font-medium tracking-[-0.425px] text-[#1e293b]">
              {heading}
            </h2>
            <p className="mt-1 text-center text-[12px] font-medium leading-[19.5px] text-[#475569]">
              {subtext}
            </p>
          </div>

          <div className="mt-4 flex flex-col gap-3.5">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="child-name" className="text-[12px] font-medium text-[#334155]">
                아이 이름 (또는 애칭)
              </label>
              <input
                id="child-name"
                type="text"
                value={name}
                disabled={inputsDisabled}
                onChange={(e) => {
                  setName(e.target.value)
                  setError(null)
                }}
                placeholder="예: 김튼튼, 우리아가"
                className="w-full rounded-xl border border-[#ffe4e6] bg-white px-[15px] py-[11px] text-[14px] text-[#1e293b] placeholder:text-[#6b7280] focus:outline-none focus:ring-2 focus:ring-[#a50034]/20 disabled:opacity-100"
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="child-birthday" className="text-[12px] font-medium text-[#334155]">
                생년월일
              </label>
              <div className="relative">
                <input
                  id="child-birthday"
                  type="date"
                  value={birthday}
                  disabled={inputsDisabled}
                  onChange={(e) => {
                    setBirthday(e.target.value)
                    setError(null)
                  }}
                  onFocus={() => setBirthdayFocused(true)}
                  onBlur={() => setBirthdayFocused(false)}
                  className={`w-full appearance-none rounded-xl border border-[#ffe4e6] bg-white px-[15px] py-[11px] text-[14px] disabled:opacity-100 [&::-webkit-calendar-picker-indicator]:absolute [&::-webkit-calendar-picker-indicator]:inset-0 [&::-webkit-calendar-picker-indicator]:size-full [&::-webkit-calendar-picker-indicator]:opacity-0 ${birthdayFocused ? 'text-[#1e293b]' : 'text-transparent caret-transparent'}`}
                />
                {!birthdayFocused && (
                  <span
                    className={`pointer-events-none absolute left-[15px] top-1/2 -translate-y-1/2 text-[14px] ${birthday ? 'text-[#1e293b]' : 'text-[#6b7280]'}`}
                  >
                    {birthday ? birthday.replaceAll('-', '.') : 'YYYY.MM.DD 선택'}
                  </span>
                )}
                <img
                  src={calendarIcon}
                  alt=""
                  className="pointer-events-none absolute right-[17px] top-1/2 h-[16.67px] w-[15px] -translate-y-1/2"
                />
              </div>
            </div>
          </div>

          <button
            type="button"
            onClick={handlePrimaryAction}
            disabled={status === 'submitting'}
            className="mt-3.5 flex h-12 w-full items-center justify-center gap-1.5 rounded-xl bg-[#a50034] px-4 text-[14px] font-medium text-white shadow-[0px_4px_6px_-1px_rgba(0,0,0,0.1),0px_2px_4px_-2px_rgba(0,0,0,0.1)] disabled:opacity-90"
          >
            <img src={checkIcon} alt="" className="size-[13.33px]" />
            {buttonLabel}
          </button>

          <p className={`mt-2 text-center text-[11px] font-medium leading-[13.75px] ${footerColorClass}`}>
            {footer}
          </p>
        </section>
      </main>
    </div>
  )
}
