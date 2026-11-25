// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextMark } from './insights.ts';

test('nextMark: 다른 표시는 바꾸고, 같은 표시를 다시 누르면 지운다', () => {
  assert.equal(nextMark(null, 'CONTACTED'), 'CONTACTED');
  assert.equal(nextMark('CONTACTED', 'ON_HOLD'), 'ON_HOLD');
  assert.equal(nextMark('ON_HOLD', 'ON_HOLD'), null);
});
