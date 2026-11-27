// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EMPTY_SIGNUP, INDUSTRIES, STEPS, stepOf, toSignUpRequest, validateStep, type SignUpValues } from './signup.ts';

const ok: SignUpValues = {
  email: 'shop@example.com',
  password: 'abcd1234',
  passwordConfirm: 'abcd1234',
  ownerName: '김원장',
  ownerPin: '1234',
  shopName: '킵시 네일',
  phone: '02-000-0000',
  industries: ['NAIL'],
};

test('validateStep: 올바른 값은 세 단계 모두 통과', () => {
  for (let i = 0; i < STEPS.length; i++) assert.deepEqual(validateStep(i, ok), {});
});

test('validateStep: 단계마다 자기 칸만 본다', () => {
  // 비밀번호와 확인이 둘 다 비어 있으면 서로 같으므로 확인 칸에는 오류가 없다
  assert.deepEqual(Object.keys(validateStep(0, EMPTY_SIGNUP)).sort(), ['email', 'ownerName', 'ownerPin', 'password']);
  assert.deepEqual(Object.keys(validateStep(1, EMPTY_SIGNUP)).sort(), ['phone', 'shopName']);
  assert.deepEqual(Object.keys(validateStep(2, EMPTY_SIGNUP)), ['industries']);
});

test('validateStep: 개인정보 규칙', () => {
  assert.ok(validateStep(0, { ...ok, email: 'shop' }).email);
  assert.ok(validateStep(0, { ...ok, password: 'short', passwordConfirm: 'short' }).password);
  assert.equal(validateStep(0, { ...ok, passwordConfirm: 'different1' }).passwordConfirm, '비밀번호가 서로 다릅니다');
  assert.ok(validateStep(0, { ...ok, ownerPin: '123' }).ownerPin);
  assert.ok(validateStep(0, { ...ok, ownerPin: '12a4' }).ownerPin);
});

test('validateStep: 매장 정보 규칙. 연락처는 필수이고 매장 설정과 같은 형식 (F73)', () => {
  assert.equal(validateStep(1, { ...ok, phone: '   ' }).phone, '매장 연락처를 입력해 주세요');
  assert.ok(validateStep(1, { ...ok, phone: 'abc' }).phone);
  assert.ok(validateStep(1, { ...ok, phone: '12345' }).phone);
  assert.deepEqual(validateStep(1, { ...ok, phone: '1588-0000' }), {});
  assert.deepEqual(validateStep(1, { ...ok, phone: '01000000000' }), {});
  assert.ok(validateStep(1, { ...ok, shopName: 'a'.repeat(101) }).shopName);
});

test('validateStep: 업종은 시술 카테고리 5개 중 하나 이상, 중복 선택 가능', () => {
  assert.deepEqual(INDUSTRIES.map(([value]) => value), ['NAIL', 'LASH', 'WAXING', 'SEMI_PERMANENT', 'ETC']);
  assert.equal(validateStep(2, { ...ok, industries: [] }).industries, '매장 업종을 하나 이상 선택해 주세요');
  assert.ok(validateStep(2, { ...ok, industries: ['HAIR'] }).industries); // 옛 보기: 서버가 거절한다
  assert.ok(validateStep(2, { ...ok, industries: ['SKIN_CARE'] }).industries);
  assert.ok(validateStep(2, { ...ok, industries: ['NAIL', 'NAIL'] }).industries);
  assert.deepEqual(validateStep(2, { ...ok, industries: ['SEMI_PERMANENT'] }), {});
  assert.deepEqual(validateStep(2, { ...ok, industries: ['NAIL', 'LASH', 'WAXING'] }), {});
});

test('stepOf: 서버가 돌려준 칸이 어느 단계인지, 모르면 -1', () => {
  assert.equal(stepOf('ownerPin'), 0);
  assert.equal(stepOf('phone'), 1);
  assert.equal(stepOf('businessNumber'), -1); // 받지 않는 칸 (F73)
  assert.equal(stepOf('industries'), 2);
  assert.equal(stepOf('unknown'), -1);
});

test('toSignUpRequest: 확인용 칸은 빼고 글자 칸은 다듬는다', () => {
  const req = toSignUpRequest({ ...ok, email: ' shop@example.com ', shopName: ' 킵시 ', phone: ' 02-000-0000 ' });
  assert.equal('passwordConfirm' in req, false);
  assert.equal('businessNumber' in req, false);
  assert.equal('address' in req, false);
  assert.equal(req.email, 'shop@example.com');
  assert.equal(req.shopName, '킵시');
  assert.equal(req.phone, '02-000-0000');
  assert.deepEqual(req.industries, ['NAIL']);
});

test('toSignUpRequest: 고른 업종은 보기 순서로 정렬해 보낸다', () => {
  assert.deepEqual(toSignUpRequest({ ...ok, industries: ['ETC', 'NAIL', 'SEMI_PERMANENT', 'LASH'] }).industries, ['NAIL', 'LASH', 'SEMI_PERMANENT', 'ETC']);
});
