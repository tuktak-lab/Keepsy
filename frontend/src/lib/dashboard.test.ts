// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bookedCount, isActive, ratioText } from './dashboard.ts';

test('bookedCount: 확정 + 완료만 센다', () => {
  assert.equal(bookedCount({ CONFIRMED: 3, COMPLETED: 2, CANCELED: 4, NO_SHOW: 1 }), 5);
});

test('isActive: 취소 · 노쇼는 오늘 예약 목록에서 뺀다', () => {
  assert.deepEqual(['CONFIRMED', 'COMPLETED', 'CANCELED', 'NO_SHOW'].map(isActive), [true, true, false, false]);
});

test('ratioText: 소수 첫째 자리 %', () => {
  assert.equal(ratioText(0.42857), '42.9%');
  assert.equal(ratioText(0), '0%');
  assert.equal(ratioText(1), '100%');
});
