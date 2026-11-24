// 스탭 · PIN · 기능 잠금 화면 계산 (순수 함수). 규칙은 docs CONTRACTS 3장 「기능 키」, 4장 ACC
// (POST · PATCH /api/staff, PUT /api/staff/{staffId}/pin, PUT /api/feature-locks, PUT /api/owner-pin). 서버가 다시 검사한다 (NFR-08).
import type { Access } from './session';

/** PIN은 숫자 4~6자리 (C21) */
export const PIN_FORMAT = /^\d{4,6}$/;
export const NAME_MAX = 50;

/** 스탭 이름 오류 사유. 없으면 null */
export function validateStaffName(name: string): string | null {
  const n = name.trim();
  if (!n) return '이름을 입력해 주세요';
  if (n.length > NAME_MAX) return `이름은 ${NAME_MAX}자 이하입니다`;
  return null;
}

/** PIN 오류 사유. 없으면 null */
export const validatePin = (pin: string): string | null => (PIN_FORMAT.test(pin) ? null : 'PIN은 숫자 4~6자리입니다');

/** 스탭 등록 칸 */
export type NewStaffValues = { name: string; pin: string; lockedFeatureAllowed: boolean };

export const validateNewStaff = (v: NewStaffValues): string | null => validateStaffName(v.name) ?? validatePin(v.pin);

export const toNewStaffRequest = (v: NewStaffValues) => ({ name: v.name.trim(), pin: v.pin, lockedFeatureAllowed: v.lockedFeatureAllowed });

/** 원장 PIN 재설정 칸. 새 PIN은 두 번 받아 오타를 막는다 */
export type OwnerPinValues = { password: string; pin: string; pinConfirm: string };

export function validateOwnerPin(v: OwnerPinValues): string | null {
  if (!v.password) return '매장 계정 비밀번호를 입력해 주세요';
  const pin = validatePin(v.pin);
  if (pin) return pin;
  if (v.pin !== v.pinConfirm) return '새 PIN 두 칸이 서로 달라요';
  return null;
}

// ── 기능별 공용/PIN (FR-ACC-05, CONTRACTS 3장 「기능 키」) ──
// 순서와 설명은 CONTRACTS 3장 표를 따른다. fixed: 바꿀 수 없는 항목 (E8)
export const FEATURES: { key: string; label: string; fixed: boolean }[] = [
  { key: 'DASHBOARD_SEARCH', label: '대시보드, 기능 검색', fixed: true },
  { key: 'RESERVATION', label: '예약 등록 · 수정, 결제 등록', fixed: false },
  { key: 'INSIGHT', label: '인사이트', fixed: false },
  { key: 'CUSTOMER_VIEW', label: '고객 조회 (연락처 마스킹)', fixed: false },
  { key: 'CUSTOMER_WRITE', label: '고객 신규 등록, 시술 기록 · 사진, 취향 · 주의사항, 상담 메모 작성', fixed: false },
  { key: 'CONSENT', label: '동의 받기 · 철회', fixed: true },
  { key: 'CONTACT_FULL', label: '고객 연락처 전체 보기', fixed: false },
  { key: 'CUSTOMER_EDIT', label: '고객 기본 정보 수정 · 동의 정정, 상담 메모 수정 · 삭제, 방문 수정 · 삭제', fixed: false },
  { key: 'SALES_ANALYTICS', label: '매출 · 성과 분석 조회', fixed: false },
  { key: 'PAYMENT_EDIT', label: '결제 수정 · 취소', fixed: false },
  { key: 'CAMPAIGN_VIEW', label: '캠페인 목록 · 결과 조회', fixed: false },
  { key: 'CAMPAIGN_SEND', label: '캠페인 작성 · 검토 체크 · 발송, 가상 휴대폰', fixed: false },
  { key: 'OWNER_PIN', label: '매장 설정 · 토글, 스탭 · PIN 관리, 고객 삭제(파기), 예약 제한', fixed: true },
];

export const ACCESS_LABEL: Record<Access, string> = { PUBLIC: '공용', PIN: 'PIN', OWNER_PIN: '원장 PIN' };

/** 바꾼 토글만 골라 PUT /api/feature-locks 본문으로 (보낸 키만 바뀐다, F38). 고정 항목은 보내지 않는다 */
export function changedFeatures(saved: Record<string, Access>, edited: Record<string, Access>): Record<string, 'PUBLIC' | 'PIN'> {
  const out: Record<string, 'PUBLIC' | 'PIN'> = {};
  for (const f of FEATURES) {
    if (f.fixed) continue;
    const v = edited[f.key];
    if ((v === 'PUBLIC' || v === 'PIN') && v !== saved[f.key]) out[f.key] = v;
  }
  return out;
}
