// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatDayOffDate, monthRange, sameDays, shiftMonth, toDayOffRequest, toggleDay, validateDayOff } from './dayOffs.ts';

test('monthRange: 그 달의 첫날부터 마지막 날까지 (31일을 넘지 않는다)', () => {
  assert.deepEqual(monthRange(2026, 10), { from: '2026-11-01', to: '2026-11-30' });
  assert.deepEqual(monthRange(2026, 11), { from: '2026-12-01', to: '2026-12-31' });
  assert.deepEqual(monthRange(2028, 1), { from: '2028-02-01', to: '2028-02-29' });
});

test('shiftMonth: 해를 넘겨 앞뒤로 옮긴다', () => {
  assert.deepEqual(shiftMonth(2026, 11, 1), { year: 2027, month0: 0 });
  assert.deepEqual(shiftMonth(2026, 0, -1), { year: 2025, month0: 11 });
  assert.deepEqual(shiftMonth(2026, 10, 0), { year: 2026, month0: 10 });
});

test('formatDayOffDate: 월 · 일 · 요일', () => {
  assert.equal(formatDayOffDate('2026-11-24'), '11월 24일 (화)');
  assert.equal(formatDayOffDate('2026-11-29'), '11월 29일 (일)');
});

test('validateDayOff: 날짜 · 지난 날짜 · 대상 · 사유 길이', () => {
  const ok = { date: '2026-11-24', target: 'SHOP', reason: '' };
  assert.equal(validateDayOff(ok, '2026-11-24'), null);
  assert.equal(validateDayOff({ ...ok, target: '3' }, '2026-11-24'), null);
  assert.equal(validateDayOff({ ...ok, date: '' }, '2026-11-24'), '휴무 날짜를 골라 주세요');
  assert.equal(validateDayOff(ok, '2026-11-25'), '오늘이나 그 뒤 날짜만 휴무로 정할 수 있어요');
  assert.equal(validateDayOff({ ...ok, target: '' }, '2026-11-24'), '매장 휴무인지 스탭 휴무인지 골라 주세요');
  assert.equal(validateDayOff({ ...ok, reason: 'a'.repeat(101) }, '2026-11-24'), '사유는 100자 이하로 적어 주세요');
  assert.equal(validateDayOff({ ...ok, reason: ` ${'a'.repeat(100)} ` }, '2026-11-24'), null);
});

test('toDayOffRequest: 매장 휴무는 staffId null, 빈 사유는 null', () => {
  assert.deepEqual(toDayOffRequest({ date: '2026-12-01', target: 'SHOP', reason: '  ' }), { date: '2026-12-01', staffId: null, reason: null });
  assert.deepEqual(toDayOffRequest({ date: '2026-12-01', target: '3', reason: ' 교육 ' }), { date: '2026-12-01', staffId: 3, reason: '교육' });
});

test('toggleDay: 켜고 끄며 월요일부터 순서를 지킨다', () => {
  assert.deepEqual(toggleDay([], 'SUNDAY'), ['SUNDAY']);
  assert.deepEqual(toggleDay(['SUNDAY'], 'MONDAY'), ['MONDAY', 'SUNDAY']);
  assert.deepEqual(toggleDay(['MONDAY', 'SUNDAY'], 'MONDAY'), ['SUNDAY']);
});

test('sameDays: 순서와 관계없이 같은 요일인지', () => {
  assert.equal(sameDays(['MONDAY', 'SUNDAY'], ['SUNDAY', 'MONDAY']), true);
  assert.equal(sameDays(['MONDAY'], ['MONDAY', 'SUNDAY']), false);
  assert.equal(sameDays([], []), true);
});
