// 매장 가입 3단계 규칙 (FR-ACC-01). 요청 필드는 docs CONTRACTS 4장 POST /api/shops [제안]. 서버가 다시 검사한다 (NFR-08).
// 사업자등록번호 · 주소 · 업종은 모의 데이터로만 받는다: 실제 사업자 정보는 받지 않고 형식만 본다.

// 전화번호 규칙은 매장 설정(lib/settings.ts PHONE_FORMAT, 서버 PUT /api/shop)과 같다. 순수 함수 파일끼리는 런타임 import를 쓰지 않아 여기에 같은 식을 둔다
const PHONE_FORMAT = /^(0\d{1,2}-?\d{3,4}-?\d{4}|1[568]\d{2}-?\d{4})$/;

export type SignUpField = 'email' | 'password' | 'passwordConfirm' | 'ownerName' | 'ownerPin' | 'shopName' | 'businessNumber' | 'phone' | 'address' | 'industries';
/** 글자 칸은 문자열, 업종은 여러 개 선택할 수 있어 값 목록이다 */
export type SignUpValues = Record<Exclude<SignUpField, 'industries'>, string> & { industries: string[] };

export const EMPTY_SIGNUP: SignUpValues = {
  email: '',
  password: '',
  passwordConfirm: '',
  ownerName: '',
  ownerPin: '',
  shopName: '',
  businessNumber: '',
  phone: '',
  address: '',
  industries: [],
};

/** 단계마다 받는 칸. 순서: 개인정보 → 사업자 정보 → 업종(카테고리) */
export const STEPS: { title: string; fields: SignUpField[] }[] = [
  { title: '개인정보', fields: ['email', 'password', 'passwordConfirm', 'ownerName', 'ownerPin'] },
  { title: '사업자 정보', fields: ['shopName', 'businessNumber', 'phone', 'address'] },
  { title: '업종 설정', fields: ['industries'] },
];

export const INDUSTRIES: [string, string][] = [
  ['NAIL', '네일'],
  ['HAIR', '헤어'],
  ['WAXING', '왁싱'],
  ['SKIN_CARE', '피부관리'],
  ['LASH', '속눈썹'],
  ['ETC', '기타'],
];

/** 사업자등록번호: 숫자 10자리를 000-00-00000 모양으로 (입력하는 동안 하이픈을 넣어 준다) */
export function formatBusinessNumber(input: string): string {
  const d = input.replace(/\D/g, '').slice(0, 10);
  return [d.slice(0, 3), d.slice(3, 5), d.slice(5)].filter(Boolean).join('-');
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** 그 단계 칸들의 오류. 비어 있으면 통과 */
export function validateStep(step: number, v: SignUpValues): Partial<Record<SignUpField, string>> {
  const e: Partial<Record<SignUpField, string>> = {};
  for (const f of STEPS[step].fields) {
    const t = f === 'industries' ? '' : v[f].trim();
    switch (f) {
      case 'email':
        if (!EMAIL.test(t) || t.length > 255) e.email = '이메일 형식이 올바르지 않아요 (예: shop@example.com)';
        break;
      case 'password':
        if (v.password.length < 8 || v.password.length > 64) e.password = '비밀번호는 8~64자입니다';
        break;
      case 'passwordConfirm':
        if (v.passwordConfirm !== v.password) e.passwordConfirm = '비밀번호가 서로 다릅니다';
        break;
      case 'ownerName':
        if (!t || t.length > 50) e.ownerName = '대표자 이름은 1~50자입니다';
        break;
      case 'ownerPin':
        if (!/^\d{4,6}$/.test(v.ownerPin)) e.ownerPin = 'PIN은 숫자 4~6자리입니다';
        break;
      case 'shopName':
        if (!t || t.length > 100) e.shopName = '매장명은 1~100자입니다';
        break;
      case 'businessNumber':
        if (!/^\d{3}-\d{2}-\d{5}$/.test(t)) e.businessNumber = '사업자등록번호는 000-00-00000 형식의 숫자 10자리입니다';
        break;
      case 'phone':
        if (!PHONE_FORMAT.test(t)) e.phone = '전화번호 형식이 아닙니다 (예: 02-000-0000, 010-0000-0000)';
        break;
      case 'address':
        if (!t || t.length > 200) e.address = '매장 주소는 1~200자입니다';
        break;
      case 'industries':
        // 하나 이상, 보기 안의 값만, 같은 값 두 번 없이
        if (v.industries.length === 0) e.industries = '매장 업종을 하나 이상 선택해 주세요';
        else if (v.industries.some((x) => !INDUSTRIES.some(([value]) => value === x)) || new Set(v.industries).size !== v.industries.length) e.industries = '선택할 수 없는 업종이에요';
        break;
    }
  }
  return e;
}

/** 서버가 칸별로 돌려준 오류가 어느 단계의 칸인지 (그 단계로 돌아가 보여 주려고). 모르는 칸이면 -1 */
export const stepOf = (field: string): number => STEPS.findIndex((s) => (s.fields as string[]).includes(field));

/** POST /api/shops 본문: 확인용 칸(passwordConfirm)은 보내지 않고, 글자 칸은 다듬는다 */
export function toSignUpRequest(v: SignUpValues) {
  return {
    email: v.email.trim(),
    password: v.password,
    ownerName: v.ownerName.trim(),
    ownerPin: v.ownerPin,
    shopName: v.shopName.trim(),
    businessNumber: v.businessNumber.trim(),
    phone: v.phone.trim(),
    address: v.address.trim(),
    industries: INDUSTRIES.map(([value]) => value).filter((value) => v.industries.includes(value)), // 보기 순서로 정렬
  };
}
