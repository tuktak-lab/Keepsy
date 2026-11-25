// 대시보드 계산 (순수 함수). docs CONTRACTS 4장 GET /api/dashboard/today · booking-channels (FR-DSH-01 · 03 · 06)

type StatusCounts = { CONFIRMED: number; COMPLETED: number; CANCELED: number; NO_SHOW: number };

/** 이번 주 요일별 「예약 건수」: 취소 · 노쇼를 뺀 확정 + 완료 (계약상 어떤 상태를 합칠지는 화면이 정한다) */
export const bookedCount = (c: StatusCounts): number => c.CONFIRMED + c.COMPLETED;

/** 오늘 예약 목록에 보일 예약: 취소 · 노쇼는 뺀다 (응답에는 들어 있다) */
export const isActive = (status: string): boolean => status !== 'CANCELED' && status !== 'NO_SHOW';

/** 0~1 비율 → '42.9%' (소수 첫째 자리) */
export const ratioText = (ratio: number): string => `${Math.round(ratio * 1000) / 10}%`;
