// 예약 화면에서 바로 하는 신규 고객 등록 (FR-CUS-01). 요청 규칙은 docs CONTRACTS 6장 「고객 (CUS)」 POST /api/customers.
// 개인정보 수집 · 이용 동의(PRIVACY)가 없으면 서버가 `invalid`로 거절하므로 화면에서도 먼저 막는다 (E3).
// 마케팅 등 나머지 동의는 여기서 받지 않고, 고객 상세에서 받는다.

import { t } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';
import type { AcquisitionChannel, Gender } from '../features/customer/types';

export type ConsentMethod = 'TABLET' | 'PAPER' | 'VERBAL';

/** 동의를 받은 방법. 등록 때 받은 동의 모두에 쓴다 (`IMPORT`는 CSV 전용) */
export const CONSENT_METHODS: [ConsentMethod, MessageKey][] = [
  ['VERBAL', 'customer.method.verbal'],
  ['PAPER', 'customer.method.paper'],
  ['TABLET', 'customer.method.tablet'],
];

export const PHONE_FORMAT = /^010-?\d{4}-?\d{4}$/;

/** 성별 · 유입 경로 선택지. 고객 등록과 기본 정보 수정이 같이 쓴다 */
export const GENDERS: [Gender, MessageKey][] = [
  ['FEMALE', 'customer.gender.female'],
  ['MALE', 'customer.gender.male'],
  ['OTHER', 'customer.gender.other'],
];
export const CHANNELS: [AcquisitionChannel, MessageKey][] = [
  ['INSTAGRAM', 'customer.channel.instagram'],
  ['NAVER_SEARCH', 'customer.channel.naver'],
  ['REFERRAL', 'customer.channel.referral'],
  ['PASSING_BY', 'customer.channel.passing'],
  ['ETC', 'customer.channel.etc'],
];

export type NewCustomerValues = {
  name: string;
  phone: string;
  privacyAgreed: boolean;
  method: ConsentMethod;
};

/** 첫 번째 입력 오류 사유. 없으면 null. 서버가 다시 검사한다 (NFR-08) */
export function validateNewCustomer(v: NewCustomerValues): string | null {
  const name = v.name.trim();
  if (!name) return t('customer.new.needName');
  if (name.length > 50) return t('customer.new.nameMax');
  if (!PHONE_FORMAT.test(v.phone.trim())) return t('customer.new.phoneFormat');
  if (!v.privacyAgreed) return t('customer.new.needPrivacy');
  return null;
}

export function toNewCustomerRequest(v: NewCustomerValues) {
  return {
    name: v.name.trim(),
    phone: v.phone.trim(),
    consents: ['PRIVACY'],
    consentMethod: v.method,
  };
}

/** 검색창에 적은 글자에서 이름 칸에 미리 넣을 값. 숫자만 적었다면 휴대폰 검색이었으니 비운다 */
export const nameFromSearch = (search: string): string => (/^[\d\s-]*$/.test(search) ? '' : search.trim());
