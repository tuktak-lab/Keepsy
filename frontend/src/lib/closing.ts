// 시술 마감 카드 계산 (FR-PAY-04, 순수 함수). 요청 규칙은 docs CONTRACTS 6장 「시술 마감 · 결제 (PAY)」
// POST /api/reservations/{id}/closing: 결제 · 방문 생성 · 방문 메모 · 예약 완료를 한 번에 저장한다.
// 완료된 예약에는 결제(추가 결제) · 메모만 더하고, 실제 시술은 보내지 않는다 (E4).

export type PaymentMethod = 'CARD' | 'CASH' | 'TRANSFER' | 'ETC';

/** CONTRACTS 3장 「결제 수단」 */
export const PAYMENT_METHODS: [PaymentMethod, string][] = [
  ['CARD', '카드'],
  ['CASH', '현금'],
  ['TRANSFER', '계좌이체'],
  ['ETC', '기타'],
];

export type ClosingService = { serviceMenuId: number; staffId: number };

export type ClosingValues = {
  services: ClosingService[];
  amount: string; // 입력 칸의 글자 그대로 (쉼표 · 공백 허용)
  method: PaymentMethod;
  memo: string;
};

/** 실제 시술 가격 합계(원). 결제 금액의 기본값 */
export const sumPrices = (prices: number[]): number => prices.reduce((a, b) => a + b, 0);

/** 금액 입력 → 0 이상의 정수. 비었거나 숫자가 아니면 null */
export function parseAmount(text: string): number | null {
  const cleaned = text.replace(/[\s,]/g, '');
  if (!/^\d+$/.test(cleaned)) return null;
  const n = Number(cleaned);
  return Number.isSafeInteger(n) ? n : null;
}

export type ClosingMode = {
  completed: boolean; // 이미 완료된 예약이면 추가 결제 · 메모만 (E4)
  hasCustomer: boolean; // 고객 없는 방문은 메모를 받지 않는다 (E3)
};

/** 저장 전에 화면에서 먼저 거르는 입력 오류. 첫 번째 사유를 돌려주고, 없으면 null. 서버가 다시 검사한다 (NFR-08) */
export function validateClosing(v: ClosingValues, mode: ClosingMode): string | null {
  if (!mode.completed) {
    if (v.services.length === 0) return '실제 시술을 1개 이상 선택해 주세요';
    if (v.services.some((s) => !s.staffId)) return '시술마다 담당 스탭을 선택해 주세요';
  }
  if (parseAmount(v.amount) === null) return '결제 금액을 0 이상의 숫자로 입력해 주세요';
  if (v.memo.length > 1000) return '메모는 1,000자 이하입니다';
  return null;
}

/** 저장 요청 본문. 완료된 예약은 시술을 보내지 않고(서버가 `invalid`로 거절), 고객이 없으면 메모를 보내지 않는다 */
export function toClosingRequest(v: ClosingValues, mode: ClosingMode) {
  const memo = mode.hasCustomer ? v.memo.trim() : '';
  return {
    ...(mode.completed ? {} : { services: v.services.map((s) => ({ serviceMenuId: s.serviceMenuId, staffId: s.staffId })) }),
    amount: parseAmount(v.amount),
    method: v.method,
    memo: memo || null,
  };
}
