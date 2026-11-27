'use client';

import { useEffect, useRef } from 'react';
import { usePathname } from 'next/navigation';
import type { Driver } from 'driver.js';
import 'driver.js/dist/driver.css';
import '@/styles/usageGuide.css';
import { t } from '@/lib/i18n';
import { activeMenu } from '@/lib/shellNav';
import { availableSteps, guideSelector } from '@/lib/usageGuide';

// 상단바 [사용 가이드] (#468). 누를 때만 지금 탭의 안내를 Driver.js로 띄운다 (자동 실행 · 진행 저장 없음).
// 안내형이라 강조한 버튼과 배경을 누를 수 없고(disableActiveInteraction · 오버레이 클릭 무시), 화면을 옮기면 끝낸다.

/** 화면에 보이는 대상인지. 숨겨진(display:none) 요소는 강조할 수 없으므로 건너뛴다 */
function visible(target: string): boolean {
  const el = document.querySelector<HTMLElement>(guideSelector(target));
  return !!el && el.getClientRects().length > 0;
}

export function UsageGuideButton() {
  const pathname = usePathname();
  const tour = useRef<Driver | null>(null);

  // 다른 화면으로 옮기거나 상단바가 사라지면 오버레이 · 키보드 이벤트를 정리한다
  useEffect(() => {
    return () => {
      tour.current?.destroy();
      tour.current = null;
    };
  }, [pathname]);

  async function start() {
    tour.current?.destroy();
    // DOM을 쓰는 라이브러리라 누른 뒤에 클라이언트에서만 불러온다 (정적 빌드, ARCHITECTURE 3장)
    const { driver } = await import('driver.js');
    const steps = availableSteps(activeMenu(pathname), visible);
    const d = driver({
      overlayOpacity: 0.5,
      stagePadding: 6,
      stageRadius: 14,
      popoverClass: 'kp-guide',
      allowClose: true,
      overlayClickBehavior: 'none',
      disableActiveInteraction: true,
      allowKeyboardControl: true,
      smoothScroll: true,
      showProgress: steps.length > 1,
      progressText: t('guide.progress'),
      prevBtnText: t('guide.prev'),
      nextBtnText: t('guide.next'),
      doneBtnText: t('guide.done'),
      closeBtnLabel: t('guide.close'),
      onDestroyed: () => {
        if (tour.current === d) tour.current = null;
      },
      // 대상이 없으면(잠긴 화면 · 빈 상태) 안내 한 장만 가운데에 띄운다
      steps: steps.length
        ? steps.map((s) => ({ element: guideSelector(s.target), popover: { title: t(s.title), description: t(s.body) } }))
        : [{ popover: { title: t('guide.empty.title'), description: t('guide.empty.body'), showButtons: ['close' as const] } }],
    });
    tour.current = d;
    d.drive();
  }

  return (
    <button type="button" className="kp-guide-btn" onClick={() => void start()} aria-label={t('guide.buttonLabel')} title={t('guide.buttonLabel')}>
      {/* 물음표 원 (Lucide circle-help, ISC) */}
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <circle cx="12" cy="12" r="10" />
        <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
        <path d="M12 17h.01" />
      </svg>
      {t('guide.button')}
    </button>
  );
}
