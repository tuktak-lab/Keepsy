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

// ── 고객 상세 (CONTRACTS 6장 CUS) ──

export type StaffRef = { id: number; name: string };
export type PreferenceTag = { category: Category; tag: string };

/** GET /api/customers/{id} */
export type CustomerDetail = {
  customerId: number;
  name: string;
  /** 서버가 마스킹한 번호. 전체 번호는 GET …/contact (CONTACT_FULL) */
  phone: string;
  gender: Gender | null;
  birthYear: number | null;
  preferredLanguage: Language | null;
  acquisitionChannel: AcquisitionChannel | null;
  registeredAt: string;
  serviceCaution: string | null;
  healthCaution: string | null;
  noShowCount: number;
  bookingRestricted: boolean;
  bookingRestrictionReason: string | null;
  preferenceTags: PreferenceTag[];
  preferenceMemo: string | null;
  visitSummary: {
    visitCount: number;
    serviceCount: number;
    firstVisitDate: string | null;
    lastVisitDate: string | null;
    categories: { category: Category; visitCount: number; averageIntervalDays: number | null }[];
    menus: { serviceMenuId: number | null; name: string | null; category: Category; count: number }[];
  };
};

/** GET /api/customers/{id}/predictions 항목 */
export type CategoryPrediction = {
  category: Category;
  status: PredictionStatus | null;
  lastServiceDate: string | null;
  startDate: string | null;
  endDate: string | null;
  averageIntervalDays: number | null;
  basisVisitCount: number;
  defaultCycleBased: boolean;
  endSignal: boolean;
};

export type ReservationStatus = 'CONFIRMED' | 'COMPLETED' | 'CANCELED' | 'NO_SHOW';

/** GET /api/customers/{id}/reservations 항목 (예약 필드) */
export type CustomerReservation = {
  id: number;
  status: ReservationStatus;
  startAt: string;
  minutes: number;
  staff: StaffRef;
  services: { serviceMenuId: number; name: string }[];
};

/** GET /api/customers/{id}/history 항목 */
export type HistoryVisit = {
  visitId: number;
  visitDate: string;
  serviceCount: number;
  services: { category: Category; menuName: string | null; staff: StaffRef | null }[];
  photos: { photoId: number; url: string; uploadedAt: string }[];
  notes: { noteId: number; content: string; staff: StaffRef; createdAt: string }[];
};

/** GET /api/customers/{id}/consult-notes 항목 */
export type ConsultNote = { noteId: number; content: string; staff: StaffRef; writtenAt: string };

/** GET /api/customers/{id}/consents */
export type ConsentHistoryRow = {
  consentId: number;
  item: ConsentItem;
  agreed: boolean;
  recordedAt: string;
  staff: StaffRef | null;
  method: ConsentMethod | 'IMPORT' | null;
  withdrawalPath: 'UNSUBSCRIBE' | 'DIRECT' | null;
  correctsConsentId: number | null;
};
export type CustomerConsents = {
  current: { item: ConsentItem; agreed: boolean; recordedAt: string | null }[];
  history: ConsentHistoryRow[];
};

/** GET /api/customers/{id}/messages 항목 */
export type ReceivedMessage = {
  messageId: number;
  kind: 'AD' | 'UNSUBSCRIBE_NOTICE' | 'CONSENT_RECONFIRM';
  receivedAt: string;
  campaign: { id: number; name: string } | null;
  category: Category | null;
  reservations: { id: number; startAt: string; status: ReservationStatus }[];
  visits: { id: number; visitDate: string }[];
};
