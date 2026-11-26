// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { periodQuery } from './analytics.ts';

test('periodQuery: 고정 기간은 period만, 직접 지정은 날짜 검사', () => {
  assert.equal(periodQuery('THIS_MONTH', '', ''), 'period=THIS_MONTH');
  assert.equal(periodQuery('CUSTOM', '2026-11-01', '2026-11-30'), 'period=CUSTOM&from=2026-11-01&to=2026-11-30');
  assert.equal(periodQuery('CUSTOM', '2026-11-01', '2026-11-01'), 'period=CUSTOM&from=2026-11-01&to=2026-11-01');
  assert.equal(periodQuery('CUSTOM', '', '2026-11-30'), null);
  assert.equal(periodQuery('CUSTOM', '2026-12-01', '2026-11-30'), null);
});
