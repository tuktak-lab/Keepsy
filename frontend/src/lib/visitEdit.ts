// 방문 수정 · 삭제 (FR-VIS-03 · 05, F8, F76, 순수 함수). 고객 상세 히스토리에서 방문 전체 시술을 고친다.
// PATCH /api/visits/{id}는 보낸 목록으로 방문의 시술 전체를 바꾸므로, 같은 날 다른 예약의 시술까지 빠짐없이 보내야 한다 (C6).
import { t } from './i18n.ts';

/** 히스토리 시술 한 건에서 수정에 쓰는 칸 (GET /api/customers/{id}/history, F76) */
export type HistoryServiceRef = { serviceMenuId: number | null; staff: { id: number } | null; cycleExcluded: boolean };

/** 수정 폼의 시술 한 줄 = PATCH 요청의 `services` 항목 */
export type VisitRow = { serviceMenuId: number; staffId: number; cycleExcluded: boolean };

/**
 * 수정 폼 시작값: 방문 전체 시술. 가져온 방문이거나 메뉴 · 스탭이 없는 시술이 있으면 null — 그 방문은 고칠 수 없다(서버 409, F25).
 * 삭제는 가져온 방문도 된다(방문만 지움, F8).
 */
export function visitRows(services: HistoryServiceRef[], imported: boolean): VisitRow[] | null {
  if (imported || services.length === 0) return null;
  const rows: VisitRow[] = [];
  for (const s of services) {
    if (s.serviceMenuId === null || s.staff === null) return null;
    rows.push({ serviceMenuId: s.serviceMenuId, staffId: s.staff.id, cycleExcluded: s.cycleExcluded });
  }
  return rows;
}

/** 첫 번째 오류 사유. 없으면 null. 메뉴 · 스탭 존재는 서버가 다시 본다 */
export function validateVisitRows(rows: VisitRow[]): string | null {
  if (rows.length === 0) return t('visit.edit.needOne');
  if (new Set(rows.map((r) => r.serviceMenuId)).size !== rows.length) return t('visit.edit.duplicateMenu');
  return null;
}

/** PATCH /api/visits/{id} 본문 */
export const toVisitEditRequest = (rows: VisitRow[]) => ({
  services: rows.map((r) => ({ serviceMenuId: r.serviceMenuId, staffId: r.staffId, cycleExcluded: r.cycleExcluded })),
});

/** 방문 삭제 확인 문구: 무엇이 되돌려지고 무엇이 남는지 (F8, F62). 가져온 방문은 방문 기록만 지운다 */
export function deleteVisitConfirm(imported: boolean): string {
  if (imported) return t('visit.delete.confirmImported');
  return (
    ['visit.delete.confirmTitle', 'visit.delete.confirmReservations', 'visit.delete.confirmPayments', 'visit.delete.confirmRecords', 'visit.delete.confirmPrediction'] as const
  )
    .map((k) => t(k))
    .join('\n');
}
