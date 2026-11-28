// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deleteVisitConfirm, toVisitEditRequest, validateVisitRows, visitRows } from './visitEdit.ts';

const svc = (serviceMenuId: number | null, staffId: number | null, cycleExcluded = false) => ({ serviceMenuId, staff: staffId === null ? null : { id: staffId }, cycleExcluded });

test('수정 폼은 방문 전체 시술로 시작한다 (같은 날 여러 예약의 시술 모두, C6)', () => {
  assert.deepEqual(visitRows([svc(1, 10), svc(2, 11, true)], false), [
    { serviceMenuId: 1, staffId: 10, cycleExcluded: false },
    { serviceMenuId: 2, staffId: 11, cycleExcluded: true },
  ]);
});

test('가져온 방문 · 메뉴나 스탭이 없는 시술이 있으면 고칠 수 없다 (F25)', () => {
  assert.equal(visitRows([svc(1, 10)], true), null);
  assert.equal(visitRows([svc(null, null)], false), null);
  assert.equal(visitRows([svc(1, 10), svc(2, null)], false), null);
  assert.equal(visitRows([], false), null);
});

test('시술은 1개 이상, 같은 메뉴는 한 번만', () => {
  assert.equal(validateVisitRows([]), '실제 시술을 1개 이상 남겨 주세요');
  assert.ok(validateVisitRows([{ serviceMenuId: 1, staffId: 1, cycleExcluded: false }, { serviceMenuId: 1, staffId: 2, cycleExcluded: false }]));
  assert.equal(validateVisitRows([{ serviceMenuId: 1, staffId: 1, cycleExcluded: false }]), null);
});

test('요청 본문은 시술 전체 목록', () => {
  assert.deepEqual(toVisitEditRequest([{ serviceMenuId: 3, staffId: 4, cycleExcluded: true }]), { services: [{ serviceMenuId: 3, staffId: 4, cycleExcluded: true }] });
});

test('삭제 확인은 되돌려지는 것과 남는 것을 알려 준다', () => {
  const text = deleteVisitConfirm(false);
  for (const word of ['확정', '취소', '이력은 남아요', '사진', '예측']) assert.ok(text.includes(word), word);
  assert.ok(deleteVisitConfirm(true).includes('가져온'));
});
