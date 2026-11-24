// 휴무일 화면 계산 (순수 함수). 규칙은 docs CONTRACTS 4장 「GET · POST /api/day-offs」 · 「PATCH /api/staff/{staffId}」 (FR-SET-07, F32)
// 서버가 다시 검사한다 (NFR-08).
import type { DateStr } from './calendar';

// 요일 순서는 서버와 같다: MONDAY부터 SUNDAY (lib/settings.ts DAYS와 같은 값)
const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DOW = ['월', '화', '수', '목', '금', '토', '일'];

const pad = (n: number) => String(n).padStart(2, '0');

/** 달 하나의 조회 기간. GET /api/day-offs는 최대 31일이라 달 단위로 끊는다 */
export function monthRange(year: number, month0: number): { from: DateStr; to: DateStr } {
  const last = new Date(Date.UTC(year, month0 + 1, 0)).getUTCDate();
  return { from: `${year}-${pad(month0 + 1)}-01`, to: `${year}-${pad(month0 + 1)}-${pad(last)}` };
}

/** 달을 앞뒤로 옮긴다 */
export function shiftMonth(year: number, month0: number, delta: number): { year: number; month0: number } {
  const total = year * 12 + month0 + delta;
  return { year: Math.floor(total / 12), month0: ((total % 12) + 12) % 12 };
}

/** 'YYYY-MM-DD' → '11월 28일 (금)' */
export function formatDayOffDate(date: DateStr): string {
  const [y, m, d] = date.split('-').map(Number);
  const dow = (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7;
  return `${m}월 ${d}일 (${DOW[dow]})`;
}

/** 등록 칸. `target`은 'SHOP'(매장 휴무) 또는 스탭 ID 문자열 */
export type DayOffValues = { date: string; target: string; reason: string };

export const REASON_MAX = 100;

/** 첫 번째 입력 오류 사유. 없으면 null. 지난 날짜는 서버도 거부한다 (F32) */
export function validateDayOff(v: DayOffValues, today: DateStr): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v.date)) return '휴무 날짜를 골라 주세요';
  if (v.date < today) return '오늘이나 그 뒤 날짜만 휴무로 정할 수 있어요';
  if (v.target !== 'SHOP' && !/^\d+$/.test(v.target)) return '매장 휴무인지 스탭 휴무인지 골라 주세요';
  if (v.reason.trim().length > REASON_MAX) return `사유는 ${REASON_MAX}자 이하로 적어 주세요`;
  return null;
}

/** 등록 본문: 매장 휴무면 staffId null, 빈 사유는 null */
export const toDayOffRequest = (v: DayOffValues) => ({
  date: v.date,
  staffId: v.target === 'SHOP' ? null : Number(v.target),
  reason: v.reason.trim() || null,
});

/** 정기 휴무 요일 하나를 켜고 끈다. 결과는 MONDAY부터 순서, 중복 없음 */
export function toggleDay(days: string[], day: string): string[] {
  const next = days.includes(day) ? days.filter((d) => d !== day) : [...days, day];
  return DAYS.filter((d) => next.includes(d));
}

/** 두 요일 목록이 같은지 (순서 무시) */
export const sameDays = (a: string[], b: string[]): boolean => a.length === b.length && a.every((d) => b.includes(d));
