// node --test 로 돌린다: 공통 셸의 경로 규칙
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { activeMenu } from './shellNav.ts';

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
