import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dueText } from './consentReconfirm.ts';

test('기한 전이면 남은 날을 D-N으로', () => {
  assert.deepEqual(dueText('2026-12-25', '2026-11-25'), { text: 'D-30', overdue: false });
  assert.deepEqual(dueText('2026-11-26', '2026-11-25'), { text: 'D-1', overdue: false });
});

test('기한 당일과 지난 뒤는 overdue', () => {
  assert.deepEqual(dueText('2026-11-25', '2026-11-25'), { text: '오늘 기한', overdue: true });
  assert.deepEqual(dueText('2026-11-20', '2026-11-25'), { text: '5일 지남', overdue: true });
});

test('해가 바뀌어도 일수로 센다', () => {
  assert.deepEqual(dueText('2027-01-01', '2026-12-31'), { text: 'D-1', overdue: false });
});
