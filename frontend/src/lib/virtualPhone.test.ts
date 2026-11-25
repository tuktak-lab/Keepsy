import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizePhone } from './virtualPhone.ts';

test('하이픈이 있든 없든 010-0000-0000 모양으로 맞춘다', () => {
  assert.equal(normalizePhone('01000000002'), '010-0000-0002');
  assert.equal(normalizePhone(' 010-0000-0002 '), '010-0000-0002');
  assert.equal(normalizePhone('010-00000002'), '010-0000-0002');
});

test('010이 아니거나 자리 수가 틀리면 null', () => {
  assert.equal(normalizePhone('011-0000-0002'), null);
  assert.equal(normalizePhone('010-000-0002'), null);
  assert.equal(normalizePhone('010 0000 0002'), null);
  assert.equal(normalizePhone(''), null);
});
