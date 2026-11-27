// 예약 등록 · 수정 폼 계산 (순수 함수). 요청 규칙은 docs CONTRACTS 6장 「예약 (RSV)」 POST · PATCH /api/reservations.
// (타입만 가져온다: Node 테스트 실행기는 확장자 없는 상대 import를 읽지 못해 런타임 import를 두지 않는다)
import type { Channel, DateStr } from './calendar';
import { t } from './i18n.ts';

/** 소요 시간 고르기: 30분 단위, 30분 ~ 8시간 (FR-RSV-01) */
export const MINUTE_STEPS = Array.from({ length: 16 }, (_, i) => (i + 1) * 30);

/** 예정 시술 소요 시간 합계를 30분 단위로 올림한 기본값 (FR-RSV-01) */
export function defaultMinutes(durations: number[]): number {
  const sum = durations.reduce((a, b) => a + b, 0);
  return Math.min(480, Math.max(30, Math.ceil(sum / 30) * 30));
}

/** `2026-11-24`, `14:30` → `2026-11-24T14:30:00+09:00` (서울 기준 시각, CONTRACTS 3장) */
export const toStartAt = (date: DateStr, hm: string): string => `${date}T${hm}:00+09:00`;

export type FormValues = {
  channel: Channel;
  customerMode: 'pick' | 'none'; // 고객을 고름 | 고객 없는 예약 (E3)
  customerId: number | null;
  staffId: number | null;
  date: DateStr;
  startTime: string; // `HH:mm`, 아직 안 골랐으면 ''
  minutes: number;
  menuIds: number[];
  consultFirst: boolean;
  memo: string;
};

/** 저장 전에 화면에서 먼저 거르는 입력 오류. 첫 번째 사유를 돌려주고, 없으면 null. 서버가 다시 검사한다 (NFR-08) */
export function validate(v: FormValues, editing: boolean): string | null {
  if (!editing && v.customerMode === 'pick' && v.customerId === null) {
    return t('reservation.form.noCustomerFirst');
  }
  if (v.staffId === null) return t('reservation.form.needStaff');
  if (v.menuIds.length === 0) return t('reservation.form.needMenu');
  if (!v.date) return t('reservation.form.needDate');
  if (!v.startTime) return t('reservation.form.needTime');
  if (v.memo.length > 1000) return t('common.memoMax');
  return null;
}

/** 등록 · 수정 요청 본문. 수정은 고객을 보내지 않고(고객 연결은 별도 API), 고객 없는 예약은 메모를 받지 않는다 (E3) */
export function toRequest(v: FormValues, editing: boolean, hasCustomer: boolean) {
  const memo = hasCustomer ? v.memo.trim() : '';
  return {
    ...(editing ? {} : { customerId: v.customerMode === 'pick' ? v.customerId : null }),
    staffId: v.staffId,
    startAt: toStartAt(v.date, v.startTime),
    minutes: v.minutes,
    channel: v.channel,
    consultFirst: v.consultFirst,
    memo: memo || null,
    serviceMenuIds: v.menuIds,
  };
}

export type FieldError = { field: string; reason: string };

/** `invalid` 응답의 칸별 사유(`details`)를 한 줄로. 모양이 다르면 null */
export function detailsText(details: unknown): string | null {
  if (!Array.isArray(details)) return null;
  const reasons = details.map((d) => (d && typeof (d as FieldError).reason === 'string' ? (d as FieldError).reason : null)).filter(Boolean);
  return reasons.length ? reasons.join(' · ') : null;
}
