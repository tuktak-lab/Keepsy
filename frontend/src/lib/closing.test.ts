// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CONSENT_REQUEST_LABEL,
  asksMenuExclusion,
  canExcludeFromCycle,
  parseAmount,
  sumPrices,
  toClosingRequest,
  validateClosing,
  validatePhotos,
  type ClosingValues,
} from './closing.ts';

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
    services: [{ serviceMenuId: 1, staffId: 2, cycleExcluded: false }], // 고객이 있으면 주기 계산 제외 값도 함께 (FR-VIS-05)
    amount: 58000,
    method: 'CARD',
    memo: '다음엔 프렌치',
  });
});

test('요청 본문: 고객이 있으면 시술마다 주기 계산 제외를 보낸다 (FR-VIS-05)', () => {
  const body = toClosingRequest(
    {
      ...base,
      services: [
        { serviceMenuId: 1, staffId: 2, cycleExcluded: true },
        { serviceMenuId: 2, staffId: 2 },
      ],
    },
    open,
  );
  assert.deepEqual(body.services, [
    { serviceMenuId: 1, staffId: 2, cycleExcluded: true },
    { serviceMenuId: 2, staffId: 2, cycleExcluded: false },
  ]);
  assert.equal('noCustomer' in body, false);
});

test('요청 본문: 고객 없음 마감은 noCustomer만 보내고 메모 · 주기 계산 제외는 보내지 않는다 (E3)', () => {
  const body = toClosingRequest(
    { ...base, memo: '무시됨', services: [{ serviceMenuId: 1, staffId: 2, cycleExcluded: true }] },
    { completed: false, hasCustomer: false, noCustomer: true },
  );
  assert.equal(body.noCustomer, true);
  assert.equal(body.memo, null);
  assert.deepEqual(body.services, [{ serviceMenuId: 1, staffId: 2 }]);
});

test('noCustomer는 고객이 이미 있거나 완료된 예약에는 보내지 않는다 (서버가 invalid로 거절)', () => {
  assert.equal('noCustomer' in toClosingRequest(base, { completed: false, hasCustomer: true, noCustomer: true }), false);
  assert.equal('noCustomer' in toClosingRequest(base, { completed: true, hasCustomer: false, noCustomer: true }), false);
});

test('주기 계산 제외 안내: 일반 메뉴만 물어보고, 항상 제외인 메뉴는 체크를 보여 주지 않는다', () => {
  assert.equal(asksMenuExclusion('NORMAL'), true);
  assert.equal(asksMenuExclusion('END_SIGNAL'), false);
  assert.equal(asksMenuExclusion('EXCLUDE'), false);
  assert.equal(canExcludeFromCycle('EXCLUDE'), false);
  assert.equal(canExcludeFromCycle('NORMAL'), true);
  assert.equal(canExcludeFromCycle('END_SIGNAL'), true);
});

test('요청 본문: 완료된 예약은 시술을 보내지 않고, 고객 없는 방문은 메모를 보내지 않는다 (E3 · E4)', () => {
  const body = toClosingRequest({ ...base, memo: '무시됨' }, { completed: true, hasCustomer: false });
  assert.equal('services' in body, false);
  assert.equal(body.memo, null);
  assert.equal(body.amount, 58000);
});

test('validatePhotos: 10장 · JPG/PNG · 빈 파일 · 10MB', () => {
  const jpg = { size: 1000, type: 'image/jpeg' };
  assert.equal(validatePhotos([]), null);
  assert.equal(validatePhotos([jpg, { size: 2000, type: 'image/png' }]), null);
  assert.equal(validatePhotos(Array.from({ length: 10 }, () => jpg)), null);
  assert.equal(validatePhotos(Array.from({ length: 11 }, () => jpg)), '사진은 한 번에 10장까지 올릴 수 있어요');
  assert.equal(validatePhotos([{ size: 1000, type: 'image/gif' }]), 'JPG · PNG 사진만 올릴 수 있어요');
  assert.equal(validatePhotos([{ size: 0, type: 'image/png' }]), '빈 파일은 올릴 수 없어요');
  assert.equal(validatePhotos([{ size: 10 * 1024 * 1024, type: 'image/png' }]), null);
  assert.equal(validatePhotos([{ size: 10 * 1024 * 1024 + 1, type: 'image/png' }]), '사진 한 장은 10MB 이하여야 해요');
});

test('CONSENT_REQUEST_LABEL: 마감 카드에서 받는 동의 4개 (PRIVACY 제외)', () => {
  assert.deepEqual(Object.keys(CONSENT_REQUEST_LABEL), ['PHOTO', 'MARKETING', 'NIGHT_AD', 'HEALTH']);
});
