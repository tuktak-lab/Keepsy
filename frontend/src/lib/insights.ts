// 재방문 관리 화면 값 (순수 함수). docs CONTRACTS 3절 「예측 상태」 · 「다음 액션」 · 「처리 표시」, 4장 GET /api/insights · PUT …/handling

export type InsightStatus = 'DUE' | 'CHURN_SIGNAL' | 'LONG_ABSENT';
export type HandlingMark = 'CONTACTED' | 'ON_HOLD' | 'CLOSED';

/** 상태별 탭 (FR-PRD-05). cls는 데모 칩 색 */
export const STATUS_TABS: { status: InsightStatus; label: string; cls: string }[] = [
  { status: 'DUE', label: '주기 도래', cls: 'due' },
  { status: 'CHURN_SIGNAL', label: '이탈 신호', cls: 'risk' },
  { status: 'LONG_ABSENT', label: '장기 미방문', cls: 'long' },
];

/** 다음 액션 (FR-PRD-06). 캠페인 액션은 같은 용도 템플릿으로 캠페인을 만든다 */
export const NEXT_ACTION_LABEL: Record<string, string> = {
  REVISIT: '재방문 안내',
  CHECK_IN: '안부 · 혜택',
  WIN_BACK: '재방문 혜택',
  REQUEST_CONSENT: '다음 방문 시 동의 요청',
};

export const HANDLING: [HandlingMark, string][] = [
  ['CONTACTED', '연락함'],
  ['ON_HOLD', '보류'],
  ['CLOSED', '관리 종료'],
];

/** 처리 표시 버튼: 지금 표시를 다시 누르면 지운다(null) */
export const nextMark = (current: HandlingMark | null, clicked: HandlingMark): HandlingMark | null => (current === clicked ? null : clicked);
