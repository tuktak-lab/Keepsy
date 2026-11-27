// 스탭 · PIN · 기능 잠금 화면 계산 (순수 함수). 규칙은 docs CONTRACTS 3장 「기능 키」, 4장 ACC
// (POST · PATCH /api/staff, PUT /api/staff/{staffId}/pin, PUT /api/feature-locks, PUT /api/owner-pin). 서버가 다시 검사한다 (NFR-08).
import { t } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';
import type { Access } from './session';

/** PIN은 숫자 4~6자리 (C21) */
export const PIN_FORMAT = /^\d{4,6}$/;
export const NAME_MAX = 50;

/** 스탭 이름 오류 사유. 없으면 null */
export function validateStaffName(name: string): string | null {
  const n = name.trim();
  if (!n) return t('settings.staff.vName');
  if (n.length > NAME_MAX) return t('settings.staff.vNameMax', { max: NAME_MAX });
  return null;
}

/** PIN 오류 사유. 없으면 null */
export const validatePin = (pin: string): string | null => (PIN_FORMAT.test(pin) ? null : t('settings.staff.vPin'));

/** 스탭 등록 칸 */
export type NewStaffValues = { name: string; pin: string; lockedFeatureAllowed: boolean };

export const validateNewStaff = (v: NewStaffValues): string | null => validateStaffName(v.name) ?? validatePin(v.pin);

export const toNewStaffRequest = (v: NewStaffValues) => ({ name: v.name.trim(), pin: v.pin, lockedFeatureAllowed: v.lockedFeatureAllowed });

/** 원장 PIN 재설정 칸. 새 PIN은 두 번 받아 오타를 막는다 */
export type OwnerPinValues = { password: string; pin: string; pinConfirm: string };

export function validateOwnerPin(v: OwnerPinValues): string | null {
  if (!v.password) return t('settings.staff.vPassword');
  const pin = validatePin(v.pin);
  if (pin) return pin;
  if (v.pin !== v.pinConfirm) return t('settings.staff.vPinConfirm');
  return null;
}

// ── 기능별 공용/PIN (FR-ACC-05, CONTRACTS 3장 「기능 키」) ──
// 순서와 설명은 CONTRACTS 3장 표를 따른다. fixed: 바꿀 수 없는 항목 (E8)
// label은 읽을 때 정한다 (언어가 모듈을 불러온 뒤에 정해지므로 getter)
const feature = (key: string, label: MessageKey, fixed: boolean) => ({
  key,
  get label() {
    return t(label);
  },
  fixed,
});
export const FEATURES: { key: string; label: string; fixed: boolean }[] = [
  feature('DASHBOARD_SEARCH', 'settings.feature.dashboard', true),
  feature('RESERVATION', 'settings.feature.reservation', false),
  feature('INSIGHT', 'settings.feature.insight', false),
  feature('CUSTOMER_VIEW', 'settings.feature.customerView', false),
  feature('CUSTOMER_WRITE', 'settings.feature.customerWrite', false),
  feature('CONSENT', 'settings.feature.consent', true),
  feature('CONTACT_FULL', 'settings.feature.contactFull', false),
  feature('CUSTOMER_EDIT', 'settings.feature.customerEdit', false),
  feature('SALES_ANALYTICS', 'settings.feature.sales', false),
  feature('PAYMENT_EDIT', 'settings.feature.paymentEdit', false),
  feature('CAMPAIGN_VIEW', 'settings.feature.campaignView', false),
  feature('CAMPAIGN_SEND', 'settings.feature.campaignSend', false),
  feature('OWNER_PIN', 'settings.feature.ownerPin', true),
];

export const ACCESS_LABEL: Record<Access, string> = {
  get PUBLIC() {
    return t('settings.access.public');
  },
  get PIN() {
    return t('settings.access.pin');
  },
  get OWNER_PIN() {
    return t('settings.access.ownerPin');
  },
};

/** 바꾼 토글만 골라 PUT /api/feature-locks 본문으로 (보낸 키만 바뀐다, F38). 고정 항목은 보내지 않는다 */
export function changedFeatures(saved: Record<string, Access>, edited: Record<string, Access>): Record<string, 'PUBLIC' | 'PIN'> {
  const out: Record<string, 'PUBLIC' | 'PIN'> = {};
  for (const f of FEATURES) {
    if (f.fixed) continue;
    const v = edited[f.key];
    if ((v === 'PUBLIC' || v === 'PIN') && v !== saved[f.key]) out[f.key] = v;
  }
  return out;
}
