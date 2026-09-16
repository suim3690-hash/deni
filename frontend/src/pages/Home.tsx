import { useState } from 'react'
import Header from '../components/Header'
import babyFaceIcon from '../assets/icons/baby-face.svg'
import calendarIcon from '../assets/icons/calendar.svg'
import checkIcon from '../assets/icons/check.svg'

export default function Home() {
  const [name, setName] = useState('')
  const [birthday, setBirthday] = useState('')

  const handleSubmit = () => {
    // TODO: 아이 정보 등록 API 연동
    console.log({ name, birthday })
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
              우리 아이 정보를 등록해보세요
            </h2>
            <p className="mt-1 text-center text-[12px] font-medium leading-[19.5px] text-[#475569]">
              생년월일을 입력하면 성장 단계에 맞춰
              <br />
              로봇청소기 안전 기준이 자동으로 바뀌어요
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
                onChange={(e) => setName(e.target.value)}
                placeholder="예: 김튼튼, 우리아가"
                className="w-full rounded-xl border border-[#ffe4e6] bg-white px-[15px] py-[11px] text-[14px] text-[#1e293b] placeholder:text-[#6b7280] focus:outline-none focus:ring-2 focus:ring-[#a50034]/20"
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
                  onChange={(e) => setBirthday(e.target.value)}
                  className="w-full appearance-none rounded-xl border border-[#ffe4e6] bg-white px-[15px] py-[11px] text-[14px] text-transparent caret-transparent [&::-webkit-calendar-picker-indicator]:absolute [&::-webkit-calendar-picker-indicator]:inset-0 [&::-webkit-calendar-picker-indicator]:size-full [&::-webkit-calendar-picker-indicator]:opacity-0"
                />
                <span
                  className={`pointer-events-none absolute left-[15px] top-1/2 -translate-y-1/2 text-[14px] ${birthday ? 'text-[#1e293b]' : 'text-[#6b7280]'}`}
                >
                  {birthday ? birthday.replaceAll('-', '.') : 'YYYY.MM.DD 선택'}
                </span>
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
            onClick={handleSubmit}
            className="mt-3.5 flex h-12 w-full items-center justify-center gap-1.5 rounded-xl bg-[#a50034] px-4 text-[14px] font-medium text-white shadow-[0px_4px_6px_-1px_rgba(0,0,0,0.1),0px_2px_4px_-2px_rgba(0,0,0,0.1)]"
          >
            <img src={checkIcon} alt="" className="size-[13.33px]" />
            정보 등록하고 Safety Care 시작하기
          </button>

          <p className="mt-2 text-center text-[11px] font-medium leading-[13.75px] text-[#94a3b8]">
            등록 후 기기 연결 상태를 확인하고
            <br />
            맞춤 안전 프로필을 적용합니다.
          </p>
        </section>
      </main>
    </div>
  )
}
