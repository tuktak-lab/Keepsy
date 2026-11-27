// 매장 가입 3단계 규칙 (FR-ACC-01). 요청 필드는 docs CONTRACTS 6장 POST /api/shops (F72 · F73). 서버가 다시 검사한다 (NFR-08).
// 사업자등록번호 · 주소는 쓰는 기능이 없어 받지 않는다 (F73).

import { t as tr } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';

// 전화번호 규칙은 매장 설정(lib/settings.ts PHONE_FORMAT, 서버 PUT /api/shop)과 같다. 순수 함수 파일끼리는 런타임 import를 쓰지 않아 여기에 같은 식을 둔다
const PHONE_FORMAT = /^(0\d{1,2}-?\d{3,4}-?\d{4}|1[568]\d{2}-?\d{4})$/;

export type SignUpField = 'email' | 'password' | 'passwordConfirm' | 'ownerName' | 'ownerPin' | 'shopName' | 'phone' | 'industries';
/** 글자 칸은 문자열, 업종은 여러 개 선택할 수 있어 값 목록이다 */
export type SignUpValues = Record<Exclude<SignUpField, 'industries'>, string> & { industries: string[] };

export const EMPTY_SIGNUP: SignUpValues = {
  email: '',
  password: '',
  passwordConfirm: '',
  ownerName: '',
  ownerPin: '',
  shopName: '',
  phone: '',
  industries: [],
};

/** 단계마다 받는 칸. 순서: 개인정보 → 매장 정보 → 업종(카테고리) */
export const STEPS: { title: MessageKey; fields: SignUpField[] }[] = [
  { title: 'account.signup.stepPersonal', fields: ['email', 'password', 'passwordConfirm', 'ownerName', 'ownerPin'] },
  { title: 'account.signup.stepShop', fields: ['shopName', 'phone'] },
  { title: 'account.signup.stepIndustry', fields: ['industries'] },
];

// 매장 업종은 시술 카테고리 5개와 같은 값이다 (CONTRACTS 3절 「매장 업종」). lib/menus.ts의 CATEGORIES와 같은 순서 · 이름이고,
// 순수 함수 파일끼리는 런타임 import를 쓰지 않아 여기에 같은 목록을 둔다 (PHONE_FORMAT과 같은 사정)
export const INDUSTRIES: [string, MessageKey][] = [
  ['NAIL', 'category.nail'],
  ['LASH', 'category.lash'],
  ['WAXING', 'category.waxing'],
  ['SEMI_PERMANENT', 'category.semi'],
  ['ETC', 'category.etc'],
];

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** 그 단계 칸들의 오류. 비어 있으면 통과 */
export function validateStep(step: number, v: SignUpValues): Partial<Record<SignUpField, string>> {
  const e: Partial<Record<SignUpField, string>> = {};
  for (const f of STEPS[step].fields) {
    const t = f === 'industries' ? '' : v[f].trim();
    switch (f) {
      case 'email':
        if (!EMAIL.test(t) || t.length > 255) e.email = tr('account.signup.vEmail');
        break;
      case 'password':
        if (v.password.length < 8 || v.password.length > 64) e.password = tr('account.signup.vPassword');
        break;
      case 'passwordConfirm':
        if (v.passwordConfirm !== v.password) e.passwordConfirm = tr('account.signup.vPasswordConfirm');
        break;
      case 'ownerName':
        if (!t || t.length > 50) e.ownerName = tr('account.signup.vOwnerName');
        break;
      case 'ownerPin':
        if (!/^\d{4,6}$/.test(v.ownerPin)) e.ownerPin = tr('account.signup.vOwnerPin');
        break;
      case 'shopName':
        if (!t || t.length > 100) e.shopName = tr('account.signup.vShopName');
        break;
      case 'phone':
        if (!t) e.phone = tr('account.signup.vPhoneEmpty');
        else if (!PHONE_FORMAT.test(t)) e.phone = tr('account.signup.vPhoneFormat');
        break;
      case 'industries':
        // 하나 이상, 보기 안의 값만, 같은 값 두 번 없이
        if (v.industries.length === 0) e.industries = tr('account.signup.vIndustryEmpty');
        else if (v.industries.some((x) => !INDUSTRIES.some(([value]) => value === x)) || new Set(v.industries).size !== v.industries.length) e.industries = tr('account.signup.vIndustryInvalid');
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
    phone: v.phone.trim(),
    industries: INDUSTRIES.map(([value]) => value).filter((value) => v.industries.includes(value)), // 보기 순서로 정렬
  };
}
