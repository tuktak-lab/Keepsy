// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hmOf } from './calendar.ts';
import { defaultMinutes, detailsText, MINUTE_STEPS, toRequest, toStartAt, validate, type FormValues } from './reservationForm.ts';

const base: FormValues = {
  channel: 'STORE',
  customerMode: 'none',
  customerId: null,
  staffId: 1,
  date: '2026-11-25',
  startTime: '14:00',
  minutes: 90,
  menuIds: [1],
  consultFirst: false,
  memo: '',
};

test('소요 시간 기본값: 시술 합계를 30분 단위로 올림, 30분~8시간', () => {
  assert.equal(defaultMinutes([90]), 90);
  assert.equal(defaultMinutes([90, 60]), 150);
  assert.equal(defaultMinutes([40]), 60);
  assert.equal(defaultMinutes([]), 30);
  assert.equal(defaultMinutes([300, 300]), 480);
  assert.deepEqual([MINUTE_STEPS[0], MINUTE_STEPS.at(-1)], [30, 480]);
});

test('시각 변환은 서울 기준', () => {
  assert.equal(toStartAt('2026-11-25', '14:30'), '2026-11-25T14:30:00+09:00');
  assert.equal(hmOf('2026-11-25T14:30:00+09:00'), '14:30');
  assert.equal(hmOf('2026-11-25T05:30:00Z'), '14:30');
});

test('검증: 고객 · 스탭 · 시술 · 시각을 거르고, 고객 없는 예약은 고객 없이 통과', () => {
  assert.equal(validate(base, false), null);
  assert.match(validate({ ...base, customerMode: 'pick' }, false)!, /고객을 고르거나/);
  assert.equal(validate({ ...base, customerMode: 'pick', customerId: 3 }, false), null);
  assert.match(validate({ ...base, staffId: null }, false)!, /스탭/);
  assert.match(validate({ ...base, menuIds: [] }, false)!, /시술/);
  assert.match(validate({ ...base, startTime: '' }, false)!, /시작 시각/);
  assert.match(validate({ ...base, memo: 'x'.repeat(1001) }, false)!, /1,000자/);
});

test('검증: 수정은 고객을 바꾸지 않으므로 고객 입력을 보지 않는다', () => {
  assert.equal(validate({ ...base, customerMode: 'pick', customerId: null }, true), null);
});

test('등록 본문: 고객 있는 예약은 메모를 보내고, 고객 없는 예약은 메모를 보내지 않는다 (E3)', () => {
  const picked: FormValues = { ...base, customerMode: 'pick', customerId: 3, memo: ' 손톱이 약해요 ' };
  assert.deepEqual(toRequest(picked, false, true), {
    customerId: 3,
    staffId: 1,
    startAt: '2026-11-25T14:00:00+09:00',
    minutes: 90,
    channel: 'STORE',
    consultFirst: false,
    memo: '손톱이 약해요',
    serviceMenuIds: [1],
  });
  assert.equal(toRequest({ ...base, memo: '무시됨' }, false, false).memo, null);
  assert.equal(toRequest({ ...base, memo: '무시됨' }, false, false).customerId, null);
});

test('수정 본문에는 고객을 넣지 않는다', () => {
  const body = toRequest({ ...base, customerMode: 'pick', customerId: 3 }, true, true);
  assert.equal('customerId' in body, false);
});

test('invalid 응답의 칸별 사유를 한 줄로', () => {
  assert.equal(detailsText([{ field: 'minutes', reason: '30분 단위여야 합니다' }, { field: 'memo', reason: '너무 깁니다' }]), '30분 단위여야 합니다 · 너무 깁니다');
  assert.equal(detailsText(undefined), null);
  assert.equal(detailsText([]), null);
  assert.equal(detailsText([{ field: 'x' }]), null);
});
