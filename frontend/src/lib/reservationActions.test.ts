// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ACTION_TEXT, actionPath, canMarkNoShow, confirmText } from './reservationActions.ts';

test('경로: 취소와 노쇼는 예약 ID 아래 동작 이름', () => {
  assert.equal(actionPath(12, 'cancel'), '/api/reservations/12/cancel');
  assert.equal(actionPath(12, 'no-show'), '/api/reservations/12/no-show');
});

test('노쇼는 시작 시각이 지난 뒤에만 (C8): 시작하는 순간부터 가능', () => {
  const start = Date.parse('2026-11-24T14:00:00+09:00');
  assert.equal(canMarkNoShow(start, start - 1), false);
  assert.equal(canMarkNoShow(start, start), true);
  assert.equal(canMarkNoShow(start, start + 60_000), true);
});

test('확인 문장: 고객 있는 노쇼는 횟수가 늘어난다고, 고객 없는 노쇼는 안 잡힌다고 알린다 (E3)', () => {
  assert.match(confirmText('no-show', true), /노쇼 횟수가 1회 늘고/);
  assert.match(confirmText('no-show', false), /어느 고객의 노쇼 횟수에도 잡히지 않아요/);
  assert.match(confirmText('cancel', true), /되돌릴 수 없어요/);
  assert.equal(confirmText('cancel', true), confirmText('cancel', false));
});

test('완료 안내 문구', () => {
  assert.equal(ACTION_TEXT.cancel.done, '예약을 취소했어요.');
  assert.equal(ACTION_TEXT['no-show'].done, '노쇼로 처리했어요.');
});
