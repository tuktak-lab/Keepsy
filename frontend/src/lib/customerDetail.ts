// 고객 상세 화면의 표시 규칙 (FR-CUS-03 · 07 · 11, FR-PRD-02 · 03). 값 이름은 CONTRACTS 3장 그대로.
import type {
  Category,
  CategoryPrediction,
  HistoryVisit,
  PredictionStatus,
  ReceivedMessage,
  ReservationStatus,
} from '../features/customer/types';

export const CATEGORY_LABEL: Record<Category, string> = {
  NAIL: '네일',
  LASH: '속눈썹',
  WAXING: '왁싱',
  SEMI_PERMANENT: '반영구',
  ETC: '기타',
};
export const CATEGORIES = Object.keys(CATEGORY_LABEL) as Category[];

/** 예측 상태 칩: [이름, 배경, 글자색] */
export const STATUS_CHIP: Record<PredictionStatus, [string, string, string]> = {
  SCHEDULED: ['예정', '#F3EEEE', '#4A3F3B'],
  DUE: ['주기 도래', '#F9EBE8', '#7A4450'],
  CHURN_SIGNAL: ['이탈 신호', '#F6E3DF', '#8A2F3F'],
  LONG_ABSENT: ['장기 미방문', '#EFE7E3', '#5B4D47'],
  NO_PREDICTION: ['예측 없음', '#F3EEEE', '#6B605B'],
};

export const RESERVATION_STATUS_LABEL: Record<ReservationStatus, string> = {
  CONFIRMED: '확정',
  COMPLETED: '완료',
  CANCELED: '취소',
  NO_SHOW: '노쇼',
};

export const MESSAGE_KIND_LABEL: Record<ReceivedMessage['kind'], string> = {
  AD: '광고',
  UNSUBSCRIBE_NOTICE: '수신 거부 처리 안내',
  CONSENT_RECONFIRM: '동의 재확인 안내',
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
  if (p.status === 'NO_PREDICTION' || !p.startDate || !p.endDate) return '예측 없음';
  if (p.endSignal) return `종료 신호 (${shortDate(p.startDate)})`;
  const basis = p.defaultCycleBased ? '시술 평균 주기 기준' : `평균 ${p.averageIntervalDays}일 · 방문 ${p.basisVisitCount}회`;
  const from = Math.round(daysBetween(today, p.startDate) / 7);
  const to = Math.round(daysBetween(today, p.endDate) / 7);
  const when = to <= 0 ? '예측 기간 지남' : from <= 0 ? `약 ${to}주 안` : `약 ${from}~${to}주 후`;
  return `${when} (${basis})`;
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
