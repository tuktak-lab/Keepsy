// 성과 분석 화면 계산 (순수 함수). 쿼리 규칙은 docs CONTRACTS 4장 「GET /api/analytics/summary」 (FR-ANL-06)

import type { MessageKey } from './messages/ko.ts';

export type Period = 'TODAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'LAST_MONTH' | 'CUSTOM';

export const PERIODS: [Period, MessageKey][] = [
  ['TODAY', 'analytics.period.today'],
  ['THIS_WEEK', 'analytics.period.week'],
  ['THIS_MONTH', 'analytics.period.month'],
  ['LAST_MONTH', 'analytics.period.lastMonth'],
  ['CUSTOM', 'analytics.period.custom'],
];

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** 조회 쿼리. 직접 지정인데 날짜가 비었거나 시작이 끝보다 늦으면 null (서버도 invalid로 거절한다) */
export function periodQuery(period: Period, from: string, to: string): string | null {
  if (period !== 'CUSTOM') return `period=${period}`;
  if (!DATE.test(from) || !DATE.test(to) || from > to) return null;
  return `period=CUSTOM&from=${from}&to=${to}`;
}

export type Funnel = { sentCount: number; reachedCount: number; rebookedCount: number; revisitedCount: number };
