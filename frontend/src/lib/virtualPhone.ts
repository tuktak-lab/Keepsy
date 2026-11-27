// 가상 휴대폰 규칙 (FR-CMP-11 · 16). 서버 VirtualPhoneController와 같은 번호 규칙을 화면에서 먼저 본다.

import { t } from './i18n.ts';

/** `010`으로 시작하는 11자리, 하이픈은 있어도 없어도 된다 (CONTRACTS 6장, 고객 phone과 같은 규칙) */
export const PHONE_PATTERN = /^010-?\d{4}-?\d{4}$/;

/** 입력값을 다듬어 맞으면 `010-0000-0000` 모양으로, 틀리면 null */
export function normalizePhone(input: string): string | null {
  const v = input.trim();
  if (!PHONE_PATTERN.test(v)) return null;
  const d = v.replace(/-/g, '');
  return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}`;
}

/** 발송 메시지 종류 (CONTRACTS 3장) */
export const MESSAGE_KIND_LABEL: Record<string, string> = {
  get AD() {
    return t('campaign.kind.ad');
  },
  get UNSUBSCRIBE_NOTICE() {
    return t('customer.msgKind.unsubscribe');
  },
  get CONSENT_RECONFIRM() {
    return t('customer.msgKind.reconfirm');
  },
};
