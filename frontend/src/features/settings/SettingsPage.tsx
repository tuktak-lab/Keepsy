import { useState } from 'react';
import { PinGate } from '@/components/common/PinGate';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { FeatureLockSection, OwnerPinSection } from './AccessSection';
import { DayOffSection } from './DayOffSection';
import { MenuSection } from './MenuSection';
import { SettingsSections } from './SettingsSections';
import { ShopSection } from './ShopSection';
import { StaffSection } from './StaffSection';
import styleText from './SettingsPage.css?inline';

// 매장 설정 (FR-SET-01~07, FR-ACC-03~05 · 09). 화면 전체가 원장 PIN 기능이다 (OWNER_PIN, PinGate).
// 구역: 매장 정보 · 영업시간, 시술 메뉴 · 기본 주기, 휴무일, 예측 상태 기준, 발송 설정, 보관 기간, 스탭 · PIN, 기능별 공용/PIN.
// 원장 PIN 재설정(FR-ACC-09)만은 원장 PIN을 잊었을 때 쓰므로 PinGate 바깥에 둔다 (매장 세션이면 되는 API, PUT /api/owner-pin).
// 구역마다 저장 API가 달라 저장 버튼도 구역별이다: 매장 정보 → PUT /api/shop, 시술 메뉴 → /api/service-menus · /api/category-cycles,
// 휴무일 → /api/day-offs · PATCH /api/staff/{staffId}, 스탭 → /api/staff, 기능 잠금 → PUT /api/feature-locks, 나머지 → PUT /api/settings.

export default function SettingsPage() {
  useStyleSheet('SettingsPage', styleText);
  // 켜 둔 매장 업종: 「매장 정보」가 알려 주고 「시술 메뉴 · 기본 주기」가 그것만 보여 준다. undefined 불러오는 중, null 모름(모두 보여 줌)
  const [industries, setIndustries] = useState<string[] | null | undefined>(undefined);

  return (
    <>
      <PinGate id="settings" title="매장 설정">
        <div className="st-wrap">
          <div>
            <h1 className="st-h1">매장 설정</h1>
            <p className="st-sub">매장 정보와 영업시간, 예측 기준, 발송 규칙, 개인정보 보관 기간을 관리해요.</p>
          </div>
          <nav className="st-nav" aria-label="설정 구역" data-guide="settings-nav">
            <a href="#st-shop">매장 정보 · 영업시간</a>
            <a href="#st-menu">시술 메뉴 · 기본 주기</a>
            <a href="#st-dayoff">휴무일</a>
            <a href="#st-status">예측 상태 기준</a>
            <a href="#st-send">발송 설정</a>
            <a href="#st-privacy">개인정보 보관</a>
            <a href="#st-staff">스탭 · PIN</a>
            <a href="#st-lock">기능별 공용 / PIN</a>
          </nav>
          <div className="st-note">설정 화면은 원장 PIN으로만 열려요. 이 화면의 모든 변경은 항상 원장 PIN이 필요해요.</div>

          <ShopSection onIndustries={setIndustries} />
          <MenuSection industries={industries} />
          <DayOffSection />
          <SettingsSections />
          <StaffSection />
          <FeatureLockSection />
        </div>
      </PinGate>
      <div className="st-wrap" style={{ paddingTop: 0 }}>
        <OwnerPinSection />
      </div>
    </>
  );
}
