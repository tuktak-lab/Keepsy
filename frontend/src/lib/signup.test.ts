// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EMPTY_SIGNUP, INDUSTRIES, STEPS, formatBusinessNumber, stepOf, toSignUpRequest, validateStep, type SignUpValues } from './signup.ts';

const ok: SignUpValues = {
  email: 'shop@example.com',
  password: 'abcd1234',
  passwordConfirm: 'abcd1234',
  ownerName: '김원장',
  ownerPin: '1234',
  shopName: '킵시 네일',
  businessNumber: '123-45-67890',
  phone: '02-000-0000',
  address: '서울시 가상구 예시로 1',
  industries: ['NAIL'],
};

test('formatBusinessNumber: 숫자만 남기고 000-00-00000 으로, 10자리까지', () => {
  assert.equal(formatBusinessNumber('1234567890'), '123-45-67890');
  assert.equal(formatBusinessNumber('123-45-6'), '123-45-6');
  assert.equal(formatBusinessNumber('12'), '12');
  assert.equal(formatBusinessNumber('123456789012345'), '123-45-67890');
  assert.equal(formatBusinessNumber('abc'), '');
});

test('validateStep: 올바른 값은 세 단계 모두 통과', () => {
  for (let i = 0; i < STEPS.length; i++) assert.deepEqual(validateStep(i, ok), {});
});

test('validateStep: 단계마다 자기 칸만 본다', () => {
  // 비밀번호와 확인이 둘 다 비어 있으면 서로 같으므로 확인 칸에는 오류가 없다
  assert.deepEqual(Object.keys(validateStep(0, EMPTY_SIGNUP)).sort(), ['email', 'ownerName', 'ownerPin', 'password']);
  assert.deepEqual(Object.keys(validateStep(1, EMPTY_SIGNUP)).sort(), ['address', 'businessNumber', 'phone', 'shopName']);
  assert.deepEqual(Object.keys(validateStep(2, EMPTY_SIGNUP)), ['industries']);
});

test('validateStep: 개인정보 규칙', () => {
  assert.ok(validateStep(0, { ...ok, email: 'shop' }).email);
  assert.ok(validateStep(0, { ...ok, password: 'short', passwordConfirm: 'short' }).password);
  assert.equal(validateStep(0, { ...ok, passwordConfirm: 'different1' }).passwordConfirm, '비밀번호가 서로 다릅니다');
  assert.ok(validateStep(0, { ...ok, ownerPin: '123' }).ownerPin);
  assert.ok(validateStep(0, { ...ok, ownerPin: '12a4' }).ownerPin);
});

test('validateStep: 사업자 정보 규칙', () => {
  assert.ok(validateStep(1, { ...ok, businessNumber: '1234567890' }).businessNumber);
  assert.ok(validateStep(1, { ...ok, phone: 'abc' }).phone);
  assert.ok(validateStep(1, { ...ok, shopName: 'a'.repeat(101) }).shopName);
  assert.ok(validateStep(1, { ...ok, address: '   ' }).address);
});

test('validateStep: 업종은 보기 6개 중 하나 이상, 중복 선택 가능', () => {
  assert.equal(INDUSTRIES.length, 6);
  assert.equal(validateStep(2, { ...ok, industries: [] }).industries, '매장 업종을 하나 이상 선택해 주세요');
  assert.ok(validateStep(2, { ...ok, industries: ['HAIR_SALON'] }).industries);
  assert.ok(validateStep(2, { ...ok, industries: ['NAIL', 'NAIL'] }).industries);
  assert.deepEqual(validateStep(2, { ...ok, industries: ['SKIN_CARE'] }), {});
  assert.deepEqual(validateStep(2, { ...ok, industries: ['NAIL', 'LASH', 'WAXING'] }), {});
});

test('stepOf: 서버가 돌려준 칸이 어느 단계인지, 모르면 -1', () => {
  assert.equal(stepOf('ownerPin'), 0);
  assert.equal(stepOf('businessNumber'), 1);
  assert.equal(stepOf('industries'), 2);
  assert.equal(stepOf('unknown'), -1);
});

test('toSignUpRequest: 확인용 칸은 빼고 글자 칸은 다듬는다', () => {
  const req = toSignUpRequest({ ...ok, email: ' shop@example.com ', shopName: ' 킵시 ' });
  assert.equal('passwordConfirm' in req, false);
  assert.equal(req.email, 'shop@example.com');
  assert.equal(req.shopName, '킵시');
  assert.deepEqual(req.industries, ['NAIL']);
});

test('toSignUpRequest: 고른 업종은 보기 순서로 정렬해 보낸다', () => {
  assert.deepEqual(toSignUpRequest({ ...ok, industries: ['LASH', 'NAIL', 'HAIR'] }).industries, ['NAIL', 'HAIR', 'LASH']);
});
