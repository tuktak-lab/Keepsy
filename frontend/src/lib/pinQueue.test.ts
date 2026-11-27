// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { attachPinHost, currentPin, requestPin, settlePin } from './pinQueue.ts';

test('창을 그릴 화면이 없으면 바로 false', async () => {
  assert.equal(await requestPin({ owner: false, message: '' }), false);
});

test('직원 PIN · 원장 PIN 요청이 겹치면 차례로 보여 주고 둘 다 끝난다 (#492)', async () => {
  let changes = 0;
  const detach = attachPinHost(() => changes++);

  const staff = requestPin({ owner: false, message: 'PIN이 필요해요' });
  const owner = requestPin({ owner: true, message: '원장 PIN이 필요해요' });
  assert.equal(currentPin()?.owner, false);

  settlePin(true);
  assert.equal(await staff, true);
  assert.equal(currentPin()?.owner, true);

  settlePin(false);
  assert.equal(await owner, false);
  assert.equal(currentPin(), null);
  assert.ok(changes >= 4);
  detach();
});

test('화면이 사라지면 남은 요청은 취소로 끝난다', async () => {
  const detach = attachPinHost(() => {});
  const a = requestPin({ owner: false, message: '' });
  const b = requestPin({ owner: true, message: '' });
  detach();
  assert.deepEqual(await Promise.all([a, b]), [false, false]);
  assert.equal(currentPin(), null);
});
