import { intlLocale, t } from './i18n.ts';

// 날짜 · 시각 표시는 Asia/Seoul 기준 (NFR-12, CONTRACTS 3장).
// API의 날짜는 이미 서울 기준 `YYYY-MM-DD`라 그대로 쓰고, 시각(ISO 8601, 오프셋 포함)만 변환한다.

const dateTimeFormat = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** 금액(원 정수) → `58,000원`, 영어는 `₩58,000` (CONTRACTS 3장, F74) */
export const formatWon = (won: number): string => t('format.won', { amount: won.toLocaleString(intlLocale()) });

/** `2026-11-23T05:30:00Z` → `2026-11-23 14:30` */
export function formatDateTime(iso: string): string {
  const parts = Object.fromEntries(dateTimeFormat.formatToParts(new Date(iso)).map((p) => [p.type, p.value]));
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}
