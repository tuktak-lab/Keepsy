// 동의 항목 안내 문구와 태블릿 본인 체크의 변경 계산 (FR-CUS-09). 고객 등록 · 태블릿 본인 체크 화면이 같이 쓴다.
import type { ConsentItem } from '../features/customer/types';
import { t } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';

/** 개인정보 수집 · 이용 동의 (필수) */
export const privacyLabel = (): string => t('customer.consent.privacy');

/**
 * 개인정보 동의 고지 문구. 보관 기간은 매장 설정값이다 (GET /api/shop의 `retentionYears`, FR-SET-06 · F40).
 * 아직 모르면(불러오는 중 · 실패) null이고, 그때는 화면이 개인정보 동의를 받지 않는다. 다른 값으로 대신 안내하지 않는다.
 * 이미 받은 동의는 그때의 문구로 받은 것이라, 설정을 바꿔도 이력은 바꾸지 않는다.
 */
export function privacyDesc(retentionYears: number | null | undefined): string | null {
  if (typeof retentionYears !== 'number' || !Number.isInteger(retentionYears) || retentionYears < 1) return null;
  return t('customer.consent.privacyDesc', { years: retentionYears });
}

/** 선택 동의 (CONTRACTS 3장 순서) */
export const OPTIONAL_CONSENTS: [ConsentItem, MessageKey, MessageKey][] = [
  ['PHOTO', 'reservation.consent.photo', 'reservation.consent.photoDesc'],
  ['MARKETING', 'reservation.consent.marketing', 'reservation.consent.marketingDesc'],
  ['NIGHT_AD', 'reservation.consent.nightAd', 'reservation.consent.nightAdDesc'],
  ['HEALTH', 'reservation.consent.health', 'reservation.consent.healthDesc'],
];

/** 동의 항목 이름 (개인정보는 필수 항목, 나머지는 선택 동의) */
export const consentLabel = (item: ConsentItem): string => {
  const optional = OPTIONAL_CONSENTS.find(([i]) => i === item);
  return optional ? t(optional[1]) : privacyLabel();
};

/** GET /api/customers/{id}/consents의 current 항목 */
export type ConsentState = { item: ConsentItem; agreed: boolean; recordedAt: string | null };

/** 바꿀 항목. 동의는 agreed true, 철회는 false (POST /api/customers/{id}/consents 한 건씩) */
export type ConsentChange = { item: ConsentItem; agreed: boolean };

/**
 * 고객이 고른 값과 지금 상태를 비교해 바뀐 항목만 돌려준다.
 * 개인정보 동의 철회는 고객 삭제로만 처리하므로(F42) 동의로 바꾸는 경우만 넣는다.
 */
export function consentChanges(current: ConsentState[], chosen: Partial<Record<ConsentItem, boolean>>): ConsentChange[] {
  const now = Object.fromEntries(current.map((c) => [c.item, c.agreed])) as Partial<Record<ConsentItem, boolean>>;
  const changes: ConsentChange[] = [];
  for (const item of ['PRIVACY', ...OPTIONAL_CONSENTS.map(([i]) => i)] as ConsentItem[]) {
    const want = chosen[item];
    if (want === undefined || want === Boolean(now[item])) continue;
    if (item === 'PRIVACY' && !want) continue;
    changes.push({ item, agreed: want });
  }
  return changes;
}
