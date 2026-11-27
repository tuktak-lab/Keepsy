// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { rateText, revenuePath } from './campaignResult.ts';

test('revenuePath: 고객 ID가 있으면 쉼표로 이어 붙이고, 없으면 캠페인 합계만', () => {
  assert.equal(revenuePath(7), '/api/campaigns/7/revenue');
  assert.equal(revenuePath(7, []), '/api/campaigns/7/revenue');
  assert.equal(revenuePath(7, [3, 1, 2]), '/api/campaigns/7/revenue?customerIds=3,1,2');
});

test('rateText: 소수 첫째 자리 %, 분모 0이면 null', () => {
  assert.equal(rateText(3, 12), '25%');
  assert.equal(rateText(1, 3), '33.3%');
  assert.equal(rateText(0, 5), '0%');
  assert.equal(rateText(0, 0), null);
});
