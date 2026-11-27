// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bookedCount, closedDays, isActive, ratioText } from './dashboard.ts';

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

const WEEK = ['2026-11-23', '2026-11-24', '2026-11-25', '2026-11-26', '2026-11-27', '2026-11-28', '2026-11-29'];

test('closedDays: 정기 휴무 요일(open null)은 휴무', () => {
  const hours = [
    { dayOfWeek: 'MONDAY', open: null },
    { dayOfWeek: 'TUESDAY', open: '10:00' },
  ];
  assert.deepEqual(closedDays(WEEK, hours, []), [true, false, false, false, false, false, false]);
});

test('closedDays: 매장 임시 휴무일은 휴무, 스탭 한 명의 휴무는 아니다', () => {
  const dayOffs = [
    { date: '2026-11-25', staffId: null },
    { date: '2026-11-26', staffId: 3 },
  ];
  assert.deepEqual(closedDays(WEEK, [], dayOffs), [false, false, true, false, false, false, false]);
});
