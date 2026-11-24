// 매장 설정 화면 계산 (순수 함수). 요청 규칙은 docs CONTRACTS 6장 「매장 설정 (SET)」
// PUT /api/shop (FR-SET-01, F39) · PUT /api/settings (FR-SET-03 · 04 · 06, F40). 서버가 다시 검사한다 (NFR-08).
import type { OpeningHour } from './calendar';

// ── 매장 정보 · 영업시간 (FR-SET-01) ──
// 요일 이름 순서는 서버와 같다: MONDAY부터 SUNDAY.
export const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;
export const DAY_LABEL = ['월', '화', '수', '목', '금', '토', '일'];

export const DEFAULT_OPEN = '10:00';
export const DEFAULT_CLOSE = '19:00';

/** 화면의 요일 한 줄. 정기 휴무(`off`)면 시각 칸은 쓰지 않는다 */
export type HoursRow = { dayOfWeek: string; off: boolean; open: string; close: string };

/** 응답의 영업시간을 월~일 7줄로 맞춘다 (빠진 요일은 정기 휴무로) */
export function toRows(hours: OpeningHour[]): HoursRow[] {
  return DAYS.map((day) => {
    const h = hours.find((x) => x.dayOfWeek === day);
    const off = !h || !h.open || !h.close;
    return { dayOfWeek: day, off, open: h?.open ?? DEFAULT_OPEN, close: h?.close ?? DEFAULT_CLOSE };
  });
}

export type ShopValues = { name: string; phone: string; senderNumber: string; unsubscribeNumber: string; hours: HoursRow[] };

/** 국내 전화번호: 0으로 시작하는 번호 · 15xx/16xx/18xx 대표번호, 하이픈은 있어도 없어도 된다. 비어 있는 값은 따로 통과시킨다 (서버와 같은 규칙) */
export const PHONE_FORMAT = /^(0\d{1,2}-?\d{3,4}-?\d{4}|1[568]\d{2}-?\d{4})$/;

const minutes = (hm: string): number => {
  const [h, m] = hm.split(':').map(Number);
  return h * 60 + m;
};

/** 첫 번째 입력 오류 사유. 없으면 null */
export function validateShop(v: ShopValues): string | null {
  const name = v.name.trim();
  if (!name) return '매장명을 입력해 주세요';
  if (name.length > 100) return '매장명은 100자 이하입니다';
  for (const [label, value] of [
    ['매장 연락처', v.phone],
    ['발신번호', v.senderNumber],
    ['수신 거부 번호', v.unsubscribeNumber],
  ] as const) {
    if (value.trim() && !PHONE_FORMAT.test(value.trim())) return `${label}가 전화번호 형식이 아닙니다 (예: 02-000-0000, 1588-0000)`;
  }
  for (const [i, row] of v.hours.entries()) {
    if (row.off) continue;
    const day = DAY_LABEL[i];
    if (!/^\d{2}:\d{2}$/.test(row.open) || !/^\d{2}:\d{2}$/.test(row.close)) return `${day}요일의 여는 시각과 닫는 시각을 모두 입력해 주세요`;
    if (minutes(row.open) % 30 !== 0 || minutes(row.close) % 30 !== 0) return `${day}요일 영업시간은 30분 단위로 입력해 주세요`;
    if (minutes(row.open) >= minutes(row.close)) return `${day}요일은 닫는 시각이 여는 시각보다 늦어야 해요`;
  }
  return null;
}

/** 저장 본문: 매장명은 앞뒤 공백을 빼고, 빈 번호는 null, 정기 휴무 요일은 시각을 모두 null로 */
export function toShopRequest(v: ShopValues) {
  const num = (s: string) => s.trim() || null;
  return {
    name: v.name.trim(),
    phone: num(v.phone),
    senderNumber: num(v.senderNumber),
    unsubscribeNumber: num(v.unsubscribeNumber),
    openingHours: v.hours.map((h) => ({ dayOfWeek: h.dayOfWeek, open: h.off ? null : h.open, close: h.off ? null : h.close })),
  };
}

// ── 매장 설정값 (FR-SET-03 · 04 · 06) ──
export type SettingKey = 'churnSignalCapDays' | 'attributionDays' | 'dailySendLimit' | 'resendBlockDays' | 'virtualFailurePercent' | 'retentionYears';

/** 입력 칸의 글자 그대로 (빈 칸 · 입력 중 값을 허용하려고 문자열) */
export type SettingsValues = Record<SettingKey, string>;

/** 범위는 서버와 같다 (F40) */
export const SETTING_RULES: Record<SettingKey, { label: string; min: number; max: number }> = {
  churnSignalCapDays: { label: '이탈 신호 기간 상한(일)', min: 30, max: 365 },
  attributionDays: { label: '귀속 기간(일)', min: 1, max: 60 },
  dailySendLimit: { label: '일 발송 한도(건)', min: 1, max: 10_000 },
  resendBlockDays: { label: '재발송 제한 기간(일)', min: 0, max: 60 },
  virtualFailurePercent: { label: '가상 발송 실패 비율(%)', min: 0, max: 100 },
  retentionYears: { label: '보관 기간(년)', min: 1, max: 5 },
};

export const SETTING_KEYS = Object.keys(SETTING_RULES) as SettingKey[];

export const settingsToValues = (s: Record<SettingKey, number>): SettingsValues =>
  Object.fromEntries(SETTING_KEYS.map((k) => [k, String(s[k])])) as SettingsValues;

/** 정수로 읽는다. 비었거나 숫자가 아니면 null */
export const parseInteger = (text: string): number | null => (/^\d+$/.test(text.trim()) ? Number(text.trim()) : null);

/** 첫 번째 입력 오류 사유. 하나라도 틀리면 아무것도 저장하지 않는다 (서버와 같음) */
export function validateSettings(v: SettingsValues): string | null {
  for (const key of SETTING_KEYS) {
    const { label, min, max } = SETTING_RULES[key];
    const n = parseInteger(v[key]);
    if (n === null) return `${label}: 숫자로 입력해 주세요`;
    if (n < min || n > max) return `${label}: ${min.toLocaleString('ko-KR')}~${max.toLocaleString('ko-KR')} 사이로 입력해 주세요`;
  }
  return null;
}

export const toSettingsRequest = (v: SettingsValues): Record<SettingKey, number> =>
  Object.fromEntries(SETTING_KEYS.map((k) => [k, parseInteger(v[k])])) as Record<SettingKey, number>;
