// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  addDays,
  axisRange,
  closedRanges,
  dayIndex,
  dayOffOn,
  layout,
  monthCells,
  seoulParts,
  toneOf,
  weekDates,
  weekStart,
  type OpeningHour,
} from './calendar.ts';

test('날짜 계산: 달 · 해를 넘겨도 맞다', () => {
  assert.equal(addDays('2026-11-30', 1), '2026-12-01');
  assert.equal(addDays('2026-01-01', -1), '2025-12-31');
  assert.equal(addDays('2026-02-27', 2), '2026-03-01');
});

test('주간은 월요일부터 7일 (F10)', () => {
  assert.equal(dayIndex('2026-11-23'), 0); // 월요일
  assert.equal(dayIndex('2026-11-29'), 6); // 일요일
  assert.equal(weekStart('2026-11-23'), '2026-11-23');
  assert.equal(weekStart('2026-11-24'), '2026-11-23');
  assert.equal(weekStart('2026-11-29'), '2026-11-23');
  assert.deepEqual(weekDates('2026-11-23'), [
    '2026-11-23',
    '2026-11-24',
    '2026-11-25',
    '2026-11-26',
    '2026-11-27',
    '2026-11-28',
    '2026-11-29',
  ]);
});

test('미니 달력 칸은 월요일 시작이고 줄 수만큼 채운다', () => {
  const cells = monthCells(2026, 10); // 2026년 11월 (1일 = 일요일)
  assert.equal(cells.length % 7, 0);
  assert.equal(cells[0].date, '2026-10-26');
  assert.equal(cells[0].inMonth, false);
  assert.equal(cells[6].date, '2026-11-01');
  assert.equal(cells[6].inMonth, true);
  assert.equal(cells.at(-1)!.date, '2026-12-06');
});

test('시각은 서울 기준 날짜 · 분으로 읽는다', () => {
  assert.deepEqual(seoulParts('2026-11-24T14:30:00+09:00'), { date: '2026-11-24', minutes: 870 });
  assert.deepEqual(seoulParts('2026-11-23T15:10:00Z'), { date: '2026-11-24', minutes: 10 });
  assert.deepEqual(seoulParts('2026-11-23T23:59:00+09:00'), { date: '2026-11-23', minutes: 1439 });
});

test('캘린더 상태: 취소 · 노쇼는 그리지 않고, 끝난 확정 예약은 마감 미완료', () => {
  const now = Date.parse('2026-11-24T12:00:00+09:00');
  const before = Date.parse('2026-11-24T13:00:00+09:00');
  const after = Date.parse('2026-11-24T11:00:00+09:00');
  assert.equal(toneOf('CONFIRMED', before, now), 'booked');
  assert.equal(toneOf('CONFIRMED', after, now), 'unclosed');
  assert.equal(toneOf('CONFIRMED', now, now), 'unclosed'); // 끝나는 순간부터
  assert.equal(toneOf('COMPLETED', after, now), 'done');
  assert.equal(toneOf('CANCELED', before, now), null);
  assert.equal(toneOf('NO_SHOW', after, now), null);
});

test('겹침 배치: 겹치는 예약만 가로로 나누고, 끝나는 시각에 시작하는 예약은 겹치지 않는다', () => {
  const list = [
    { id: 1, start: 600, end: 690, day: 0 },
    { id: 2, start: 630, end: 720, day: 0 },
    { id: 3, start: 690, end: 750, day: 0 }, // 1이 끝나는 시각에 시작
    { id: 4, start: 600, end: 660, day: 1 }, // 다른 날은 따로
  ];
  const { pos, more } = layout(list, (a) => a.day, 7, 2);
  assert.deepEqual(pos.get(1), { lane: 0, n: 2, hidden: false });
  assert.deepEqual(pos.get(2), { lane: 1, n: 2, hidden: false });
  assert.deepEqual(pos.get(3), { lane: 0, n: 2, hidden: false });
  assert.deepEqual(pos.get(4), { lane: 0, n: 1, hidden: false });
  assert.deepEqual(more, []);
});

test('겹침 배치: 칸 수를 넘으면 앞 칸만 그리고 나머지는 +N건으로 접는다', () => {
  const list = [
    { id: 1, start: 600, end: 720 },
    { id: 2, start: 600, end: 720 },
    { id: 3, start: 600, end: 720 },
  ];
  const { pos, more } = layout(list, () => 0, 1, 2);
  assert.equal(pos.get(1)!.hidden, false);
  assert.equal(pos.get(2)!.hidden, true);
  assert.equal(pos.get(3)!.hidden, true);
  assert.deepEqual(more, [{ group: 0, start: 600, end: 720, lane: 1, n: 2, count: 2 }]);
});

const hours = (open: string | null, close: string | null): OpeningHour[] =>
  ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map((dayOfWeek, i) => ({
    dayOfWeek,
    open: i === 6 ? null : open,
    close: i === 6 ? null : close,
  }));

test('시간축: 영업시간과 예약을 모두 담고, 아무것도 없으면 9~20시', () => {
  assert.deepEqual(axisRange(hours('10:00', '19:30'), []), { start: 10, end: 20 });
  assert.deepEqual(axisRange(hours('10:00', '19:30'), [{ start: 9 * 60 + 30, end: 21 * 60 }]), { start: 9, end: 21 });
  assert.deepEqual(axisRange(hours(null, null), []), { start: 9, end: 20 });
  assert.deepEqual(axisRange([], []), { start: 9, end: 20 });
});

test('영업시간 밖 구간: 앞뒤를 막고, 정기 휴무 요일은 하루 전체', () => {
  const axis = { start: 9, end: 21 };
  assert.deepEqual(closedRanges(hours('10:00', '19:00'), 0, axis), [
    [540, 600],
    [1140, 1260],
  ]);
  assert.deepEqual(closedRanges(hours('09:00', '21:00'), 0, axis), []);
  assert.deepEqual(closedRanges(hours('10:00', '19:00'), 6, axis), [[540, 1260]]);
});

test('휴무일: 매장 휴무는 항상, 스탭 휴무는 그 스탭을 볼 때만', () => {
  const offs = [
    { id: 1, date: '2026-11-25', staffId: null, reason: '매장 휴무' },
    { id: 2, date: '2026-11-26', staffId: 7, reason: null },
  ];
  assert.equal(dayOffOn(offs, '2026-11-25', null), '매장 휴무');
  assert.equal(dayOffOn(offs, '2026-11-26', null), null);
  assert.equal(dayOffOn(offs, '2026-11-26', 7), '');
  assert.equal(dayOffOn(offs, '2026-11-26', 8), null);
  assert.equal(dayOffOn(offs, '2026-11-27', 7), null);
});
