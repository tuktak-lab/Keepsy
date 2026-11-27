'use client';

import { Fragment, useEffect, useSyncExternalStore, type ReactNode } from 'react';
import { LOCALE_PENDING_ATTR, locale, readStoredLocale, setLocale, type Locale } from '@/lib/i18n';

// 언어는 바꾸면 새로고침하므로(changeLocale) 구독할 변화가 없다
const noSubscribe = () => () => {};
const serverLocale = (): Locale => 'ko';

// 저장된 화면 언어를 적용한다 (FR-I18N-02, F74). hydration 때는 미리 만든 HTML과 같은 한국어로 그리고,
// 저장된 언어가 다르면 React가 바로 다시 그릴 때 언어를 바꾸고 key로 앱 전체를 한 번 다시 마운트한다.
// ponytail: 다시 마운트하면 화면을 연 직후의 데이터 요청이 한 번 더 나간다(한국어가 아닐 때만). 문제가 되면 첫 요청을 언어 적용 뒤로 미룬다
export function LocaleRoot({ children }: { children: ReactNode }) {
  const stored = useSyncExternalStore(noSubscribe, readStoredLocale, serverLocale);
  // 자식이 그리기 전에 t()가 읽는 현재 언어를 맞춘다. 같은 값이면 아무 일도 하지 않는다
  if (locale() !== stored) setLocale(stored);

  useEffect(() => {
    if (stored !== readStoredLocale()) return; // hydration 직후, 저장된 언어로 다시 그리기 전
    document.documentElement.lang = stored;
    document.documentElement.removeAttribute(LOCALE_PENDING_ATTR);
  }, [stored]);

  return <Fragment key={stored}>{children}</Fragment>;
}
