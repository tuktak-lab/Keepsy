'use client';

import { changeLocale, isLocale, LOCALE_CODES, LOCALES, locale, t } from '@/lib/i18n';
import styles from './LocaleSelect.module.css';

// 화면 언어 선택 (FR-I18N-02). 브라우저별 저장, 바꾸면 새로고침한다 (i18n.ts changeLocale).
// 배경 · 테두리 없는 글자형(KOR / ENG)이며 소개 화면, 상단바(로그인 뒤), 로그인 · 가입 같은 계정 화면이 같이 쓴다.
export function LocaleSelect() {
  return (
    <span className={styles.wrap}>
      <select
        aria-label={t('locale.label')}
        value={locale()}
        onChange={(e) => {
          if (isLocale(e.target.value)) changeLocale(e.target.value);
        }}
      >
        {LOCALES.map((l) => (
          <option key={l} value={l}>
            {LOCALE_CODES[l]}
          </option>
        ))}
      </select>
    </span>
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
