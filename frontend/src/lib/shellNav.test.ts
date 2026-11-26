// node --test 로 돌린다: 공통 셸의 경로 규칙
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { activeMenu, crumbOf } from './shellNav.ts';

test('activeMenu: 화면 경로가 속한 사이드바 메뉴', () => {
  assert.equal(activeMenu('/dashboard'), 'dashboard');
  assert.equal(activeMenu('/reservations'), 'schedule');
  assert.equal(activeMenu('/reservations/detail'), 'schedule');
  assert.equal(activeMenu('/reservations/closing'), 'schedule');
  assert.equal(activeMenu('/customers/detail'), 'customers');
  assert.equal(activeMenu('/customers/retention'), 'customers');
  assert.equal(activeMenu('/insights'), 'insights');
  assert.equal(activeMenu('/campaigns/templates'), 'campaigns');
  assert.equal(activeMenu('/virtual-phone'), 'campaigns');
  assert.equal(activeMenu('/analytics'), 'performance');
  assert.equal(activeMenu('/settings'), 'settings');
});

test('activeMenu: 끝의 / 는 무시하고, 모르는 경로는 대시보드, 비슷한 머리말은 속하지 않는다', () => {
  assert.equal(activeMenu('/customers/'), 'customers');
  assert.equal(activeMenu('/nowhere'), 'dashboard');
  assert.equal(activeMenu('/customers-old'), 'dashboard');
});

test('crumbOf: 경로 표 그대로, 고객 상세는 이름이 붙는다', () => {
  assert.equal(crumbOf('/dashboard'), 'Customer Intelligence / Overview');
  assert.equal(crumbOf('/campaigns/reconfirm'), '파트너 센터 / 캠페인 관리 / 동의 재확인');
  assert.equal(crumbOf('/virtual-phone/'), 'Campaign / 가상 휴대폰');
  assert.equal(crumbOf('/customers/detail', '김데모'), 'Customer Intelligence / 고객 목록 / 김데모');
  assert.equal(crumbOf('/customers/detail'), 'Customer Intelligence / 고객 목록 / 고객 상세');
  assert.equal(crumbOf('/customers/detail', ''), 'Customer Intelligence / 고객 목록 / 고객 상세');
});

test('crumbOf: 모르는 경로는 대시보드 표시', () => {
  assert.equal(crumbOf('/nowhere'), 'Customer Intelligence / Overview');
});
