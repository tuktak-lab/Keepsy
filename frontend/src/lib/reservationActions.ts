// 예약 취소 · 노쇼 처리 (FR-RSV-03). 요청 규칙은 docs CONTRACTS 6장 「예약 (RSV)」
// POST /api/reservations/{id}/cancel · /no-show: 본문 없음, 성공하면 204, 확정 예약만. 예약 캘린더와 예약 상세(#128)가 함께 쓴다.

import { t } from './i18n.ts';
import { monthDayText } from './calendar.ts';

export type StatusAction = 'cancel' | 'no-show';

export const actionPath = (reservationId: number, action: StatusAction): string => `/api/reservations/${reservationId}/${action}`;

export const ACTION_TEXT: Record<StatusAction, { button: string; done: string }> = {
  cancel: {
    get button() {
      return t('reservation.action.cancel');
    },
    get done() {
      return t('reservation.action.cancelDone');
    },
  },
  'no-show': {
    get button() {
      return t('reservation.action.noShow');
    },
    get done() {
      return t('reservation.action.noShowDone');
    },
  },
};

/** 노쇼는 예약 시작 시각이 지난 뒤에만 처리할 수 있다 (C8). 서버가 다시 검사한다 */
export const canMarkNoShow = (startAtMs: number, nowMs: number): boolean => startAtMs <= nowMs;

export const noShowTooEarly = (): string => t('reservation.action.noShowTooEarly');

/** 시술 마감은 예약일(서울, YYYY-MM-DD)이 오늘 이하일 때만. 오늘 예약은 시작 전이어도 된다 (F71). 서버가 다시 검사한다 */
export const canClose = (reservationDate: string, today: string): boolean => reservationDate <= today;

export const closeTooEarly = (reservationDate: string): string =>
  t('reservation.action.closeTooEarly', { date: monthDayText(reservationDate) });

/** 되돌릴 수 없는 처리라 한 번 더 묻는 문장 */
export function confirmText(action: StatusAction, hasCustomer: boolean): string {
  if (action === 'cancel') return t('reservation.action.confirmCancel');
  return hasCustomer
    ? t('reservation.action.confirmNoShow')
    : t('reservation.action.confirmNoShowNoCustomer');
}
