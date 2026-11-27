'use client';

import { changeLocale, isLocale, LOCALE_NAMES, LOCALES, locale, t } from '@/lib/i18n';

// 화면 언어 선택 (FR-I18N-02). 브라우저별 저장, 바꾸면 새로고침한다 (i18n.ts changeLocale).
// 상단바(로그인 뒤)와 로그인 · 가입 같은 계정 화면이 같이 쓴다.
export function LocaleSelect() {
  return (
    <select
      aria-label={t('locale.label')}
      value={locale()}
      onChange={(e) => {
        if (isLocale(e.target.value)) changeLocale(e.target.value);
      }}
      style={{
        height: '32px',
        padding: '0 8px',
        borderRadius: '999px',
        border: '1px solid rgba(255,255,255,0.9)',
        background: 'rgba(255,255,255,0.55)',
        boxShadow: '0 0 0 1px rgba(150,100,108,0.12)',
        font: "400 12.5px 'Noto Sans KR', sans-serif",
        color: '#4A3F3B',
        cursor: 'pointer',
      }}
    >
      {LOCALES.map((l) => (
        <option key={l} value={l}>
          {LOCALE_NAMES[l]}
        </option>
      ))}
    </select>
  );
}

/** 로그인 전 화면처럼 상단바가 없는 곳: 화면 오른쪽 위 구석에 둔다 */
export function LocaleCorner() {
  return (
    <div style={{ position: 'fixed', top: 16, right: 20, zIndex: 20 }}>
      <LocaleSelect />
    </div>
  );
}
