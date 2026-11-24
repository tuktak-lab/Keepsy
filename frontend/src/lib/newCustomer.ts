// 예약 화면에서 바로 하는 신규 고객 등록 (FR-CUS-01). 요청 규칙은 docs CONTRACTS 6장 「고객 (CUS)」 POST /api/customers.
// 개인정보 수집 · 이용 동의(PRIVACY)가 없으면 서버가 `invalid`로 거절하므로 화면에서도 먼저 막는다 (E3).
// 마케팅 등 나머지 동의는 여기서 받지 않고, 고객 상세에서 받는다.

export type ConsentMethod = 'TABLET' | 'PAPER' | 'VERBAL';

/** 동의를 받은 방법. 등록 때 받은 동의 모두에 쓴다 (`IMPORT`는 CSV 전용) */
export const CONSENT_METHODS: [ConsentMethod, string][] = [
  ['VERBAL', '구두'],
  ['PAPER', '서면'],
  ['TABLET', '태블릿'],
];

export const PHONE_FORMAT = /^010-?\d{4}-?\d{4}$/;

export type NewCustomerValues = {
  name: string;
  phone: string;
  privacyAgreed: boolean;
  method: ConsentMethod;
};

/** 첫 번째 입력 오류 사유. 없으면 null. 서버가 다시 검사한다 (NFR-08) */
export function validateNewCustomer(v: NewCustomerValues): string | null {
  const name = v.name.trim();
  if (!name) return '이름을 입력해 주세요';
  if (name.length > 50) return '이름은 50자 이하입니다';
  if (!PHONE_FORMAT.test(v.phone.trim())) return '휴대폰 번호는 010으로 시작하는 11자리입니다';
  if (!v.privacyAgreed) return '개인정보 수집 · 이용 동의를 받아야 등록할 수 있어요';
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
