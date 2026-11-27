// 파기 대상 (FR-CUS-12, D3). 기준일 종류와 지난 기간을 화면 글자로 바꾼다.

import { t } from './i18n.ts';

/** 기준일 종류 (CONTRACTS 6장 `GET /api/customers/retention-expired`) */
export type RetentionBaseKind = 'VISIT' | 'RESERVATION' | 'REGISTERED';

export const BASE_KIND_LABEL: Record<RetentionBaseKind, string> = {
  get VISIT() {
    return t('customer.retention.baseVisit');
  },
  get RESERVATION() {
    return t('customer.retention.baseResv');
  },
  get REGISTERED() {
    return t('customer.retention.baseReg');
  },
};

/** 'YYYY-MM-DD' 두 날짜 사이를 "N년 M개월"로. 1개월이 안 되면 "1개월 미만" */
export function elapsedText(base: string, today: string): string {
  const [by, bm, bd] = base.slice(0, 10).split('-').map(Number);
  const [ty, tm, td] = today.slice(0, 10).split('-').map(Number);
  let months = (ty - by) * 12 + (tm - bm) - (td < bd ? 1 : 0);
  if (months < 1) return t('customer.retention.lessThanMonth');
  const years = Math.floor(months / 12);
  months %= 12;
  return [years ? t('customer.retention.years', { n: years }) : '', months ? t('customer.retention.months', { n: months }) : ''].filter(Boolean).join(' ');
}
