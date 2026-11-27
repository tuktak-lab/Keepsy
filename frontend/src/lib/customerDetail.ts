// 고객 상세 화면의 표시 규칙 (FR-CUS-03 · 07 · 11, FR-PRD-02 · 03). 값 이름은 CONTRACTS 3장 그대로.
import type {
  Category,
  CategoryPrediction,
  HistoryVisit,
  PredictionStatus,
  ReceivedMessage,
  ReservationStatus,
} from '../features/customer/types';
import { t } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';

// 문구는 읽을 때 정한다 (언어가 모듈을 불러온 뒤에 정해지므로 값 대신 getter를 둔다)
export const CATEGORY_LABEL: Record<Category, string> = {
  get NAIL() {
    return t('category.nail');
  },
  get LASH() {
    return t('category.lash');
  },
  get WAXING() {
    return t('category.waxing');
  },
  get SEMI_PERMANENT() {
    return t('category.semi');
  },
  get ETC() {
    return t('category.etc');
  },
};
export const CATEGORIES = Object.keys(CATEGORY_LABEL) as Category[];

/** 예측 상태 칩: [이름, 배경, 글자색] */
export const STATUS_CHIP: Record<PredictionStatus, [MessageKey, string, string]> = {
  SCHEDULED: ['dashboard.seg.scheduled', '#F3EEEE', '#4A3F3B'],
  DUE: ['prediction.status.due', '#F9EBE8', '#7A4450'],
  CHURN_SIGNAL: ['prediction.status.churn', '#F6E3DF', '#8A2F3F'],
  LONG_ABSENT: ['prediction.status.long', '#EFE7E3', '#5B4D47'],
  NO_PREDICTION: ['customer.status.none', '#F3EEEE', '#6B605B'],
};

export const RESERVATION_STATUS_LABEL: Record<ReservationStatus, string> = {
  get CONFIRMED() {
    return t('customer.rstatus.confirmed');
  },
  get COMPLETED() {
    return t('customer.rstatus.completed');
  },
  get CANCELED() {
    return t('customer.rstatus.canceled');
  },
  get NO_SHOW() {
    return t('customer.rstatus.noShow');
  },
};

export const MESSAGE_KIND_LABEL: Record<ReceivedMessage['kind'], string> = {
  get AD() {
    return t('customer.msgKind.ad');
  },
  get UNSUBSCRIBE_NOTICE() {
    return t('customer.msgKind.unsubscribe');
  },
  get CONSENT_RECONFIRM() {
    return t('customer.msgKind.reconfirm');
  },
};

/** `2026-10-24` → `2026.10.24` (서버 날짜는 이미 Asia/Seoul) */
export const dotDate = (date: string): string => date.replaceAll('-', '.');
/** `2026-10-24` → `10.24` */
export const shortDate = (date: string): string => date.slice(5).replace('-', '.');

/**
 * 카테고리 예측 한 줄 (FR-PRD-02 · 03). 예:
 * "약 3~4주 후 (평균 24일 · 방문 6회)", "약 4~6주 후 (시술 평균 주기 기준)".
 * 주 단위는 예측 기간 시작 · 끝까지 남은 날로 셈한다.
 */
export function predictionText(p: CategoryPrediction, today: string): string {
  if (p.status === 'NO_PREDICTION' || !p.startDate || !p.endDate) return t('customer.status.none');
  if (p.endSignal) return t('customer.predict.endSignal', { date: shortDate(p.startDate) });
  const basis = p.defaultCycleBased ? t('customer.predict.defaultBasis') : t('customer.predict.basis', { days: p.averageIntervalDays ?? '', visits: p.basisVisitCount });
  const from = Math.round(daysBetween(today, p.startDate) / 7);
  const to = Math.round(daysBetween(today, p.endDate) / 7);
  const when = to <= 0 ? t('customer.predict.past') : from <= 0 ? t('customer.predict.within', { to }) : t('customer.predict.range', { from, to });
  return t('customer.predict.line', { when, basis });
}

function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(b) - Date.parse(a)) / 86_400_000);
}

export type RhythmPoint = { date: string; categories: Category[] };

/** 방문 리듬 타임라인: 방문일 오름차순, 방문마다 시술 카테고리(중복 없이) */
export function rhythmPoints(visits: HistoryVisit[]): RhythmPoint[] {
  return [...visits]
    .sort((a, b) => a.visitDate.localeCompare(b.visitDate))
    .map((v) => ({ date: v.visitDate, categories: [...new Set(v.services.map((s) => s.category))] }));
}

/** 오늘(Asia/Seoul) `YYYY-MM-DD` */
export function seoulToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(now);
}
