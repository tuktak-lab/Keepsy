// 시술 메뉴 · 카테고리 기본 주기 계산 (FR-SET-02, 순수 함수). 요청 규칙은 docs CONTRACTS 6장 「매장 설정 (SET)」
// POST · PATCH /api/service-menus, GET · PUT /api/category-cycles. 서버가 다시 검사한다 (NFR-08).

import { t } from './i18n.ts';

/** CONTRACTS 3장 「시술 카테고리」 (표시 순서) */
export const CATEGORIES = ['NAIL', 'LASH', 'WAXING', 'SEMI_PERMANENT', 'ETC'] as const;
export type Category = (typeof CATEGORIES)[number];

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

// ── 켜 둔 매장 업종 (CONTRACTS 3절 「매장 업종」) ──
// `industries`: GET /api/shop의 켜 둔 업종. undefined는 아직 모름(불러오는 중), null은 불러오지 못해 모름.

/** 선택지에 보일 카테고리. 모르면(null) 모두, 불러오는 중(undefined)에는 아직 아무것도 보이지 않는다 */
export function enabledCategories(industries: readonly string[] | null | undefined): Category[] {
  if (industries === undefined) return [];
  return CATEGORIES.filter((c) => industries === null || industries.includes(c));
}

/**
 * 새로 고를 수 있는 메뉴: 켜 둔 업종의 메뉴, 그리고 이미 골라 둔 메뉴(`keepIds`).
 * 끈 업종의 메뉴가 이미 예약 · 마감에 들어 있으면 그 선택은 끊기지 않게 계속 보인다.
 */
export function pickableMenus<T extends { id: number; category: string }>(menus: T[], industries: readonly string[] | null | undefined, keepIds: readonly number[] = []): T[] {
  if (industries === undefined) return [];
  return menus.filter((m) => industries === null || industries.includes(m.category) || keepIds.includes(m.id));
}

/** 메뉴 예측 속성 (E5). 한글 설명은 명세서 FR-SET-02 */
export type PredictionAttribute = 'NORMAL' | 'EXCLUDE' | 'END_SIGNAL';
export const ATTRIBUTES: [PredictionAttribute, string][] = [
  ['NORMAL', '일반'],
  ['EXCLUDE', '주기 계산 제외'],
  ['END_SIGNAL', '종료 신호'],
];
export const ATTRIBUTE_LABEL = Object.fromEntries(ATTRIBUTES) as Record<PredictionAttribute, string>;

export type Menu = {
  id: number;
  category: Category;
  name: string;
  price: number;
  durationMinutes: number;
  cycleMinDays: number;
  cycleMaxDays: number;
  predictionAttribute: PredictionAttribute;
};

/** 소요 시간 고르기: 30분 단위, 30분 ~ 8시간 */
export const DURATION_STEPS = Array.from({ length: 16 }, (_, i) => (i + 1) * 30);

/** `90 → "1시간 30분"`, `120 → "2시간"`, `30 → "30분"` */
export function formatDuration(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m}분`;
  return m === 0 ? `${h}시간` : `${h}시간 ${m}분`;
}

/** 화면의 입력값. 숫자 칸은 입력 중 값을 허용하려고 문자열 */
export type MenuValues = {
  name: string;
  price: string;
  durationMinutes: number;
  cycleMinDays: string;
  cycleMaxDays: string;
  predictionAttribute: PredictionAttribute;
};

export const toMenuValues = (m: Menu): MenuValues => ({
  name: m.name,
  price: String(m.price),
  durationMinutes: m.durationMinutes,
  cycleMinDays: String(m.cycleMinDays),
  cycleMaxDays: String(m.cycleMaxDays),
  predictionAttribute: m.predictionAttribute,
});

/** 새 메뉴의 기본값: 카테고리 기본 주기에서 시작한다 (FR-SET-02) */
export const emptyMenuValues = (cycle: { minDays: number; maxDays: number } | undefined): MenuValues => ({
  name: '',
  price: '0',
  durationMinutes: 60,
  cycleMinDays: String(cycle?.minDays ?? 28),
  cycleMaxDays: String(cycle?.maxDays ?? 42),
  predictionAttribute: 'NORMAL',
});

const intOrNull = (s: string): number | null => (/^\d+$/.test(s.trim()) ? Number(s.trim()) : null);

/** 첫 번째 입력 오류 사유. 없으면 null */
export function validateMenu(v: MenuValues): string | null {
  const name = v.name.trim();
  if (!name) return '시술명을 입력해 주세요';
  if (name.length > 100) return '시술명은 100자 이하입니다';
  if (intOrNull(v.price) === null) return '가격은 0원 이상의 숫자로 입력해 주세요';
  if (v.durationMinutes < 30 || v.durationMinutes % 30 !== 0) return '소요 시간은 30분 단위로 골라 주세요';
  const min = intOrNull(v.cycleMinDays);
  const max = intOrNull(v.cycleMaxDays);
  if (min === null || max === null) return '기본 주기(일)를 숫자로 입력해 주세요';
  if (min < 1 || max < 1) return '기본 주기는 1일 이상이에요';
  if (min > max) return '기본 주기의 최소는 최대보다 클 수 없어요';
  return null;
}

/** 추가 요청 본문. 카테고리는 여기서만 보낸다 (수정에서는 바꿀 수 없음, F31) */
export function toCreateRequest(category: Category, v: MenuValues) {
  return {
    category,
    name: v.name.trim(),
    price: intOrNull(v.price),
    durationMinutes: v.durationMinutes,
    cycleMinDays: intOrNull(v.cycleMinDays),
    cycleMaxDays: intOrNull(v.cycleMaxDays),
    predictionAttribute: v.predictionAttribute,
  };
}

/** 수정 요청 본문: 실제로 바뀐 칸만 보낸다 (F31). 바뀐 것이 없으면 빈 객체 */
export function toPatchRequest(original: Menu, v: MenuValues): Record<string, string | number> {
  const body: Record<string, string | number> = {};
  if (v.name.trim() !== original.name) body.name = v.name.trim();
  const price = intOrNull(v.price);
  if (price !== null && price !== original.price) body.price = price;
  if (v.durationMinutes !== original.durationMinutes) body.durationMinutes = v.durationMinutes;
  const min = intOrNull(v.cycleMinDays);
  const max = intOrNull(v.cycleMaxDays);
  if (min !== null && min !== original.cycleMinDays) body.cycleMinDays = min;
  if (max !== null && max !== original.cycleMaxDays) body.cycleMaxDays = max;
  if (v.predictionAttribute !== original.predictionAttribute) body.predictionAttribute = v.predictionAttribute;
  return body;
}

/** 기본 주기나 예측 속성이 바뀌면 그 메뉴를 받은 고객 전체의 예측을 다시 계산한다 (FR-SET-02, FR-PRD-01). 이름 · 가격 · 소요 시간만이면 아님 */
export const changesPrediction = (patch: Record<string, unknown>): boolean => 'cycleMinDays' in patch || 'cycleMaxDays' in patch || 'predictionAttribute' in patch;

// ── 카테고리 기본 주기 ──
export type CategoryCycle = { category: Category; minDays: number; maxDays: number };

/** 입력 칸 (문자열) */
export type CycleValues = Record<Category, { minDays: string; maxDays: string }>;

export const toCycleValues = (cycles: CategoryCycle[]): CycleValues =>
  Object.fromEntries(
    CATEGORIES.map((c) => {
      const found = cycles.find((x) => x.category === c);
      return [c, { minDays: String(found?.minDays ?? ''), maxDays: String(found?.maxDays ?? '') }];
    }),
  ) as CycleValues;

export function validateCycles(v: CycleValues): string | null {
  for (const c of CATEGORIES) {
    const min = intOrNull(v[c].minDays);
    const max = intOrNull(v[c].maxDays);
    if (min === null || max === null) return `${CATEGORY_LABEL[c]}: 기본 주기(일)를 숫자로 입력해 주세요`;
    if (min < 1) return `${CATEGORY_LABEL[c]}: 기본 주기는 1일 이상이에요`;
    if (min > max) return `${CATEGORY_LABEL[c]}: 최소는 최대보다 클 수 없어요`;
  }
  return null;
}

/** 카테고리 5개를 한 번씩, 3절 순서로 통째로 보낸다 */
export const toCyclesRequest = (v: CycleValues): CategoryCycle[] =>
  CATEGORIES.map((c) => ({ category: c, minDays: intOrNull(v[c].minDays) as number, maxDays: intOrNull(v[c].maxDays) as number }));
