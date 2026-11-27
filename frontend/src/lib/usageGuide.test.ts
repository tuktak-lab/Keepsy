import { test } from 'node:test';
import assert from 'node:assert/strict';
import { GUIDE_STEPS, availableSteps, guideSelector } from './usageGuide.ts';
import { ko } from './messages/ko.ts';
import { en } from './messages/en.ts';

test('7개 탭(사이드바 메뉴 id)마다 단계가 있다', () => {
  assert.deepEqual(Object.keys(GUIDE_STEPS).sort(), ['customers', 'dashboard', 'insights', 'performance', 'schedule', 'settings', 'campaigns'].sort());
  for (const steps of Object.values(GUIDE_STEPS)) assert.ok(steps.length >= 3 && steps.length <= 5);
});

test('강조 대상 이름은 겹치지 않고, 문구는 한국어 · 영어 모두 있다', () => {
  const targets = Object.values(GUIDE_STEPS).flat().map((s) => s.target);
  assert.equal(new Set(targets).size, targets.length);
  for (const s of Object.values(GUIDE_STEPS).flat()) {
    assert.ok(ko[s.title] && ko[s.body] && en[s.title] && en[s.body], s.target);
  }
});

test('화면에 없는 대상은 건너뛰고 순서는 그대로', () => {
  const steps = availableSteps('customers', (t) => t !== 'customers-detail' && t !== 'customers-search');
  assert.deepEqual(steps.map((s) => s.target), ['customers-actions', 'customers-list']);
});

test('대상이 하나도 없거나 모르는 탭이면 빈 목록 (오류 없음)', () => {
  assert.deepEqual(availableSteps('settings', () => false), []);
  assert.deepEqual(availableSteps('unknown', () => true), []);
});

test('선택자는 data-guide 속성', () => {
  assert.equal(guideSelector('dashboard-kpis'), '[data-guide="dashboard-kpis"]');
});
