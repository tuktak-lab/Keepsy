import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canCreate, currentTargets, isSeoulNight, parseIds, parsePurpose, resolveCategory, seoulLocalInput, seoulLocalToIso, type TargetsResult } from './campaignNew.ts';

test('parsePurpose: 광고 템플릿 용도 3개(재방문 · 안부 · 재방문 혜택)만 받고 나머지는 무시', () => {
  assert.equal(parsePurpose('REVISIT'), 'REVISIT');
  assert.equal(parsePurpose('CHECK_IN'), 'CHECK_IN');
  assert.equal(parsePurpose('WIN_BACK'), 'WIN_BACK');
  for (const bad of [null, '', 'REQUEST_CONSENT', 'CONSENT_RECONFIRM', 'ETC', 'revisit', '<script>']) assert.equal(parsePurpose(bad), null);
});

test('resolveCategory: 업종을 불러오는 중 · 실패면 그대로 두고 막는다', () => {
  assert.deepEqual(resolveCategory('WAXING', true, undefined), { category: 'WAXING', block: 'loading' });
  assert.deepEqual(resolveCategory('WAXING', true, null), { category: 'WAXING', block: 'failed' });
});

test('resolveCategory: 주소로 받은 꺼 둔 업종은 다른 업종으로 바꾸지 않고 막는다 (F72)', () => {
  assert.deepEqual(resolveCategory('WAXING', true, ['NAIL', 'LASH']), { category: 'WAXING', block: 'disabled' });
  assert.deepEqual(resolveCategory('WAXING', true, ['NAIL', 'WAXING']), { category: 'WAXING', block: null });
});

test('resolveCategory: 직접 들어온 경우만 켜 둔 첫 업종으로 시작', () => {
  assert.deepEqual(resolveCategory('NAIL', false, ['LASH', 'ETC']), { category: 'LASH', block: null });
  assert.deepEqual(resolveCategory('NAIL', false, ['NAIL', 'ETC']), { category: 'NAIL', block: null });
});

test('resolveCategory: 꺼 둔 업종 딥링크에서 켜 둔 업종을 직접 고르면 풀린다', () => {
  // 화면은 고른 값(wanted)을 바꾸고, 대상 조건(category)이 바뀌어 서버가 다시 센다
  assert.deepEqual(resolveCategory('LASH', true, ['NAIL', 'LASH']), { category: 'LASH', block: null });
});

type T = { targetCount: number };
const result = (key: string, targetCount: number | null, error = ''): TargetsResult<T> => ({ key, data: targetCount === null ? null : { targetCount }, error });

test('currentTargets: 지금 조건으로 센 결과만, 조건을 바꾼 직후(대기 중)나 이전 조건의 늦은 응답은 null', () => {
  assert.equal(currentTargets(null, 'b'), null);
  assert.equal(currentTargets(result('a', 5), 'b'), null); // 조건을 b로 바꾼 직후 · a 응답이 늦게 옴
  assert.deepEqual(currentTargets(result('b', 3), 'b'), result('b', 3));
});

test('canCreate: 템플릿 · 업종 사정 · 지금 조건의 대상 1명 이상이 모두 맞아야', () => {
  const ok = { creating: false, hasTemplate: true, block: null, current: result('k', 2) } as const;
  assert.equal(canCreate(ok), true);
  assert.equal(canCreate({ ...ok, current: null }), false); // 대기 · 조회 중 · 이전 결과
  assert.equal(canCreate({ ...ok, current: result('k', 0) }), false);
  assert.equal(canCreate({ ...ok, current: result('k', null, '오류') }), false);
  assert.equal(canCreate({ ...ok, hasTemplate: false }), false);
  assert.equal(canCreate({ ...ok, creating: true }), false);
  for (const block of ['loading', 'failed', 'disabled'] as const) assert.equal(canCreate({ ...ok, block }), false);
});

test('야간은 서울 21:00부터 08:00 전까지', () => {
  assert.equal(isSeoulNight(new Date('2026-11-25T11:59:00Z')), false); // 20:59
  assert.equal(isSeoulNight(new Date('2026-11-25T12:00:00Z')), true); // 21:00
  assert.equal(isSeoulNight(new Date('2026-11-25T22:59:00Z')), true); // 다음 날 07:59
  assert.equal(isSeoulNight(new Date('2026-11-25T23:00:00Z')), false); // 08:00
});

test('예약 입력값은 서울 시각으로 보고 +09:00을 붙인다', () => {
  assert.equal(seoulLocalToIso('2026-11-26T11:00'), '2026-11-26T11:00:00+09:00');
  assert.equal(seoulLocalToIso('2026-11-26 11:00'), null);
  assert.equal(seoulLocalInput(new Date('2026-11-25T15:30:00Z')), '2026-11-26T00:30');
});

test('customerIds 쿼리는 양의 정수만 한 번씩', () => {
  assert.deepEqual(parseIds('3,1,3,x,-2,0'), [3, 1]);
  assert.deepEqual(parseIds(null), []);
});
