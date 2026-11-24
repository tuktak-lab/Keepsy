// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CATEGORIES,
  changesPrediction,
  emptyMenuValues,
  formatDuration,
  toCreateRequest,
  toCycleValues,
  toCyclesRequest,
  toMenuValues,
  toPatchRequest,
  validateCycles,
  validateMenu,
  type Menu,
} from './menus.ts';

const menu: Menu = {
  id: 7,
  category: 'NAIL',
  name: '컬러 젤 네일',
  price: 58000,
  durationMinutes: 90,
  cycleMinDays: 21,
  cycleMaxDays: 35,
  predictionAttribute: 'NORMAL',
};

test('소요 시간 표시: 30분 · 1시간 · 1시간 30분', () => {
  assert.equal(formatDuration(30), '30분');
  assert.equal(formatDuration(60), '1시간');
  assert.equal(formatDuration(90), '1시간 30분');
  assert.equal(formatDuration(120), '2시간');
});

test('새 메뉴 기본값은 카테고리 기본 주기에서 시작한다', () => {
  const v = emptyMenuValues({ minDays: 21, maxDays: 28 });
  assert.deepEqual([v.cycleMinDays, v.cycleMaxDays, v.predictionAttribute, v.durationMinutes], ['21', '28', 'NORMAL', 60]);
  assert.equal(emptyMenuValues(undefined).cycleMinDays, '28'); // 모르면 무난한 값
});

test('검증: 시술명 · 가격 · 소요 시간 · 기본 주기', () => {
  const ok = toMenuValues(menu);
  assert.equal(validateMenu(ok), null);
  assert.match(validateMenu({ ...ok, name: '  ' })!, /시술명을 입력/);
  assert.match(validateMenu({ ...ok, name: 'a'.repeat(101) })!, /100자/);
  assert.match(validateMenu({ ...ok, price: '' })!, /가격/);
  assert.match(validateMenu({ ...ok, price: '-5' })!, /가격/);
  assert.equal(validateMenu({ ...ok, price: '0' }), null); // 무료 보수 같은 메뉴
  assert.match(validateMenu({ ...ok, durationMinutes: 45 })!, /30분 단위/);
  assert.match(validateMenu({ ...ok, cycleMinDays: 'x' })!, /숫자/);
  assert.match(validateMenu({ ...ok, cycleMinDays: '0' })!, /1일 이상/);
  assert.match(validateMenu({ ...ok, cycleMinDays: '40', cycleMaxDays: '30' })!, /최소는 최대보다/);
  assert.equal(validateMenu({ ...ok, cycleMinDays: '30', cycleMaxDays: '30' }), null); // 같아도 됨
});

test('추가 요청 본문: 카테고리를 보내고 이름은 공백을 뺀다', () => {
  const body = toCreateRequest('LASH', { ...toMenuValues(menu), name: ' 연장 풀세트 ', predictionAttribute: 'END_SIGNAL' });
  assert.equal(body.category, 'LASH');
  assert.equal(body.name, '연장 풀세트');
  assert.equal(body.price, 58000);
  assert.equal(body.predictionAttribute, 'END_SIGNAL');
});

test('수정 요청 본문: 실제로 바뀐 칸만, 카테고리는 절대 보내지 않는다 (F31)', () => {
  const same = toMenuValues(menu);
  assert.deepEqual(toPatchRequest(menu, same), {});
  assert.deepEqual(toPatchRequest(menu, { ...same, price: '60000' }), { price: 60000 });
  assert.deepEqual(toPatchRequest(menu, { ...same, name: ' 컬러 젤 ', durationMinutes: 120 }), { name: '컬러 젤', durationMinutes: 120 });
  const full = toPatchRequest(menu, { ...same, cycleMinDays: '14', cycleMaxDays: '28', predictionAttribute: 'EXCLUDE' });
  assert.deepEqual(full, { cycleMinDays: 14, cycleMaxDays: 28, predictionAttribute: 'EXCLUDE' });
  assert.equal('category' in toPatchRequest(menu, { ...same, price: '1' }), false);
});

test('예측 재계산 안내: 주기나 예측 속성이 바뀔 때만 (이름 · 가격 · 소요 시간은 아님)', () => {
  assert.equal(changesPrediction({ price: 1, name: 'a', durationMinutes: 60 }), false);
  assert.equal(changesPrediction({ cycleMaxDays: 40 }), true);
  assert.equal(changesPrediction({ cycleMinDays: 10 }), true);
  assert.equal(changesPrediction({ predictionAttribute: 'EXCLUDE' }), true);
  assert.equal(changesPrediction({}), false);
});

test('카테고리 기본 주기: 5개를 3절 순서로, 빠진 카테고리는 빈 칸', () => {
  const values = toCycleValues([
    { category: 'LASH', minDays: 30, maxDays: 60 },
    { category: 'NAIL', minDays: 21, maxDays: 28 },
  ]);
  assert.deepEqual(Object.keys(values), [...CATEGORIES]);
  assert.deepEqual(values.NAIL, { minDays: '21', maxDays: '28' });
  assert.deepEqual(values.ETC, { minDays: '', maxDays: '' });
});

test('카테고리 기본 주기 검증과 요청 본문', () => {
  const full = toCycleValues(CATEGORIES.map((c, i) => ({ category: c, minDays: 20 + i, maxDays: 40 + i })));
  assert.equal(validateCycles(full), null);
  assert.match(validateCycles({ ...full, WAXING: { minDays: '', maxDays: '40' } })!, /왁싱.*숫자/);
  assert.match(validateCycles({ ...full, NAIL: { minDays: '0', maxDays: '5' } })!, /네일.*1일 이상/);
  assert.match(validateCycles({ ...full, ETC: { minDays: '60', maxDays: '30' } })!, /기타.*최소는 최대보다/);
  const body = toCyclesRequest(full);
  assert.equal(body.length, 5);
  assert.deepEqual(body[0], { category: 'NAIL', minDays: 20, maxDays: 40 });
  assert.deepEqual(body.map((b) => b.category), [...CATEGORIES]);
});
