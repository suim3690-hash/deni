import { useRef, useState, type FormEvent } from 'react'
import Header from '../components/Header'
import babyFaceIcon from '../assets/icons/baby-face.svg'
import calendarIcon from '../assets/icons/calendar.svg'
import checkIcon from '../assets/icons/check.svg'
import { computeSafetyProfile, localToday, registerChild, type RegisteredChild } from '../services/children'
import { stageByOrder, stageDisplayNames, stageOrder, stageRegistrationAgeLabels, stageTitles } from '../lib/stages'

type RegistrationStatus = 'editing' | 'loading' | 'success' | 'failure'

interface ChildRegistrationProps {
  onGoHome: (child: RegisteredChild) => void
}

export default function ChildRegistration({ onGoHome }: ChildRegistrationProps) {
  const [name, setName] = useState('')
  const [birthDate, setBirthDate] = useState('')
  const [status, setStatus] = useState<RegistrationStatus>('editing')
  const [error, setError] = useState('')
  const [registeredChild, setRegisteredChild] = useState<RegisteredChild | null>(null)
  const requestKey = useRef(crypto.randomUUID())

  const isReadOnly = status === 'loading' || status === 'success'
  const previewStage = birthDate && birthDate <= localToday() ? computeSafetyProfile(birthDate).stage : null

  function resetAfterEdit() {
    setStatus('editing')
    setError('')
    requestKey.current = crypto.randomUUID()
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (status === 'loading') return

    const trimmedName = name.trim()
    if (!trimmedName && !birthDate) {
      setError('아이 이름과 생년월일을 입력해 주세요.')
      return
    }
    if (!trimmedName) {
      setError('아이 이름 또는 애칭을 입력해 주세요.')
      return
    }
    if (!birthDate) {
      setError('생년월일을 선택해 주세요.')
      return
    }
    if (birthDate > localToday()) {
      setError('미래 날짜는 생년월일로 선택할 수 없어요.')
      return
    }

    setError('')
    setStatus('loading')
    try {
      const child = await registerChild(
        { name: trimmedName, birthDate },
        requestKey.current,
      )
      setRegisteredChild(child)
      setName(child.name)
      setBirthDate(child.birthDate)
      setStatus('success')
    } catch {
      setStatus('failure')
    }
  }

  const title = {
    editing: '우리 아이 정보를 등록해보세요',
    loading: '아이 정보를 등록하고 있어요',
    success: '아이 정보 등록이 완료됐어요',
    failure: '정보를 저장하지 못했어요',
  }[status]

  const description = {
    editing: <>생년월일을 입력하면 성장 단계에 맞춰<br />로봇청소기 안전 기준이 자동으로 바뀌어요</>,
    loading: '잠시만 기다려 주세요.',
    success: `${registeredChild?.name ?? name}의 정보를 확인했어요.`,
    failure: '연결 상태를 확인하고 다시 시도해 주세요.',
  }[status]

  const buttonText = {
    editing: '정보 등록하고 Safety Care 시작하기',
    loading: '등록 중…',
    success: '홈으로 이동하기',
    failure: '다시 시도하기',
  }[status]

  const helper = error || {
    editing: <>등록 후 기기 연결 상태를 확인하고<br />맞춤 안전 프로필을 적용합니다.</>,
    loading: '정보 저장이 완료되면 다음 화면으로 이동합니다.',
    success: <>안전 프로필의 기기 적용 상태는<br />홈에서 확인해 주세요.</>,
    failure: '입력한 정보는 유지되어 있어요.',
  }[status]

  return (
    <div className="min-h-screen bg-[#f0f5fd]">
      <Header title="손지아 홈" hasNotification />

      <main className="mx-auto max-w-[402px] px-4 pb-6 pt-[45px]">
        <section className="rounded-2xl border-2 border-dashed border-[#ffdfdf] bg-[#fff8f8] px-5 py-5 drop-shadow-[0px_1px_1px_rgba(0,0,0,0.05)]">
          <div className="flex min-h-[128px] flex-col items-center">
            <div className="flex size-12 items-center justify-center rounded-full bg-[#ffeaeb]">
              <img src={babyFaceIcon} alt="" className="size-[18px]" />
            </div>
            <h1 className="mt-3 text-center text-[17px] font-medium tracking-[-0.425px] text-[#1e293b]">
              {title}
            </h1>
            <p className="mt-1 text-center text-[12px] font-medium leading-[19.5px] text-[#475569]">
              {description}
            </p>
          </div>

          <form onSubmit={handleSubmit} noValidate>
            <div className="mt-4 flex flex-col gap-3.5">
              <div className="flex flex-col gap-1.5">
                <label htmlFor="child-name" className="text-[12px] font-medium text-[#334155]">
                  아이 이름 (또는 애칭)
                </label>
                <input
                  id="child-name"
                  type="text"
                  autoComplete="off"
                  value={name}
                  onChange={(event) => { setName(event.target.value); resetAfterEdit() }}
                  placeholder="예: 김튼튼, 우리아가"
                  readOnly={isReadOnly}
                  aria-invalid={!!error && !name.trim()}
                  aria-describedby={error ? 'registration-message' : undefined}
                  className="h-[45px] w-full rounded-xl border border-[#ffe4e6] bg-white px-[15px] text-[14px] text-[#1e293b] placeholder:text-[#6b7280] focus:outline-none focus:ring-2 focus:ring-[#a50034]/20 read-only:text-[#6b7280]"
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
                    value={birthDate}
                    max={localToday()}
                    onChange={(event) => { setBirthDate(event.target.value); resetAfterEdit() }}
                    disabled={isReadOnly}
                    aria-invalid={!!error && (!birthDate || birthDate > localToday())}
                    aria-describedby={error ? 'registration-message' : undefined}
                    className="h-[45px] w-full appearance-none rounded-xl border border-[#ffe4e6] bg-white px-[15px] text-[14px] text-transparent caret-transparent focus:outline-none focus:ring-2 focus:ring-[#a50034]/20 disabled:opacity-100 [&::-webkit-calendar-picker-indicator]:absolute [&::-webkit-calendar-picker-indicator]:inset-0 [&::-webkit-calendar-picker-indicator]:size-full [&::-webkit-calendar-picker-indicator]:opacity-0"
                  />
                  <span
                    aria-hidden="true"
                    className={`pointer-events-none absolute left-[15px] top-1/2 -translate-y-1/2 text-[14px] ${birthDate && status === 'editing' ? 'text-[#1e293b]' : 'text-[#6b7280]'}`}
                  >
                    {birthDate ? birthDate.replaceAll('-', '.') : 'YYYY.MM.DD 선택'}
                  </span>
                  <img
                    src={calendarIcon}
                    alt=""
                    className="pointer-events-none absolute right-[17px] top-1/2 h-[16.67px] w-[15px] -translate-y-1/2"
                  />
                </div>
              </div>
            </div>

            <div className="mt-4 space-y-2">
              <p className="px-0.5 text-[11px] font-medium text-[#334155]">성장 단계 안내</p>
              {[1, 2, 3].map((order) => {
                const stage = stageByOrder[order]
                const isActive = previewStage === stage
                return (
                  <div key={stage} className={`flex items-start gap-2.5 rounded-xl border p-2.5 transition-colors ${isActive ? 'border-[#a50034] bg-[#fff1f2]' : 'border-[#f1f5f9] bg-white'}`}>
                    <span className={`mt-0.5 grid size-6 shrink-0 place-items-center rounded-full text-[11px] font-bold ${isActive ? 'bg-[#a50034] text-white' : 'bg-[#f1f5f9] text-[#94a3b8]'}`}>
                      {stageOrder[stage]}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className={`text-[13px] font-bold ${isActive ? 'text-[#a50034]' : 'text-[#334155]'}`}>{stageDisplayNames[stage]}</span>
                        <span className="text-[11px] text-[#94a3b8]">{stageRegistrationAgeLabels[stage]}</span>
                        {isActive && <span className="rounded-full bg-[#a50034] px-1.5 py-[1px] text-[9px] font-bold text-white">우리 아이 단계</span>}
                      </div>
                      <p className="mt-0.5 text-[11px] leading-[1.4] text-[#64748b]">{stageTitles[stage]}</p>
                    </div>
                  </div>
                )
              })}
            </div>

            <button
              type={status === 'success' ? 'button' : 'submit'}
              onClick={status === 'success' && registeredChild ? () => onGoHome(registeredChild) : undefined}
              disabled={status === 'loading'}
              className="mt-5 flex h-12 w-full items-center justify-center gap-1.5 rounded-xl bg-[#a50034] px-4 text-[14px] font-medium text-white shadow-[0px_4px_6px_-1px_rgba(0,0,0,0.1),0px_2px_4px_-2px_rgba(0,0,0,0.1)] hover:bg-[#8f002d] focus:outline-none focus-visible:ring-2 focus-visible:ring-[#a50034] focus-visible:ring-offset-2 disabled:cursor-wait disabled:opacity-80"
            >
              {status === 'loading' ? <span aria-hidden="true" className="size-[13px] animate-spin rounded-full border-2 border-white/40 border-t-white" /> : <img src={checkIcon} alt="" className="size-[13.33px]" />}
              {buttonText}
            </button>

            <p
              id="registration-message"
              role={error ? 'alert' : status === 'failure' ? 'status' : undefined}
              aria-live={status === 'loading' || status === 'success' ? 'polite' : undefined}
              className={`mt-2 min-h-[28px] text-center text-[11px] font-medium leading-[13.75px] ${error ? 'text-[#a50034]' : 'text-[#94a3b8]'}`}
            >
              {helper}
            </p>
          </form>
        </section>
      </main>
    </div>
  )
}
