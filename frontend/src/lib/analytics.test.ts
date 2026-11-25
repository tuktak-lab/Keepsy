// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { periodQuery, sumFunnels } from './analytics.ts';

test('periodQuery: 고정 기간은 period만, 직접 지정은 날짜 검사', () => {
  assert.equal(periodQuery('THIS_MONTH', '', ''), 'period=THIS_MONTH');
  assert.equal(periodQuery('CUSTOM', '2026-11-01', '2026-11-30'), 'period=CUSTOM&from=2026-11-01&to=2026-11-30');
  assert.equal(periodQuery('CUSTOM', '2026-11-01', '2026-11-01'), 'period=CUSTOM&from=2026-11-01&to=2026-11-01');
  assert.equal(periodQuery('CUSTOM', '', '2026-11-30'), null);
  assert.equal(periodQuery('CUSTOM', '2026-12-01', '2026-11-30'), null);
});

test('sumFunnels: 단계마다 더하고, 빈 목록은 0', () => {
  const a = { sentCount: 10, reachedCount: 9, rebookedCount: 3, revisitedCount: 2 };
  const b = { sentCount: 5, reachedCount: 5, rebookedCount: 1, revisitedCount: 0 };
  assert.deepEqual(sumFunnels([a, b]), { sentCount: 15, reachedCount: 14, rebookedCount: 4, revisitedCount: 2 });
  assert.deepEqual(sumFunnels([]), { sentCount: 0, reachedCount: 0, rebookedCount: 0, revisitedCount: 0 });
});
