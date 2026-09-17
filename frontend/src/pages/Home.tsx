import Header from '../components/Header'
import ProductStatusCard from '../components/ProductStatusCard'
import GrowthStageBanner from '../components/GrowthStageBanner'
import ReportCard from '../components/ReportCard'
import BottomNav from '../components/BottomNav'

interface HomeProps {
  childName: string
}

export default function Home({ childName }: HomeProps) {
  return (
    <div className="min-h-screen bg-[#f0f5fd] pb-[78px]">
      <Header title="손지아 홈" hasNotification />

      <main className="mx-auto flex max-w-[402px] flex-col gap-4 px-[26px] pt-3">
        <div className="flex items-center justify-between">
          <h2 className="text-[16px] font-semibold text-[#0f172a]">즐겨 찾는 제품</h2>
          <button type="button" className="text-[12px] text-[#94a3b8]">
            전체보기
          </button>
        </div>

        <ProductStatusCard />
        <GrowthStageBanner />
        <ReportCard childName={childName} />
      </main>

      <BottomNav />
    </div>
  )
}
