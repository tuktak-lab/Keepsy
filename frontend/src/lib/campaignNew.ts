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
