// 시술 마감 카드 계산 (FR-PAY-04, 순수 함수). 요청 규칙은 docs CONTRACTS 6장 「시술 마감 · 결제 (PAY)」
// POST /api/reservations/{id}/closing: 결제 · 방문 생성 · 방문 메모 · 예약 완료를 한 번에 저장한다.
// 완료된 예약에는 결제(추가 결제) · 메모만 더하고, 실제 시술은 보내지 않는다 (E4).

import { t } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';

export type PaymentMethod = 'CARD' | 'CASH' | 'TRANSFER' | 'ETC';

/** CONTRACTS 3장 「결제 수단」 */
export const PAYMENT_METHODS: [PaymentMethod, MessageKey][] = [
  ['CARD', 'reservation.pay.card'],
  ['CASH', 'reservation.pay.cash'],
  ['TRANSFER', 'reservation.pay.transfer'],
  ['ETC', 'reservation.pay.etc'],
];

/** `cycleExcluded`: 이번 방문만 주기 계산에서 제외 (FR-VIS-05). 고객이 있는 마감에서만 보낸다 */
export type ClosingService = { serviceMenuId: number; staffId: number; cycleExcluded?: boolean };

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
  hasCustomer: boolean; // 고객 없는 방문은 메모 · 주기 계산 제외를 받지 않는다 (E3)
  noCustomer?: boolean; // 고객 없는 예약을 "고객 없음"으로 마감 (E3). 고객이 없고 완료 전일 때만
};

/** 메뉴 예측 속성 (CONTRACTS 3장). `NORMAL`이면 주기 계산 제외를 체크할 때 FR-VIS-05 안내를 띄운다 */
export type PredictionAttribute = 'NORMAL' | 'EXCLUDE' | 'END_SIGNAL';

/** 이 시술에 [이번 시술 주기 계산 제외] 체크를 보여 줄지: 이미 항상 제외인 메뉴는 보여 줄 필요가 없다 */
export const canExcludeFromCycle = (attribute: string): boolean => attribute !== 'EXCLUDE';

/** 체크할 때 「앞으로 이 메뉴를 모두 제외할까요?」를 물을지: 예측 속성이 "일반"인 메뉴만 (FR-VIS-05) */
export const asksMenuExclusion = (attribute: string): boolean => attribute === 'NORMAL';

/** 저장 전에 화면에서 먼저 거르는 입력 오류. 첫 번째 사유를 돌려주고, 없으면 null. 서버가 다시 검사한다 (NFR-08) */
export function validateClosing(v: ClosingValues, mode: ClosingMode): string | null {
  if (!mode.completed) {
    if (v.services.length === 0) return t('reservation.closing.needService');
    if (v.services.some((s) => !s.staffId)) return t('reservation.closing.needStaff');
  }
  if (parseAmount(v.amount) === null) return t('reservation.closing.needAmount');
  if (v.memo.length > 1000) return t('common.memoMax');
  return null;
}

/**
 * 저장 요청 본문. 완료된 예약은 시술을 보내지 않고(서버가 `invalid`로 거절),
 * 고객이 없으면(고객 없음 마감 포함) 메모와 주기 계산 제외를 보내지 않는다 (E3).
 */
export function toClosingRequest(v: ClosingValues, mode: ClosingMode) {
  const memo = mode.hasCustomer ? v.memo.trim() : '';
  return {
    ...(mode.completed
      ? {}
      : {
          services: v.services.map((s) => ({
            serviceMenuId: s.serviceMenuId,
            staffId: s.staffId,
            ...(mode.hasCustomer ? { cycleExcluded: !!s.cycleExcluded } : {}),
          })),
        }),
    ...(mode.noCustomer && !mode.hasCustomer && !mode.completed ? { noCustomer: true } : {}),
    amount: parseAmount(v.amount),
    method: v.method,
    memo: memo || null,
  };
}

// ── 마감 카드의 동의 받기 · 사진 (FR-PAY-04, FR-CUS-08 · 09, F9) ──
// 동의는 POST /api/customers/{customerId}/consents, 사진은 마감 저장 응답의 방문 ID로 POST /api/visits/{visitId}/photos (CONTRACTS 4장)

/** 마감 카드에서 받는 동의 항목 (GET …/closing의 consentsToRequest 값, 3절 순서) */
const consentItem = (label: MessageKey, desc: MessageKey) => ({
  get label() {
    return t(label);
  },
  get desc() {
    return t(desc);
  },
});
export const CONSENT_REQUEST_LABEL: Record<string, { label: string; desc: string }> = {
  PHOTO: consentItem('reservation.consent.photo', 'reservation.consent.photoDesc'),
  MARKETING: consentItem('reservation.consent.marketing', 'reservation.consent.marketingDesc'),
  NIGHT_AD: consentItem('reservation.consent.nightAd', 'reservation.consent.nightAdDesc'),
  HEALTH: consentItem('reservation.consent.health', 'reservation.consent.healthDesc'),
};

/** 한 방문에 올릴 수 있는 사진 수 · 한 장 크기 (서버와 같음) */
export const PHOTO_MAX = 10;
export const PHOTO_MAX_BYTES = 10 * 1024 * 1024;

/** 고른 사진의 첫 번째 오류 사유. 없으면 null. 형식은 서버가 파일 앞 바이트로 다시 판단한다 (NFR-08) */
export function validatePhotos(files: { size: number; type: string }[]): string | null {
  if (files.length > PHOTO_MAX) return t('reservation.photo.tooMany', { max: PHOTO_MAX });
  for (const f of files) {
    if (f.type !== 'image/jpeg' && f.type !== 'image/png') return t('reservation.photo.badType');
    if (f.size === 0) return t('reservation.photo.empty');
    if (f.size > PHOTO_MAX_BYTES) return t('reservation.photo.tooBig');
  }
  return null;
}

// ── 결제 정정 (FR-PAY-02, F76). 고객 상세 결제 이력과 완료 예약의 마감 카드가 같이 쓴다 ──

/** 결제 한 건. 마감 카드 `payments`와 고객 결제 이력 항목의 공통 칸 (CONTRACTS 6장) */
export type PaymentRecord = { paymentId: number; paidAt: string; amount: number; method: PaymentMethod; canceled: boolean };

/** 결제 수단 이름. 모르는 값이면 그대로 */
export const paymentMethodLabel = (method: string): string => {
  const key = PAYMENT_METHODS.find(([v]) => v === method)?.[1];
  return key ? t(key) : method;
};

/** 취소한 결제는 이력으로만 남고 다시 고치거나 취소할 수 없다 (서버도 409, F76) */
export const paymentEditable = (p: Pick<PaymentRecord, 'canceled'>): boolean => !p.canceled;

/** 결제 수정 입력 검사. 비어 있으면 통과 (PATCH /api/payments/{id}: 금액 0 이상의 정수 · 수단 필수) */
export function validatePaymentEdit(amountText: string): string | null {
  return parseAmount(amountText) === null ? t('payment.amountInvalid') : null;
}

/** 결제 취소 확인 문구. 취소는 매출에서만 빠지고 방문 · 예약은 그대로다 (F8). 언어가 정해진 뒤 부르도록 함수로 둔다 */
export const cancelPaymentConfirm = (): string => t('payment.cancelConfirm');
