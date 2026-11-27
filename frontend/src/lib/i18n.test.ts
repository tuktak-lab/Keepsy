// node --test 로 돌린다: 화면 언어 전환 (FR-I18N-02, F74)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { errorText, intlLocale, isLocale, setLocale, t } from './i18n.ts';
import { formatWon } from './format.ts';
import { ApiError } from './api.ts';

test('t: 현재 언어 문구와 {이름} 치환. 없는 이름은 그대로 둔다', () => {
  setLocale('ko');
  assert.equal(t('format.won', { amount: '58,000' }), '58,000원');
  assert.equal(t('format.won'), '{amount}원');
  setLocale('en');
  assert.equal(t('format.won', { amount: '58,000' }), '₩58,000');
  assert.equal(t('locale.label'), 'Language');
  setLocale('ko');
});

test('errorText: 한국어는 서버 문구 그대로, 다른 언어는 code로 번역하고 모르는 code는 서버 문구', () => {
  setLocale('ko');
  assert.equal(errorText('conflict', '예약일이 오늘보다 늦어요'), '예약일이 오늘보다 늦어요');
  setLocale('en');
  assert.equal(errorText('conflict', '예약일이 오늘보다 늦어요'), 'This cannot be done in the current state.');
  assert.equal(errorText('new_code', '새 오류'), '새 오류');
  // 화면은 ApiError.message를 보여 주므로 만들 때 번역된다
  assert.equal(new ApiError(409, 'conflict', '지금은 안 돼요').message, 'This cannot be done in the current state.');
  setLocale('ko');
  assert.equal(new ApiError(409, 'conflict', '지금은 안 돼요').message, '지금은 안 돼요');
});

test('formatWon · intlLocale: 언어별 금액 표기', () => {
  setLocale('ko');
  assert.equal(intlLocale(), 'ko-KR');
  assert.equal(formatWon(58000), '58,000원');
  setLocale('en');
  assert.equal(formatWon(58000), '₩58,000');
  setLocale('ko');
});

test('isLocale: 지원 언어만', () => {
  assert.ok(isLocale('en'));
  assert.ok(!isLocale('fr'));
  assert.ok(!isLocale(null));
});
