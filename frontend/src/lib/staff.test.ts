// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FEATURES, changedFeatures, toNewStaffRequest, validateNewStaff, validateOwnerPin, validatePin, validateStaffName } from './staff.ts';

test('validateStaffName: 비었거나 50자 넘으면 오류, 앞뒤 공백은 빼고 센다', () => {
  assert.equal(validateStaffName(' 김스탭 '), null);
  assert.equal(validateStaffName('   '), '이름을 입력해 주세요');
  assert.equal(validateStaffName('가'.repeat(51)), '이름은 50자 이하입니다');
  assert.equal(validateStaffName(` ${'가'.repeat(50)} `), null);
});

test('validatePin: 숫자 4~6자리만', () => {
  assert.equal(validatePin('1234'), null);
  assert.equal(validatePin('123456'), null);
  for (const bad of ['123', '1234567', '12a4', '']) assert.equal(validatePin(bad), 'PIN은 숫자 4~6자리입니다');
});

test('validateNewStaff · toNewStaffRequest: 이름 먼저 검사, 이름은 다듬어 보낸다', () => {
  assert.equal(validateNewStaff({ name: '', pin: '12', lockedFeatureAllowed: false }), '이름을 입력해 주세요');
  assert.equal(validateNewStaff({ name: '김스탭', pin: '12', lockedFeatureAllowed: false }), 'PIN은 숫자 4~6자리입니다');
  assert.deepEqual(toNewStaffRequest({ name: ' 김스탭 ', pin: '4321', lockedFeatureAllowed: true }), { name: '김스탭', pin: '4321', lockedFeatureAllowed: true });
});

test('validateOwnerPin: 비밀번호 → PIN 형식 → 두 칸 일치', () => {
  const ok = { password: 'x', pin: '5678', pinConfirm: '5678' };
  assert.equal(validateOwnerPin(ok), null);
  assert.equal(validateOwnerPin({ ...ok, password: '' }), '매장 계정 비밀번호를 입력해 주세요');
  assert.equal(validateOwnerPin({ ...ok, pin: '56' }), 'PIN은 숫자 4~6자리입니다');
  assert.equal(validateOwnerPin({ ...ok, pinConfirm: '5679' }), '새 PIN 두 칸이 서로 달라요');
});

test('FEATURES: 기능 키 13개, 고정은 DASHBOARD_SEARCH · CONSENT · OWNER_PIN', () => {
  assert.equal(FEATURES.length, 13);
  assert.deepEqual(
    FEATURES.filter((f) => f.fixed).map((f) => f.key),
    ['DASHBOARD_SEARCH', 'CONSENT', 'OWNER_PIN'],
  );
});

test('changedFeatures: 바뀐 토글만, 고정 항목과 OWNER_PIN 값은 보내지 않는다', () => {
  const saved = Object.fromEntries(FEATURES.map((f) => [f.key, f.key === 'OWNER_PIN' ? 'OWNER_PIN' : 'PUBLIC'])) as Record<string, 'PUBLIC' | 'PIN' | 'OWNER_PIN'>;
  assert.deepEqual(changedFeatures(saved, saved), {});
  assert.deepEqual(changedFeatures(saved, { ...saved, INSIGHT: 'PIN', CONSENT: 'PIN' }), { INSIGHT: 'PIN' });
  assert.deepEqual(changedFeatures({ ...saved, CONTACT_FULL: 'PIN' }, { ...saved, CONTACT_FULL: 'PUBLIC' }), { CONTACT_FULL: 'PUBLIC' });
});
