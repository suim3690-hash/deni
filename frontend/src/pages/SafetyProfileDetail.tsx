import { useEffect, useState, type FormEvent } from 'react'
import { ArrowLeft, Check, Info, ListChecks, Pencil, Smile, X } from 'lucide-react'
import MonthlyFeedbackButton from '../components/MonthlyFeedbackButton'
import { getSafetyProfile, localToday, updateChild, type RegisteredChild, type SafetyProfileData } from '../services/children'
import { ApiRequestError, apiErrorMessage } from '../services/apiError'
import { stageAgeRangeLabels, stageBannerSubtitles, stageLabels, stageOrder, stageTitles, type Stage } from '../lib/stages'
import { categoryExamples, categoryLabels, riskByStage, riskLabels, riskStyles, type HazardCategory } from '../lib/hazardRisk'

interface Props {
  child: RegisteredChild
  onBack: () => void
  onUpdateChild: (child: RegisteredChild) => void
  isMock: boolean
}

const stageList: Stage[] = ['INFANT', 'TODDLER', 'ACTIVE_CHILD']
const categoryList: HazardCategory[] = ['SWALLOW', 'LIVING']

export default function SafetyProfileDetail({ child, onBack, onUpdateChild, isMock }: Props) {
  const [profileReloadKey, setProfileReloadKey] = useState(0)
  const profileRequestKey = `${child.childId}:${child.birthDate}:${profileReloadKey}`
  const [profileRequest, setProfileRequest] = useState<{
    key: string
    detail: SafetyProfileData | null
    error: string
  } | null>(null)
  const profileRequestIsCurrent = profileRequest?.key === profileRequestKey
  const profileDetail = profileRequestIsCurrent ? profileRequest.detail : null
  const profileError = profileRequestIsCurrent ? profileRequest.error : ''

  const profile = profileDetail ?? child.safetyProfile
  const stage = profile.stage
  const isSupported = profile.status === 'APPLIED' && stage !== null

  const [isEditing, setIsEditing] = useState(false)
  const [editName, setEditName] = useState(child.name)
  const [editBirthDate, setEditBirthDate] = useState(child.birthDate)
  const [saving, setSaving] = useState(false)
  const [editError, setEditError] = useState('')
  const [editFieldErrors, setEditFieldErrors] = useState<Record<string, string>>({})

  useEffect(() => {
    let current = true

    void getSafetyProfile(child.childId, child.birthDate)
      .then((result) => {
        if (current) setProfileRequest({ key: profileRequestKey, detail: result, error: '' })
      })
      .catch((error) => {
        if (current) setProfileRequest({
          key: profileRequestKey,
          detail: null,
          error: apiErrorMessage(error, 'Safety Profile을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'),
        })
      })

    return () => { current = false }
  }, [child.childId, child.birthDate, profileRequestKey])

  function startEditing() {
    setEditName(child.name)
    setEditBirthDate(child.birthDate)
    setEditError('')
    setEditFieldErrors({})
    setIsEditing(true)
  }

  async function handleEditSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (saving) return

    const trimmedName = editName.trim()
    if (!trimmedName) {
      setEditError('아이 이름 또는 애칭을 입력해 주세요.')
      return
    }
    if (!editBirthDate) {
      setEditError('생년월일을 선택해 주세요.')
      return
    }
    if (editBirthDate > localToday()) {
      setEditError('미래 날짜는 생년월일로 선택할 수 없어요.')
      return
    }

    setEditError('')
    setEditFieldErrors({})
    setSaving(true)
    try {
      const updated = await updateChild(child.childId, { name: trimmedName, birthDate: editBirthDate })
      onUpdateChild(updated)
      setIsEditing(false)
    } catch (error) {
      setEditFieldErrors(error instanceof ApiRequestError ? error.fieldErrors : {})
      setEditError(apiErrorMessage(error, '정보를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="min-h-screen bg-[#f2f6fa] text-[#0f172a] [zoom:clamp(0.85,calc(100vw/402px),1.4)]">
      <div className="mx-auto min-h-screen max-w-[402px] pb-[40px]">
        <header className="sticky top-0 z-10 flex items-center justify-between gap-2 border-b border-[#f1f5f9] bg-white/95 px-4 pb-[13px] pt-3 backdrop-blur-md">
          <div className="flex min-w-0 items-center gap-2.5">
            <button type="button" onClick={onBack} aria-label="홈으로 돌아가기" className="grid shrink-0 size-6 place-items-center focus-visible:outline-[#a50034]"><ArrowLeft size={20} /></button>
            <h1 className="text-[16px] font-bold tracking-[-0.4px]">성장단계 연동 Safety Profile</h1>
          </div>
        </header>

        <main className="space-y-4 px-4 pt-4">
          <section aria-label="성장 단계 안내" className="min-h-[185px] rounded-[24px] bg-gradient-to-r from-[#d9064d] via-[#ee4f7e] to-[#fa80a5] p-5 text-white shadow-[0_6px_15px_rgba(174,0,57,0.14)]">
            <div className="flex items-start justify-between gap-2">
              <span className="min-w-0 rounded-full bg-white/20 px-[10px] py-[5px] text-[11px] font-medium">✦ {isSupported ? isMock ? '현재 Safety Profile 자동 적용 중' : 'Safety Profile 등록 완료' : '지원 범위 밖'}</span>
              {isMock ? <MonthlyFeedbackButton shapeClassName="size-[44px] rounded-[14px]" wrapperClassName="shrink-0" iconSize={22} /> : <span title="리포트 평가 기능 준비 중" aria-label="리포트 평가 기능 준비 중" className="grid size-[44px] shrink-0 place-items-center rounded-[14px] bg-white/20"><Smile size={22} aria-hidden="true" /></span>}
            </div>
            <h2 className="-mt-1 text-[21px] font-bold leading-[1.2]">
              {isSupported && stage ? stageTitles[stage] : '현재 지원하는 연령이 아니에요'}
            </h2>
            <p className="mt-1 text-[12px] leading-[1.4] text-white/95">
              {isSupported && stage ? isMock ? stageBannerSubtitles[stage] : '성장 단계에 맞는 안전점검 기준을 서버에서 확인했어요. 기기 적용은 연동 전입니다.' : '안전 프로필이 적용되지 않았어요.'}
            </p>
          </section>

          <section aria-label="우리 아이 정보" className="rounded-[24px] border border-[#f1f5f9] bg-white p-[21px] shadow-[0_1px_1px_rgba(0,0,0,0.05)]">
            {isEditing ? (
              <form onSubmit={handleEditSubmit} noValidate>
                <div className="flex items-center justify-between">
                  <h2 className="text-[16px] font-medium">우리 아이 정보 수정</h2>
                  <button type="button" onClick={() => setIsEditing(false)} aria-label="수정 취소" className="grid size-6 place-items-center text-[#64748b] focus-visible:outline-[#a50034]"><X size={16} /></button>
                </div>
                <div className="mt-4 flex flex-col gap-3">
                  <div className="flex flex-col gap-1.5">
                    <label htmlFor="edit-child-name" className="text-[12px] font-medium text-[#334155]">아이 이름 또는 애칭</label>
                    <input
                      id="edit-child-name"
                      type="text"
                      autoComplete="off"
                      maxLength={50}
                      value={editName}
                      onChange={(event) => { setEditName(event.target.value); setEditFieldErrors({}); setEditError('') }}
                      disabled={saving}
                      aria-invalid={!!editFieldErrors.name || (!!editError && !editName.trim())}
                      aria-describedby={editFieldErrors.name ? 'edit-child-name-error' : undefined}
                      className="h-[45px] w-full rounded-xl border border-[#e2e8f0] bg-white px-[15px] text-[14px] text-[#1e293b] focus:outline-none focus:ring-2 focus:ring-[#a50034]/20 disabled:opacity-60"
                    />
                    {editFieldErrors.name && <p id="edit-child-name-error" role="alert" className="text-[11px] text-[#a50034]">{editFieldErrors.name}</p>}
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label htmlFor="edit-child-birthday" className="text-[12px] font-medium text-[#334155]">생년월일</label>
                    <input
                      id="edit-child-birthday"
                      type="date"
                      value={editBirthDate}
                      max={localToday()}
                      onChange={(event) => { setEditBirthDate(event.target.value); setEditFieldErrors({}); setEditError('') }}
                      disabled={saving}
                      aria-invalid={!!editFieldErrors.birthDate || (!!editError && (!editBirthDate || editBirthDate > localToday()))}
                      aria-describedby={editFieldErrors.birthDate ? 'edit-child-birthday-error' : undefined}
                      className="h-[45px] w-full rounded-xl border border-[#e2e8f0] bg-white px-[15px] text-[14px] text-[#1e293b] focus:outline-none focus:ring-2 focus:ring-[#a50034]/20 disabled:opacity-60"
                    />
                    {editFieldErrors.birthDate && <p id="edit-child-birthday-error" role="alert" className="text-[11px] text-[#a50034]">{editFieldErrors.birthDate}</p>}
                    <p className="text-[11px] leading-[1.4] text-[#94a3b8]">생년월일을 바꾸면 성장 단계와 Safety Profile 기준이 다시 계산돼요.</p>
                  </div>
                </div>

                {editError && <p role="alert" className="mt-2 text-[12px] text-[#a50034]">{editError}</p>}

                <button type="submit" disabled={saving} className="mt-4 flex min-h-[45px] w-full items-center justify-center rounded-xl bg-gradient-to-r from-[#dc3f6e] to-[#f2789c] text-[15px] font-bold text-white disabled:cursor-wait disabled:opacity-80">
                  {saving ? '저장 중…' : '수정 완료'}
                </button>
                <button type="button" onClick={() => setIsEditing(false)} disabled={saving} className="mt-2 w-full text-center text-[12px] text-[#94a3b8] disabled:opacity-60">취소</button>
              </form>
            ) : (
              <>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Smile size={20} className="text-[#0f172a]" aria-hidden="true" />
                    <h2 className="text-[16px] font-medium">우리 아이 정보</h2>
                  </div>
                  <button type="button" onClick={startEditing} className="flex items-center gap-1 text-[12px] font-medium text-[#2563eb] focus-visible:outline-[#a50034]">수정하기 <Pencil size={13} /></button>
                </div>
                <div className="mt-4 flex flex-wrap items-center justify-between gap-2 rounded-[16px] border border-[#f1f5f9] bg-[#f8fafc]/80 p-[15px]">
                  <div className={`flex flex-1 items-center ${child.name.length > 8 ? 'min-w-[14rem]' : 'min-w-[10rem]'}`}>
                    <div className="relative grid size-12 shrink-0 place-items-center rounded-full bg-[#fecdd3]">
                      <Smile size={22} className="text-[#e11d48]" aria-hidden="true" />
                      <span className="absolute -bottom-0.5 -right-0.5 grid size-4 place-items-center rounded-full border-2 border-white bg-[#10b981]"><Check size={10} className="text-white" strokeWidth={3} /></span>
                    </div>
                    <div className="min-w-0 flex-1 pl-3">
                      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                        <span className="min-w-0 text-[16px] font-medium">{child.name}</span>
                        <span className="shrink-0 rounded-[6px] bg-[#ffe4e6] px-2 py-[2px] text-[11px] font-bold text-[#e11d48]">{profile.ageMonths}개월</span>
                      </div>
                      <p className="text-[12px] text-[#64748b]">생년월일 <span className="text-[#334155]">{child.birthDate.replaceAll('-', '.')}</span></p>
                    </div>
                  </div>
                  <span className="flex shrink-0 items-center gap-1.5 rounded-full bg-[#eff6ff] px-[10px] py-1 text-[12px] font-medium text-[#2563eb]"><span className="size-1.5 rounded-full bg-[#3b82f6]" />{isSupported && stage ? stageLabels[stage] : '지원 범위 밖'}</span>
                </div>
              </>
            )}
          </section>

          <section aria-label="성장단계별 안전점검 기준" className="space-y-4 rounded-[24px] border border-[#f1f5f9] bg-white p-[21px] shadow-sm">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <ListChecks size={20} className="text-[#0f172a]" aria-hidden="true" />
                <h2 className="text-[16px] font-medium">성장단계별 안전점검 기준</h2>
              </div>
              <span className="shrink-0 rounded-full border border-[#fecdd3] bg-[#fff1f2] px-3 py-[5px] text-[11px] font-bold text-[#8b0a2d]">Safety Profile</span>
            </div>

            <div className="rounded-2xl border-2 border-[#10b981] bg-[#ecfdf5]/60 p-[18px]">
              <div className="flex items-center">
                <span className="grid size-8 shrink-0 place-items-center rounded-full bg-[#10b981] text-[14px] font-bold text-white">{stage ? stageOrder[stage] : '-'}</span>
                <div className="min-w-0 flex-1 pl-2.5">
                  <div className="flex min-w-0 items-center gap-1.5">
                    <span className="min-w-0 text-[14px] font-medium">{stage ? `${stageOrder[stage]}. ${profileDetail?.stageLabel ?? stageLabels[stage]}` : '현재 지원하는 연령이 아니에요'}</span>
                    {isSupported && <span className="shrink-0 rounded-full bg-[#10b981] px-2 py-[2px] text-[9px] text-white">{isMock ? '현재 적용' : '서버 기준'}</span>}
                  </div>
                  {stage && <p className="text-[12px] font-bold text-[#047857]">{stageAgeRangeLabels[stage]} · {profile.ageMonths}개월 현재</p>}
                </div>
              </div>
              {stage ? (
                <div className="mt-3 space-y-2 border-t border-[#a7f3d0]/70 pt-3">
                  {categoryList.map((category) => {
                    const risk = riskByStage[stage][category]
                    return (
                      <div key={category} className="flex items-center justify-between gap-2 text-[12px]">
                        <span className="min-w-0"><span className="font-medium text-[#0f172a]">{categoryLabels[category]}</span><span className="ml-1.5 text-[11px] text-[#64748b]">{categoryExamples[category]}</span></span>
                        <span className={`shrink-0 rounded-full px-2.5 py-[2px] text-[11px] font-bold ${riskStyles[risk].chip}`}>{riskLabels[risk]}</span>
                      </div>
                    )
                  })}
                </div>
              ) : (
                <p className="mt-3 border-t border-[#a7f3d0]/70 pt-3 text-[12px] text-[#475569]">지원되는 안전점검 기준이 없어요.</p>
              )}
              {profileError && (
                <div role="alert" className="mt-3 border-t border-[#a7f3d0]/70 pt-3 text-[12px] text-[#9f1239]">
                  <p>{profileError}</p>
                  <button type="button" onClick={() => setProfileReloadKey((value) => value + 1)} className="mt-2 font-bold underline underline-offset-2 focus-visible:outline-[#a50034]">다시 시도</button>
                </div>
              )}
            </div>

            <div>
              <h3 className="mb-2 text-[13px] font-bold text-[#0f172a]">성장 단계에 따른 위험도 기준</h3>
              <div className="grid grid-cols-[92px_1fr_1fr] items-end gap-x-2 gap-y-2 text-center">
                <span />
                {categoryList.map((category) => (
                  <div key={category} className="min-w-0">
                    <p className="text-[12px] font-bold text-[#0f172a]">{categoryLabels[category]}</p>
                    <p className="text-[10px] text-[#94a3b8]">{categoryExamples[category]}</p>
                  </div>
                ))}
                {stageList.map((rowStage) => {
                  const current = stage === rowStage
                  return (
                    <div key={rowStage} className="contents">
                      <span className={`break-keep rounded-xl px-1.5 py-2 text-left text-[11px] font-bold leading-[1.3] ${current ? 'bg-[#ecfdf5] text-[#047857] ring-2 ring-[#10b981]' : 'text-[#475569]'}`}>{stageLabels[rowStage]}{current && <span className="block text-[9px] font-medium">현재 단계</span>}</span>
                      {categoryList.map((category) => {
                        const risk = riskByStage[rowStage][category]
                        return <span key={category} className={`grid place-items-center rounded-xl py-2 text-[12px] font-bold ${riskStyles[risk].chip} ${current ? 'ring-2 ring-[#10b981]' : ''}`}>{riskLabels[risk]}</span>
                      })}
                    </div>
                  )
                })}
              </div>
              <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-[#f1f5f9] pt-3 text-[11px] text-[#64748b]">
                {(['VERY_HIGH', 'HIGH', 'MEDIUM'] as const).map((risk) => (
                  <span key={risk} className="inline-flex items-center gap-1"><span className={`size-2 rounded-full ${riskStyles[risk].dot}`} />{riskLabels[risk]}</span>
                ))}
                <span className="text-[#94a3b8]">· 매우 높음·높음은 긴급, 보통은 주의로 알려요</span>
              </div>
            </div>

            <div className="flex items-start gap-2.5 rounded-2xl border border-[#dbeafe] bg-[#eff6ff]/60 p-[13px]">
              <Info size={16} className="mt-0.5 shrink-0 text-[#1e3a8a]" aria-hidden="true" />
              <p className="text-[11px] leading-[1.5] text-[#334155]"><strong className="text-[#1e3a8a]">ThinQ 자동 연동 안내:</strong> 아이 생년월일을 등록하면 성장단계에 맞춰 로봇청소기의 안전점검 대상과 기준이 자동으로 변경됩니다.</p>
            </div>
          </section>
        </main>
      </div>
    </div>
  )
}
