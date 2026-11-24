// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseAmount, sumPrices, toClosingRequest, validateClosing, type ClosingValues } from './closing.ts';

const base: ClosingValues = {
  services: [{ serviceMenuId: 1, staffId: 2 }],
  amount: '58,000',
  method: 'CARD',
  memo: '',
};
const open = { completed: false, hasCustomer: true };

test('금액 입력: 쉼표 · 공백은 허용하고, 0 이상의 정수만', () => {
  assert.equal(parseAmount('58000'), 58000);
  assert.equal(parseAmount(' 58,000 '), 58000);
  assert.equal(parseAmount('0'), 0);
  assert.equal(parseAmount(''), null);
  assert.equal(parseAmount('-1'), null);
  assert.equal(parseAmount('1.5'), null);
  assert.equal(parseAmount('만원'), null);
  assert.equal(parseAmount('9'.repeat(30)), null);
});

test('시술 가격 합계', () => {
  assert.equal(sumPrices([58000, 55000]), 113000);
  assert.equal(sumPrices([]), 0);
});

test('검증: 확정 예약은 시술 · 스탭 · 금액을 본다', () => {
  assert.equal(validateClosing(base, open), null);
  assert.match(validateClosing({ ...base, services: [] }, open)!, /1개 이상/);
  assert.match(validateClosing({ ...base, services: [{ serviceMenuId: 1, staffId: 0 }] }, open)!, /담당 스탭/);
  assert.match(validateClosing({ ...base, amount: '' }, open)!, /금액/);
  assert.match(validateClosing({ ...base, memo: 'x'.repeat(1001) }, open)!, /1,000자/);
  assert.equal(validateClosing({ ...base, amount: '0' }, open), null); // 0원도 마감 가능
});

test('검증: 완료된 예약은 시술을 보지 않는다 (읽기 전용, E4)', () => {
  assert.equal(validateClosing({ ...base, services: [] }, { completed: true, hasCustomer: true }), null);
  assert.match(validateClosing({ ...base, services: [], amount: 'x' }, { completed: true, hasCustomer: true })!, /금액/);
});

test('요청 본문: 확정 예약은 시술을 보내고 금액은 숫자로', () => {
  assert.deepEqual(toClosingRequest({ ...base, memo: ' 다음엔 프렌치 ' }, open), {
    services: [{ serviceMenuId: 1, staffId: 2 }],
    amount: 58000,
    method: 'CARD',
    memo: '다음엔 프렌치',
  });
});

test('요청 본문: 완료된 예약은 시술을 보내지 않고, 고객 없는 방문은 메모를 보내지 않는다 (E3 · E4)', () => {
  const body = toClosingRequest({ ...base, memo: '무시됨' }, { completed: true, hasCustomer: false });
  assert.equal('services' in body, false);
  assert.equal(body.memo, null);
  assert.equal(body.amount, 58000);
});
