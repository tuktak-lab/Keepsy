// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { countBytes, disallowedVariables, messageKind } from './messageTemplate.ts';

test('바이트: ASCII 1, 한글 2, 줄바꿈 1', () => {
  assert.equal(countBytes('abc'), 3);
  assert.equal(countBytes('안녕'), 4);
  assert.equal(countBytes('a\r\nb'), 3);
  assert.equal(countBytes('a\nb'), 3);
});

test('메시지 종류: 동의 재확인만 안내', () => {
  assert.equal(messageKind('CONSENT_RECONFIRM'), 'INFO');
  assert.equal(messageKind('ETC'), 'AD');
});

test('용도별 변수', () => {
  assert.deepEqual(disallowedVariables('{고객명}님 {최근시술} {경과일}일', 'REVISIT'), []);
  assert.deepEqual(disallowedVariables('{고객명} {동의일} {동의일}', 'REVISIT'), ['동의일']);
  assert.deepEqual(disallowedVariables('{동의일} {야간광고동의} {경과일}', 'CONSENT_RECONFIRM'), ['경과일']);
  assert.deepEqual(disallowedVariables('{이름}', 'ETC'), ['이름']);
});
