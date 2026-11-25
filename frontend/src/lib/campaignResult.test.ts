// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { rateText } from './campaignResult.ts';

test('rateText: 소수 첫째 자리 %, 분모 0이면 null', () => {
  assert.equal(rateText(3, 12), '25%');
  assert.equal(rateText(1, 3), '33.3%');
  assert.equal(rateText(0, 5), '0%');
  assert.equal(rateText(0, 0), null);
});
