// 메시지 템플릿 규칙 (FR-CMP-03, C10). 서버 MessageTemplateService · TemplatePurpose와 같은 규칙을 화면에서 미리 보여 준다.
// 최종 검사는 서버가 한다 (NFR-08).

import { t } from './i18n.ts';
import type { MessageKey } from './messages/ko.ts';

/** 템플릿 용도 (CONTRACTS 3장) */
export type TemplatePurpose = 'REVISIT' | 'CHECK_IN' | 'WIN_BACK' | 'CONSENT_RECONFIRM' | 'ETC';

/** 캠페인 메시지 종류 (CONTRACTS 3장). 용도로 정해진다 */
export type MessageKind = 'AD' | 'INFO';

export const PURPOSES: [TemplatePurpose, MessageKey][] = [
  ['REVISIT', 'campaign.purpose.revisit'],
  ['CHECK_IN', 'campaign.purpose.checkin'],
  ['WIN_BACK', 'campaign.purpose.winback'],
  ['CONSENT_RECONFIRM', 'campaign.purpose.reconfirm'],
  ['ETC', 'campaign.purpose.etc'],
];

/** 용도 이름 (문구는 읽을 때 정한다) */
export const purposeLabel = (purpose: string): string => {
  const found = PURPOSES.find(([p]) => p === purpose);
  return found ? t(found[1]) : purpose;
};

/** 본문 상한 (CONTRACTS 2장 「2,000바이트 초과 메시지」) */
export const MAX_BODY_BYTES = 2000;

/** 동의 재확인만 안내, 나머지(기타 포함)는 광고 (FR-CMP-03) */
export function messageKind(purpose: TemplatePurpose): MessageKind {
  return purpose === 'CONSENT_RECONFIRM' ? 'INFO' : 'AD';
}

/** 용도별로 쓸 수 있는 변수. 동의 재확인은 카테고리가 없어 {최근시술} {경과일}을 못 쓴다 (FR-CMP-13, C10) */
export function allowedVariables(purpose: TemplatePurpose): string[] {
  return purpose === 'CONSENT_RECONFIRM'
    ? ['고객명', '매장명', '동의일', '야간광고동의']
    : ['고객명', '최근시술', '경과일', '매장명'];
}

/** ASCII 1바이트, 그 밖의 문자 2바이트, 줄바꿈은 1바이트 (서버 MessageComposer.countBytes와 같다) */
export function countBytes(text: string): number {
  let bytes = 0;
  for (const ch of text.replace(/\r\n?/g, '\n')) bytes += ch.codePointAt(0)! < 0x80 ? 1 : 2;
  return bytes;
}

/** 본문에 쓴 변수 중 이 용도에서 쓸 수 없는 것 (중복 없이, 나온 순서대로) */
export function disallowedVariables(body: string, purpose: TemplatePurpose): string[] {
  const allowed = allowedVariables(purpose);
  const found = [...body.matchAll(/\{([^{}]*)\}/g)].map((m) => m[1]);
  return [...new Set(found.filter((v) => !allowed.includes(v)))];
}
