// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { consentChanges, type ConsentState } from './consents.ts';

const current: ConsentState[] = [
  { item: 'PRIVACY', agreed: true, recordedAt: '2026-11-01T10:00:00+09:00' },
  { item: 'PHOTO', agreed: false, recordedAt: null },
  { item: 'MARKETING', agreed: true, recordedAt: '2026-11-01T10:00:00+09:00' },
  { item: 'NIGHT_AD', agreed: false, recordedAt: null },
  { item: 'HEALTH', agreed: false, recordedAt: null },
];

test('바뀐 항목만, 항목 순서대로', () => {
  assert.deepEqual(consentChanges(current, { HEALTH: true, MARKETING: false, PHOTO: false }), [
    { item: 'MARKETING', agreed: false },
    { item: 'HEALTH', agreed: true },
  ]);
});

test('그대로면 바꿀 것이 없다', () => {
  assert.deepEqual(consentChanges(current, { PRIVACY: true, MARKETING: true }), []);
});

test('개인정보 동의 철회는 보내지 않고, 동의는 보낸다', () => {
  assert.deepEqual(consentChanges(current, { PRIVACY: false }), []);
  assert.deepEqual(consentChanges([{ ...current[0], agreed: false }], { PRIVACY: true }), [{ item: 'PRIVACY', agreed: true }]);
});
