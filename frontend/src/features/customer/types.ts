// 고객(CUS) API 타입. 이름 · enum 값은 docs CONTRACTS 3장 · 6장 그대로.

/** 시술 카테고리 (CONTRACTS 3장) */
export type Category = 'NAIL' | 'LASH' | 'WAXING' | 'SEMI_PERMANENT' | 'ETC';

/** 예측 상태 (CONTRACTS 3장) */
export type PredictionStatus = 'SCHEDULED' | 'DUE' | 'CHURN_SIGNAL' | 'LONG_ABSENT' | 'NO_PREDICTION';

/** 동의 항목 (CONTRACTS 3장) */
export type ConsentItem = 'PRIVACY' | 'PHOTO' | 'MARKETING' | 'NIGHT_AD' | 'HEALTH';

/** 동의 방법은 예약 폼 신규 등록과 같이 lib/newCustomer.ts 것을 쓴다 */
import type { ConsentMethod } from '@/lib/newCustomer';
export type { ConsentMethod };

export type Gender = 'FEMALE' | 'MALE' | 'OTHER';
export type Language = 'KO' | 'EN' | 'ZH' | 'JA' | 'ETC';
export type AcquisitionChannel = 'INSTAGRAM' | 'NAVER_SEARCH' | 'REFERRAL' | 'PASSING_BY' | 'ETC';

/** POST /api/customers 요청 */
export type CustomerCreateRequest = {
  name: string;
  phone: string;
  consents: ConsentItem[];
  consentMethod: ConsentMethod;
  gender?: Gender;
  birthYear?: number;
  preferredLanguage?: Language;
  acquisitionChannel?: AcquisitionChannel;
};

/** POST /api/customers 응답 */
export type CustomerCreateResponse = { customerId: number };

/** GET /api/customers 쿼리 (page · size 제외) */
export type CustomerListFilter = {
  q?: string;
  visitCountMin?: number;
  visitCountMax?: number;
  category?: Category;
  predictionStatus?: PredictionStatus;
  staffId?: number;
  marketingConsent?: boolean;
};

/** GET /api/customers 항목 */
export type CustomerListItem = {
  customerId: number;
  name: string;
  /** 서버가 마스킹한 번호 `010-****-1234` (NFR-04). 화면에서 다시 가리거나 풀지 않는다 */
  phone: string;
  visitCount: number;
  /** `YYYY-MM-DD`. 방문이 없으면 null */
  lastVisitDate: string | null;
  marketingConsent: boolean;
};
