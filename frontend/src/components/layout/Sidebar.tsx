'use client';

import { useLayoutEffect, useState } from 'react';
import { usePathname } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import { activeMenu } from '@/lib/shellNav';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';

// Next.js는 이미지 import를 { src, width, height } 객체로 준다 (Vite는 URL 문자열).
const logo = logoImg.src;

const NAV: [string, MessageKey, string, string][] = [
  ['dashboard', 'shell.nav.dashboard', '/dashboard', 'M4 11l8-7 8 7 M6 10v10h12V10 M10 20v-5h4v5'],
  [
    'schedule',
    'shell.nav.schedule',
    '/reservations',
    'M5 4h14a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M3 10h18 M8 2v4 M16 2v4',
  ],
  [
    'customers',
    'shell.nav.customers',
    '/customers',
    'M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z M2.5 20c0-3.6 3-6 6.5-6s6.5 2.4 6.5 6 M17 11a2.5 2.5 0 1 0 0-5 M18 14c2.5.3 4 2.2 4 5',
  ],
  // 인사이트: Lucide rotate-cw (화살표 머리가 원호 끝에 맞게 이어짐)
  ['insights', 'shell.nav.insights', '/insights', 'M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8 M21 3v5h-5'],
  ['campaigns', 'shell.nav.campaigns', '/campaigns', 'M21 3L3 10.5l7 3 3 7z M21 3L10 13.5'],
  ['performance', 'shell.nav.performance', '/analytics', 'M5 20v-8 M12 20V5 M19 20v-5'],
  ['settings', 'shell.nav.settings', '/settings', 'M4 9l1.5-5h13L20 9 M4 9v11h16V9 M9 20v-6h6v6'],
];

const ITEM = 54; // 메뉴 한 칸 높이
const GAP = 8;

// 사이드바는 공통 셸(app/(shop)/layout.tsx)에서 한 번만 그려지고 화면을 옮겨도 유지된다.
// 활성 메뉴는 경로에서 정하고, 표시(배경 + 왼쪽 선)는 이전 위치에서 새 메뉴로 부드럽게 옮긴다.
export function Sidebar() {
  const active = activeMenu(usePathname());
  const idx = Math.max(
    0,
    NAV.findIndex(([id]) => id === active),
  );
  const [markIdx, setMarkIdx] = useState(idx);
  const [moving, setMoving] = useState(false);

  useLayoutEffect(() => {
    if (markIdx !== idx) {
      // 이전 위치에 먼저 그린 뒤 다음 프레임에 이동
      const r = requestAnimationFrame(() => {
        setMoving(true);
        setMarkIdx(idx);
      });
      return () => cancelAnimationFrame(r);
    }
  }, [idx, markIdx]);

  return (
    <nav aria-label={t('shell.nav.label')} className="kp-nav">
      <div className="kp-nav-logo">
        <img src={logo} alt="Keepsy" />
        <span>SMART BEAUTY CRM</span>
      </div>

      <div className="kp-nav-list">
        <span
          className={'kp-nav-mark' + (moving ? ' moving' : '')}
          style={{ transform: `translateY(${markIdx * (ITEM + GAP)}px)` }}
          aria-hidden="true"
        ></span>
        {NAV.map(([id, label, href, d]) => {
          const on = id === active;
          return (
            <AppLink href={href} aria-current={on ? 'page' : 'false'} className={'kp-nav-item' + (on ? ' on' : '')} key={id}>
              <span className="kp-nav-ico">
                <svg
                  width="21"
                  height="21"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <path d={d}></path>
                </svg>
              </span>
              <span style={{ flex: 1 }}>{t(label)}</span>
            </AppLink>
          );
        })}
      </div>

      <div className="kp-nav-card">
        <img src={logo} alt="Keepsy" style={{ height: '15px', width: 'auto', verticalAlign: '-2px', marginRight: '3px' }} />{t('shell.nav.promo.with')}
        <br />
        {t('shell.nav.promo.line2')}
        <br />
        {t('shell.nav.promo.line3')}
        <AppLink href="/insights" aria-label={t('shell.nav.promo.go')} className="kp-nav-go">
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M5 12h14 M13 6l6 6-6 6"></path>
          </svg>
        </AppLink>
      </div>
    </nav>
  );
}
