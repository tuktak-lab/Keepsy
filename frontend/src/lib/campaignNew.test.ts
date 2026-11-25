import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isSeoulNight, parseIds, seoulLocalInput, seoulLocalToIso } from './campaignNew.ts';

test('야간은 서울 21:00부터 08:00 전까지', () => {
  assert.equal(isSeoulNight(new Date('2026-11-25T11:59:00Z')), false); // 20:59
  assert.equal(isSeoulNight(new Date('2026-11-25T12:00:00Z')), true); // 21:00
  assert.equal(isSeoulNight(new Date('2026-11-25T22:59:00Z')), true); // 다음 날 07:59
  assert.equal(isSeoulNight(new Date('2026-11-25T23:00:00Z')), false); // 08:00
});

test('예약 입력값은 서울 시각으로 보고 +09:00을 붙인다', () => {
  assert.equal(seoulLocalToIso('2026-11-26T11:00'), '2026-11-26T11:00:00+09:00');
  assert.equal(seoulLocalToIso('2026-11-26 11:00'), null);
  assert.equal(seoulLocalInput(new Date('2026-11-25T15:30:00Z')), '2026-11-26T00:30');
});

test('customerIds 쿼리는 양의 정수만 한 번씩', () => {
  assert.deepEqual(parseIds('3,1,3,x,-2,0'), [3, 1]);
  assert.deepEqual(parseIds(null), []);
});
