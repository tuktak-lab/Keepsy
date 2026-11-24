// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nameFromSearch, toNewCustomerRequest, validateNewCustomer, type NewCustomerValues } from './newCustomer.ts';

const ok: NewCustomerValues = { name: '테스트고객', phone: '010-0000-0003', privacyAgreed: true, method: 'VERBAL' };

test('검증: 이름 · 휴대폰 · 개인정보 동의', () => {
  assert.equal(validateNewCustomer(ok), null);
  assert.match(validateNewCustomer({ ...ok, name: '  ' })!, /이름을 입력/);
  assert.match(validateNewCustomer({ ...ok, name: 'a'.repeat(51) })!, /50자/);
  assert.match(validateNewCustomer({ ...ok, phone: '02-1234-5678' })!, /010/);
  assert.match(validateNewCustomer({ ...ok, phone: '010-000-0003' })!, /11자리/);
  assert.match(validateNewCustomer({ ...ok, privacyAgreed: false })!, /개인정보/);
});

test('휴대폰은 하이픈이 있어도 없어도 된다', () => {
  assert.equal(validateNewCustomer({ ...ok, phone: '01000000003' }), null);
  assert.equal(validateNewCustomer({ ...ok, phone: ' 010-0000-0003 ' }), null);
});

test('요청 본문: 개인정보 동의만 보내고 방법은 고른 값', () => {
  assert.deepEqual(toNewCustomerRequest({ ...ok, name: ' 테스트고객 ', method: 'TABLET' }), {
    name: '테스트고객',
    phone: '010-0000-0003',
    consents: ['PRIVACY'],
    consentMethod: 'TABLET',
  });
});

test('검색어에서 이름 칸에 넣을 값: 글자는 이름, 숫자만이면 비움', () => {
  assert.equal(nameFromSearch(' 김민지 '), '김민지');
  assert.equal(nameFromSearch('0003'), '');
  assert.equal(nameFromSearch('010-0000'), '');
  assert.equal(nameFromSearch(''), '');
});
