// 동의 항목 안내 문구와 태블릿 본인 체크의 변경 계산 (FR-CUS-09). 고객 등록 · 태블릿 본인 체크 화면이 같이 쓴다.
import type { ConsentItem } from '../features/customer/types';

/** 개인정보 수집 · 이용 동의 (필수). 보관 기간은 매장 설정값을 공용으로 읽을 수 없어 기본값으로 안내한다 (#33) */
export const PRIVACY_TEXT = {
  label: '개인정보 수집 · 이용 동의',
  desc: '이름 · 휴대폰 번호와 방문 · 시술 기록을 고객 관리와 예약 안내에 씁니다. 마지막 방문 후 매장이 정한 보관 기간(기본 3년)이 지나면 파기합니다.',
};

/** 선택 동의 (CONTRACTS 3장 순서) */
export const OPTIONAL_CONSENTS: [ConsentItem, string, string][] = [
  ['PHOTO', '시술 사진', '시술 전후 사진을 기록하고 히스토리북에 보관합니다'],
  ['MARKETING', '마케팅 수신', '재방문 안내 · 이벤트 문자를 받습니다'],
  ['NIGHT_AD', '야간 광고 수신', '오후 9시 ~ 오전 8시 사이 광고 문자도 받습니다'],
  ['HEALTH', '건강 정보', '알레르기 등 시술에 필요한 건강 주의사항을 기록합니다'],
];

/** GET /api/customers/{id}/consents의 current 항목 */
export type ConsentState = { item: ConsentItem; agreed: boolean; recordedAt: string | null };

/** 바꿀 항목. 동의는 agreed true, 철회는 false (POST /api/customers/{id}/consents 한 건씩) */
export type ConsentChange = { item: ConsentItem; agreed: boolean };

/**
 * 고객이 고른 값과 지금 상태를 비교해 바뀐 항목만 돌려준다.
 * 개인정보 동의 철회는 고객 삭제로만 처리하므로(F42) 동의로 바꾸는 경우만 넣는다.
 */
export function consentChanges(current: ConsentState[], chosen: Partial<Record<ConsentItem, boolean>>): ConsentChange[] {
  const now = Object.fromEntries(current.map((c) => [c.item, c.agreed])) as Partial<Record<ConsentItem, boolean>>;
  const changes: ConsentChange[] = [];
  for (const item of ['PRIVACY', ...OPTIONAL_CONSENTS.map(([i]) => i)] as ConsentItem[]) {
    const want = chosen[item];
    if (want === undefined || want === Boolean(now[item])) continue;
    if (item === 'PRIVACY' && !want) continue;
    changes.push({ item, agreed: want });
  }
  return changes;
}
