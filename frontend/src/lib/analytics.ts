// 성과 분석 화면 계산 (순수 함수). 쿼리 규칙은 docs CONTRACTS 4장 「GET /api/analytics/summary」 (FR-ANL-06)

export type Period = 'TODAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'LAST_MONTH' | 'CUSTOM';

export const PERIODS: [Period, string][] = [
  ['TODAY', '오늘'],
  ['THIS_WEEK', '이번 주'],
  ['THIS_MONTH', '이번 달'],
  ['LAST_MONTH', '지난달'],
  ['CUSTOM', '직접 지정'],
];

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** 조회 쿼리. 직접 지정인데 날짜가 비었거나 시작이 끝보다 늦으면 null (서버도 invalid로 거절한다) */
export function periodQuery(period: Period, from: string, to: string): string | null {
  if (period !== 'CUSTOM') return `period=${period}`;
  if (!DATE.test(from) || !DATE.test(to) || from > to) return null;
  return `period=CUSTOM&from=${from}&to=${to}`;
}

export type Funnel = { sentCount: number; reachedCount: number; rebookedCount: number; revisitedCount: number };

/** 「전체」: 캠페인별 퍼널을 단계마다 더한다 (고객은 캠페인마다 따로 센다) */
export const sumFunnels = (list: Funnel[]): Funnel =>
  list.reduce(
    (a, f) => ({
      sentCount: a.sentCount + f.sentCount,
      reachedCount: a.reachedCount + f.reachedCount,
      rebookedCount: a.rebookedCount + f.rebookedCount,
      revisitedCount: a.revisitedCount + f.revisitedCount,
    }),
    { sentCount: 0, reachedCount: 0, rebookedCount: 0, revisitedCount: 0 },
  );
