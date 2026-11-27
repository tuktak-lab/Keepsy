// 재방문 관리 화면 값 (순수 함수). docs CONTRACTS 3절 「예측 상태」 · 「다음 액션」 · 「처리 표시」, 4장 GET /api/insights · PUT …/handling

import { t } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';
export type InsightStatus = 'DUE' | 'CHURN_SIGNAL' | 'LONG_ABSENT';
export type HandlingMark = 'CONTACTED' | 'ON_HOLD' | 'CLOSED';

/** 상태별 탭 (FR-PRD-05). cls는 데모 칩 색 */
export const STATUS_TABS: { status: InsightStatus; labelKey: MessageKey; cls: string }[] = [
  { status: 'DUE', labelKey: 'prediction.status.due', cls: 'due' },
  { status: 'CHURN_SIGNAL', labelKey: 'prediction.status.churn', cls: 'risk' },
  { status: 'LONG_ABSENT', labelKey: 'prediction.status.long', cls: 'long' },
];

/** 다음 액션 (FR-PRD-06). 캠페인 액션은 같은 용도 템플릿으로 캠페인을 만든다 */
const NEXT_ACTION_KEY: Record<string, MessageKey> = {
  REVISIT: 'prediction.action.revisit',
  CHECK_IN: 'prediction.action.checkin',
  WIN_BACK: 'prediction.action.winback',
  REQUEST_CONSENT: 'prediction.action.requestConsent',
};

/** 다음 액션 이름. 모르는 값이면 값 그대로 보여 준다 (언어는 부르는 때 정해지므로 모듈 상수에 문구를 두지 않는다) */
export const nextActionLabel = (action: string): string => (NEXT_ACTION_KEY[action] ? t(NEXT_ACTION_KEY[action]) : action);

export const HANDLING: [HandlingMark, MessageKey][] = [
  ['CONTACTED', 'prediction.handling.contacted'],
  ['ON_HOLD', 'prediction.handling.onHold'],
  ['CLOSED', 'prediction.handling.closed'],
];

/** 처리 표시 버튼: 지금 표시를 다시 누르면 지운다(null) */
export const nextMark = (current: HandlingMark | null, clicked: HandlingMark): HandlingMark | null => (current === clicked ? null : clicked);
