// 예약 취소 · 노쇼 처리 (FR-RSV-03). 요청 규칙은 docs CONTRACTS 6장 「예약 (RSV)」
// POST /api/reservations/{id}/cancel · /no-show: 본문 없음, 성공하면 204, 확정 예약만. 예약 캘린더와 예약 상세(#128)가 함께 쓴다.

export type StatusAction = 'cancel' | 'no-show';

export const actionPath = (reservationId: number, action: StatusAction): string => `/api/reservations/${reservationId}/${action}`;

export const ACTION_TEXT: Record<StatusAction, { button: string; done: string }> = {
  cancel: { button: '예약 취소', done: '예약을 취소했어요.' },
  'no-show': { button: '노쇼 처리', done: '노쇼로 처리했어요.' },
};

/** 노쇼는 예약 시작 시각이 지난 뒤에만 처리할 수 있다 (C8). 서버가 다시 검사한다 */
export const canMarkNoShow = (startAtMs: number, nowMs: number): boolean => startAtMs <= nowMs;

export const NO_SHOW_TOO_EARLY = '예약 시작 시각이 지난 뒤에만 노쇼로 처리할 수 있어요';

/** 되돌릴 수 없는 처리라 한 번 더 묻는 문장 */
export function confirmText(action: StatusAction, hasCustomer: boolean): string {
  if (action === 'cancel') return '이 예약을 취소할까요? 취소하면 그 시간이 다시 비고, 취소한 예약은 되돌릴 수 없어요.';
  return hasCustomer
    ? '노쇼로 처리할까요? 그 고객의 노쇼 횟수가 1회 늘고, 처리한 뒤에는 되돌릴 수 없어요.'
    : '노쇼로 처리할까요? 고객 없는 예약이라 어느 고객의 노쇼 횟수에도 잡히지 않아요. 그 시간은 다시 예약할 수 있게 풀려요.';
}
