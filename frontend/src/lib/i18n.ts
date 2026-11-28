// 관리자 화면 언어 전환 (FR-I18N-02, 결정 F74). 언어는 브라우저별로 localStorage에 저장하고 기본은 한국어다.
// 정적 빌드라 미리 만든 HTML은 한국어다. 저장된 언어는 화면이 뜬 뒤 LocaleRoot가 적용하고 앱을 한 번 다시 마운트하므로,
// t()는 훅이 아니라 일반 함수다 (lib의 순수 함수에서도 쓴다). 사용 중 언어를 바꾸면 새로고침한다.
import { en, enErrors } from './messages/en.ts';
import { ko, type MessageKey } from './messages/ko.ts';

export type Locale = 'ko' | 'en';
export const LOCALES: readonly Locale[] = ['ko', 'en'];
/** 언어 선택 목록에 쓰는 줄임 표기. 고른 언어와 상관없이 같다 */
export const LOCALE_CODES: Record<Locale, string> = { ko: 'KOR', en: 'ENG' };
export const LOCALE_STORAGE_KEY = 'keepsy.locale';
/** 저장된 언어가 한국어가 아니면 적용 전까지 <html>에 붙는 표시. 한국어 HTML이 잠깐 보이는 것을 막는다 (app/layout.tsx) */
export const LOCALE_PENDING_ATTR = 'data-locale-pending';

const dictionaries: Record<Locale, Record<MessageKey, string>> = { ko, en };
/** 오류 code별 화면 문구 (F74 ③). 한국어는 서버 문구를 그대로 쓰므로 없다 */
const errorDictionaries: Partial<Record<Locale, Record<string, string>>> = { en: enErrors };
const intlLocales: Record<Locale, string> = { ko: 'ko-KR', en: 'en-US' };

let current: Locale = 'ko';

export const locale = (): Locale => current;
export const intlLocale = (): string => intlLocales[current];

export function isLocale(value: unknown): value is Locale {
  return LOCALES.includes(value as Locale);
}

/** 앱 안에서 현재 언어를 바꾼다. 화면을 다시 그리는 일은 부르는 쪽(LocaleRoot)이 한다 */
export function setLocale(next: Locale): void {
  current = next;
}

/** `t('format.won', { amount: '58,000' })` → `58,000원`. `{이름}` 자리를 vars로 채운다 */
export function t(key: MessageKey, vars?: Record<string, string | number>): string {
  const text = dictionaries[current][key] ?? ko[key];
  return vars ? text.replace(/\{(\w+)\}/g, (all, name: string) => (name in vars ? String(vars[name]) : all)) : text;
}

/** API 오류 안내 문구 (F74 ③): 한국어가 아니면 code로 번역하고, 번역이 없으면 서버 문구를 그대로 쓴다 */
export function errorText(code: string, serverMessage: string): string {
  return errorDictionaries[current]?.[code] ?? serverMessage;
}

export function readStoredLocale(): Locale {
  try {
    const stored = localStorage.getItem(LOCALE_STORAGE_KEY);
    return isLocale(stored) ? stored : 'ko';
  } catch {
    return 'ko';
  }
}

/** 언어 선택: 저장하고 새로고침한다. 입력 중이던 값은 사라진다 (언어 전환은 드문 동작이라 단순하게 둔다) */
export function changeLocale(next: Locale): void {
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, next);
  } catch {
    // 저장할 수 없는 브라우저(사생활 보호 모드 등)는 이번 화면에서만 바꾸지 않고 그대로 둔다
    return;
  }
  location.reload();
}
