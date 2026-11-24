import { PinGate } from '@/components/common/PinGate';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { DayOffSection } from './DayOffSection';
import { MenuSection } from './MenuSection';
import { SettingsSections } from './SettingsSections';
import { ShopSection } from './ShopSection';
import styleText from './SettingsPage.css?inline';

// 매장 설정 (FR-SET-01 · 02 · 03 · 04 · 06 · 07). 화면 전체가 원장 PIN 기능이다 (OWNER_PIN, PinGate).
// 이번 단계: 매장 정보 · 영업시간, 시술 메뉴 · 기본 주기, 휴무일, 예측 상태 기준, 발송 설정, 보관 기간.
// 스탭 · PIN · 기능 잠금(FR-SET-05, FR-ACC-03~05 · 09)은 이어지는 PR에서 이 화면에 더한다.
// 구역마다 저장 API가 달라 저장 버튼도 구역별이다: 매장 정보 → PUT /api/shop, 시술 메뉴 → /api/service-menus · /api/category-cycles,
// 휴무일 → /api/day-offs · PATCH /api/staff/{staffId}, 나머지 → PUT /api/settings.

export default function SettingsPage() {
  useStyleSheet('SettingsPage', styleText);

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', minHeight: '1200px' }}>
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="settings" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: '0', display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb="파트너 센터 / 매장 설정" />
        <PinGate id="settings" title="매장 설정">
          <div className="st-wrap">
            <div>
              <h1 className="st-h1">매장 설정</h1>
              <p className="st-sub">매장 정보와 영업시간, 예측 기준, 발송 규칙, 개인정보 보관 기간을 관리해요.</p>
            </div>
            <nav className="st-nav" aria-label="설정 구역">
              <a href="#st-shop">매장 정보 · 영업시간</a>
              <a href="#st-menu">시술 메뉴 · 기본 주기</a>
              <a href="#st-dayoff">휴무일</a>
              <a href="#st-status">예측 상태 기준</a>
              <a href="#st-send">발송 설정</a>
              <a href="#st-privacy">개인정보 보관</a>
            </nav>
            <div className="st-note">설정 화면은 원장 PIN으로만 열려요. 이 화면의 모든 변경은 항상 원장 PIN이 필요해요.</div>

            <ShopSection />
            <MenuSection />
            <DayOffSection />
            <SettingsSections />
          </div>
        </PinGate>
      </main>
    </div>
  );
}
