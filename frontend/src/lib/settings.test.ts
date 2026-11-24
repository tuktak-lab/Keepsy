// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DAYS,
  parseInteger,
  settingsToValues,
  toRows,
  toSettingsRequest,
  toShopRequest,
  validateSettings,
  validateShop,
  type ShopValues,
} from './settings.ts';

const rows = toRows(DAYS.map((d, i) => ({ dayOfWeek: d, open: i === 6 ? null : '10:00', close: i === 6 ? null : '19:00' })));
const shop: ShopValues = { name: ' 테스트샵 ', phone: '02-000-0000', senderNumber: '', unsubscribeNumber: '080-000-0000', hours: rows };

test('영업시간 변환: 7줄로 맞추고 정기 휴무(null)는 off', () => {
  assert.equal(rows.length, 7);
  assert.equal(rows[0].off, false);
  assert.deepEqual([rows[0].open, rows[0].close], ['10:00', '19:00']);
  assert.equal(rows[6].off, true);
  // 빠진 요일은 정기 휴무
  const partial = toRows([{ dayOfWeek: 'MONDAY', open: '09:30', close: '18:00' }]);
  assert.equal(partial.length, 7);
  assert.equal(partial[0].open, '09:30');
  assert.equal(partial[1].off, true);
});

test('매장 검증: 매장명 · 전화번호 형식', () => {
  assert.equal(validateShop(shop), null);
  assert.match(validateShop({ ...shop, name: '   ' })!, /매장명을 입력/);
  assert.match(validateShop({ ...shop, name: 'a'.repeat(101) })!, /100자/);
  assert.equal(validateShop({ ...shop, phone: '1588-0000', senderNumber: '010-0000-0000' }), null);
  assert.equal(validateShop({ ...shop, phone: '0212345678' }), null); // 하이픈 없어도 됨
  assert.match(validateShop({ ...shop, phone: '12-345' })!, /매장 연락처/);
  assert.match(validateShop({ ...shop, unsubscribeNumber: 'abc' })!, /수신 거부 번호/);
  assert.equal(validateShop({ ...shop, phone: '', senderNumber: '', unsubscribeNumber: '' }), null); // 비워도 됨
});

test('매장 검증: 영업시간은 30분 단위이고 닫는 시각이 늦어야 한다 (C7)', () => {
  const edit = (i: number, patch: Partial<(typeof rows)[number]>): ShopValues => ({ ...shop, hours: rows.map((r, j) => (j === i ? { ...r, ...patch } : r)) });
  assert.match(validateShop(edit(0, { open: '19:00', close: '10:00' }))!, /월요일은 닫는 시각이/);
  assert.match(validateShop(edit(1, { open: '10:00', close: '10:00' }))!, /화요일은 닫는 시각이/);
  assert.match(validateShop(edit(2, { open: '10:10' }))!, /수요일 영업시간은 30분 단위/);
  assert.match(validateShop(edit(3, { close: '' }))!, /목요일의 여는 시각과 닫는 시각/);
  assert.equal(validateShop(edit(6, { off: false, open: '10:30', close: '17:30' })), null);
  // 정기 휴무인 요일은 시각이 이상해도 보지 않는다
  assert.equal(validateShop(edit(0, { off: true, open: '', close: '' })), null);
});

test('매장 저장 본문: 이름 공백 제거, 빈 번호는 null, 정기 휴무는 시각 null', () => {
  const body = toShopRequest(shop);
  assert.equal(body.name, '테스트샵');
  assert.equal(body.phone, '02-000-0000');
  assert.equal(body.senderNumber, null);
  assert.equal(body.unsubscribeNumber, '080-000-0000');
  assert.equal(body.openingHours.length, 7);
  assert.deepEqual(body.openingHours[0], { dayOfWeek: 'MONDAY', open: '10:00', close: '19:00' });
  assert.deepEqual(body.openingHours[6], { dayOfWeek: 'SUNDAY', open: null, close: null });
});

const settings = settingsToValues({
  churnSignalCapDays: 120,
  attributionDays: 14,
  dailySendLimit: 500,
  resendBlockDays: 7,
  virtualFailurePercent: 0,
  retentionYears: 3,
});

test('설정값: 응답 → 입력 칸 → 요청 본문이 숫자로 돌아온다', () => {
  assert.equal(settings.dailySendLimit, '500');
  assert.deepEqual(toSettingsRequest(settings), {
    churnSignalCapDays: 120,
    attributionDays: 14,
    dailySendLimit: 500,
    resendBlockDays: 7,
    virtualFailurePercent: 0,
    retentionYears: 3,
  });
});

test('설정값 검증: 범위는 서버와 같다 (F40), 경계값은 통과', () => {
  assert.equal(validateSettings(settings), null);
  assert.equal(
    validateSettings({ ...settings, churnSignalCapDays: '30', attributionDays: '60', dailySendLimit: '10000', resendBlockDays: '0', virtualFailurePercent: '100', retentionYears: '5' }),
    null,
  );
  assert.match(validateSettings({ ...settings, churnSignalCapDays: '29' })!, /이탈 신호 기간 상한.*30~365/);
  assert.match(validateSettings({ ...settings, churnSignalCapDays: '366' })!, /30~365/);
  assert.match(validateSettings({ ...settings, attributionDays: '0' })!, /귀속 기간.*1~60/);
  assert.match(validateSettings({ ...settings, dailySendLimit: '10001' })!, /1~10,000/);
  assert.match(validateSettings({ ...settings, resendBlockDays: '61' })!, /0~60/);
  assert.match(validateSettings({ ...settings, virtualFailurePercent: '101' })!, /0~100/);
  assert.match(validateSettings({ ...settings, retentionYears: '6' })!, /1~5/);
  assert.match(validateSettings({ ...settings, retentionYears: '0' })!, /1~5/);
});

test('설정값 검증: 비었거나 숫자가 아니면 막는다', () => {
  assert.match(validateSettings({ ...settings, dailySendLimit: '' })!, /일 발송 한도.*숫자/);
  assert.match(validateSettings({ ...settings, attributionDays: '열흘' })!, /귀속 기간.*숫자/);
  assert.match(validateSettings({ ...settings, resendBlockDays: '1.5' })!, /재발송 제한 기간.*숫자/);
  assert.match(validateSettings({ ...settings, retentionYears: '-1' })!, /보관 기간.*숫자/);
  assert.equal(parseInteger(' 42 '), 42);
  assert.equal(parseInteger('4 2'), null);
});
