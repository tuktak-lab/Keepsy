// 매장 설정 화면 계산 (순수 함수). 요청 규칙은 docs CONTRACTS 6장 「매장 설정 (SET)」
// PUT /api/shop (FR-SET-01, F39) · PUT /api/settings (FR-SET-03 · 04 · 06, F40). 서버가 다시 검사한다 (NFR-08).
import type { OpeningHour } from './calendar';
import { dowLabel } from './calendar.ts';
import { intlLocale, t } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';

// ── 매장 정보 · 영업시간 (FR-SET-01) ──
// 요일 이름 순서는 서버와 같다: MONDAY부터 SUNDAY.
export const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;

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

/** `industries`는 켜 둔 매장 업종(시술 카테고리 5개 중, CONTRACTS 3절 「매장 업종」). 순서는 3절 순서 */
export type ShopValues = { name: string; phone: string; senderNumber: string; unsubscribeNumber: string; hours: HoursRow[]; industries: string[] };

/** 저장된 업종과 고친 업종의 차이: 새로 꺼지는 것(`off`)과 새로 켜지는 것(`on`), 3절 순서 */
export function industryChange(order: readonly string[], saved: string[], next: string[]): { off: string[]; on: string[] } {
  return { off: order.filter((x) => saved.includes(x) && !next.includes(x)), on: order.filter((x) => !saved.includes(x) && next.includes(x)) };
}

/**
 * 업종 하나를 켜거나 끈 새 목록. 순서는 `order`(3절 순서)로 맞추고, 마지막 하나는 끌 수 없어 그대로 돌려준다 (1개 이상).
 * 끈 업종은 시술 메뉴 · 기본 주기 화면에서 숨기기만 하고 데이터는 그대로 둔다.
 */
export function toggleIndustry(order: readonly string[], current: string[], value: string): string[] {
  const on = current.includes(value);
  if (on && current.length === 1) return current;
  const next = on ? current.filter((x) => x !== value) : [...current, value];
  return order.filter((x) => next.includes(x));
}

/** 국내 전화번호: 0으로 시작하는 번호 · 15xx/16xx/18xx 대표번호, 하이픈은 있어도 없어도 된다. 비어 있는 값은 따로 통과시킨다 (서버와 같은 규칙) */
export const PHONE_FORMAT = /^(0\d{1,2}-?\d{3,4}-?\d{4}|1[568]\d{2}-?\d{4})$/;

const minutes = (hm: string): number => {
  const [h, m] = hm.split(':').map(Number);
  return h * 60 + m;
};

/** 첫 번째 입력 오류 사유. 없으면 null */
export function validateShop(v: ShopValues): string | null {
  const name = v.name.trim();
  if (!name) return t('settings.v.shopName');
  if (name.length > 100) return t('settings.v.shopNameMax');
  if (v.industries.length === 0) return t('settings.v.industryMin');
  for (const [label, value] of [
    [t('settings.v.phoneLabel'), v.phone],
    [t('settings.v.senderLabel'), v.senderNumber],
    [t('settings.v.unsubLabel'), v.unsubscribeNumber],
  ] as const) {
    if (value.trim() && !PHONE_FORMAT.test(value.trim())) return t('settings.v.phoneFormat', { label });
  }
  for (const [i, row] of v.hours.entries()) {
    if (row.off) continue;
    const day = dowLabel(i);
    if (!/^\d{2}:\d{2}$/.test(row.open) || !/^\d{2}:\d{2}$/.test(row.close)) return t('settings.v.hoursBoth', { day });
    if (minutes(row.open) % 30 !== 0 || minutes(row.close) % 30 !== 0) return t('settings.v.hoursStep', { day });
    if (minutes(row.open) >= minutes(row.close)) return t('settings.v.hoursOrder', { day });
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
    industries: v.industries,
  };
}

// ── 매장 설정값 (FR-SET-03 · 04 · 06) ──
export type SettingKey = 'churnSignalCapDays' | 'attributionDays' | 'dailySendLimit' | 'resendBlockDays' | 'virtualFailurePercent' | 'retentionYears';

/** 입력 칸의 글자 그대로 (빈 칸 · 입력 중 값을 허용하려고 문자열) */
export type SettingsValues = Record<SettingKey, string>;

/** 범위는 서버와 같다 (F40) */
// label은 읽을 때 정한다 (언어가 모듈을 불러온 뒤에 정해지므로 getter)
const rule = (key: MessageKey, min: number, max: number) => ({
  get label() {
    return t(key);
  },
  min,
  max,
});
export const SETTING_RULES: Record<SettingKey, { label: string; min: number; max: number }> = {
  churnSignalCapDays: rule('settings.rule.churnCap', 30, 365),
  attributionDays: rule('settings.rule.attribution', 1, 60),
  dailySendLimit: rule('settings.rule.dailyLimit', 1, 10_000),
  resendBlockDays: rule('settings.rule.resendBlock', 0, 60),
  virtualFailurePercent: rule('settings.rule.failurePercent', 0, 100),
  retentionYears: rule('settings.rule.retentionYears', 1, 5),
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
    if (n === null) return t('settings.v.number', { label });
    if (n < min || n > max) return t('settings.v.range', { label, min: min.toLocaleString(intlLocale()), max: max.toLocaleString(intlLocale()) });
  }
  return null;
}

export const toSettingsRequest = (v: SettingsValues): Record<SettingKey, number> =>
  Object.fromEntries(SETTING_KEYS.map((k) => [k, parseInteger(v[k])])) as Record<SettingKey, number>;
