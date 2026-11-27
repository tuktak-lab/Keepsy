// 캠페인 작성 화면 값 (순수 함수). docs CONTRACTS 3절 「예측 상태」 · 「템플릿 용도」 · 「캠페인 제외 사유」, 6장 CMP

export type TargetStatus = 'DUE' | 'CHURN_SIGNAL' | 'LONG_ABSENT';

/** 대상 예측 상태 (FR-CMP-01). 뜻풀이는 처음 쓰는 원장이 바로 알 수 있게 (FR-PRD-04) */
export const TARGET_STATUSES: { status: TargetStatus; label: string; desc: string }[] = [
  { status: 'DUE', label: '주기 도래', desc: '다음 방문 예상 시기가 됐는데 아직 예약이 없는 고객' },
  { status: 'CHURN_SIGNAL', label: '이탈 신호', desc: '다음 방문 예상 시기가 지났는데 아직 오지 않은 고객' },
  { status: 'LONG_ABSENT', label: '장기 미방문', desc: '예상 시기가 지나고도 오랫동안 오지 않은 고객' },
];

/** 상태별 추천 템플릿 용도 (FR-PRD-06 다음 액션과 같은 규칙) */
export const RECOMMENDED_PURPOSE: Record<TargetStatus, string> = {
  DUE: 'REVISIT',
  CHURN_SIGNAL: 'CHECK_IN',
  LONG_ABSENT: 'WIN_BACK',
};

/** 캠페인 제외 사유 (CONTRACTS 3절, 이 순서로 우선) */
export const EXCLUSION_LABEL: [string, string][] = [
  ['UNSUBSCRIBED', '수신 거부'],
  ['NO_CONSENT', '수신 미동의'],
  ['RESEND_LIMIT', '재발송 제한 기간'],
];

/** 서울 시각으로 야간(21:00~08:00 전)인지 (FR-CMP-07, 서버 NightSendPolicy와 같은 경계) */
export function isSeoulNight(at: Date): boolean {
  const h = Number(new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', hour: '2-digit', hourCycle: 'h23' }).format(at));
  return h >= 21 || h < 8;
}

/** `<input type="datetime-local">` 값(서울 시각으로 본다)을 오프셋 포함 ISO로 (CONTRACTS 2장) */
export function seoulLocalToIso(local: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(local)) return null;
  return `${local}:00+09:00`;
}

/** 'YYYY-MM-DDTHH:mm' (서울) — 예약 입력의 기본값 · 최솟값 */
export function seoulLocalInput(at: Date): string {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
      .formatToParts(at)
      .map((x) => [x.type, x.value]),
  );
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
}

/** 쿼리 `customerIds=1,2,3` → 양의 정수 배열 (인사이트에서 고른 고객으로 시작, FR-CMP-01) */
export function parseIds(raw: string | null): number[] {
  if (!raw) return [];
  return [...new Set(raw.split(',').map(Number).filter((n) => Number.isInteger(n) && n > 0))];
}

/** 쿼리 `purpose`(인사이트의 다음 액션) → 추천할 광고 템플릿 용도. 재방문 · 안부 · 재방문 혜택 3개만 받고, 다른 값은 무시(null)한다 */
export function parsePurpose(raw: string | null): string | null {
  return raw !== null && Object.values(RECOMMENDED_PURPOSE).includes(raw) ? raw : null;
}

/** 캠페인을 만들 수 없는 업종 사정. loading: 켜 둔 업종을 불러오는 중, failed: 불러오지 못함, disabled: 고른 카테고리가 꺼 둔 업종 */
export type CategoryBlock = 'loading' | 'failed' | 'disabled' | null;

/**
 * 쓸 카테고리와 만들기를 막는 사정 (F72: 꺼 둔 업종은 화면 선택지에서만 숨긴다).
 * `explicit`: 주소의 `category`로 받은 값(인사이트 등). 꺼 둔 업종이어도 다른 업종으로 조용히 바꾸지 않고 막는다.
 * 직접 들어온 경우(기본 네일)만 켜 둔 첫 업종으로 시작한다. `industries`는 GET /api/shop 순서(CONTRACTS 3절)다.
 */
export function resolveCategory<C extends string>(wanted: C, explicit: boolean, industries: readonly string[] | null | undefined): { category: C; block: CategoryBlock } {
  if (industries === undefined) return { category: wanted, block: 'loading' };
  if (industries === null) return { category: wanted, block: 'failed' };
  if (industries.includes(wanted)) return { category: wanted, block: null };
  if (explicit || industries.length === 0) return { category: wanted, block: 'disabled' };
  return { category: industries[0] as C, block: null };
}

/** 대상 수 조회 결과. 어떤 조건(`key`)으로 센 것인지 함께 둔다 */
export type TargetsResult<T> = { key: string; data: T | null; error: string };

/** 지금 조건(`key`)으로 센 결과만 쓴다. 조건을 바꾼 직후(대기 · 조회 중)나 늦게 온 이전 응답은 null */
export function currentTargets<T>(result: TargetsResult<T> | null, key: string): TargetsResult<T> | null {
  return result !== null && result.key === key ? result : null;
}

/** 캠페인을 만들 수 있는지: 템플릿이 있고, 업종 사정이 없고, 지금 조건으로 센 대상이 1명 이상 */
export function canCreate(p: { creating: boolean; hasTemplate: boolean; block: CategoryBlock; current: TargetsResult<{ targetCount: number }> | null }): boolean {
  return !p.creating && p.hasTemplate && p.block === null && p.current !== null && (p.current.data?.targetCount ?? 0) > 0;
}
