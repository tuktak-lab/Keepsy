import { test } from 'node:test';
import assert from 'node:assert/strict';
import { elapsedText } from './retention.ts';

test('기준일부터 오늘까지를 N년 M개월로 센다', () => {
  assert.equal(elapsedText('2022-11-25', '2026-11-25'), '4년');
  assert.equal(elapsedText('2023-01-10', '2026-11-25'), '3년 10개월');
  assert.equal(elapsedText('2026-03-25', '2026-11-25'), '8개월');
});

test('날이 덜 차면 그달은 세지 않는다', () => {
  assert.equal(elapsedText('2023-11-26', '2026-11-25'), '2년 11개월');
  assert.equal(elapsedText('2026-11-01', '2026-11-25'), '1개월 미만');
});
