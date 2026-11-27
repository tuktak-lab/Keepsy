// 실행: npm test
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { configureApi } from './api.ts';
import { isSessionFailed, lockPin, logout, refreshSession } from './session.ts';

let calls: string[];
let responses: (() => Response)[];
let navigated: string[];
let shown: string[];

const ok = (body?: unknown) => () => new Response(body === undefined ? '' : JSON.stringify(body), { status: 200 });
const fail = (status: number, code: string) => () => new Response(JSON.stringify({ error: { code, message: code } }), { status });
const session = { shopId: 1, shopName: '모의 뷰티샵', currentStaff: { id: 1, name: '스탭1' }, pinStaff: null, pinExpiresAt: null, features: {} };

beforeEach(() => {
  calls = [];
  responses = [];
  navigated = [];
  shown = [];
  (globalThis as { document?: unknown }).document = { cookie: '' };
  globalThis.fetch = (async (url: string, init: RequestInit) => {
    calls.push(`${init.method} ${url}`);
    const next = responses.shift();
    if (!next) throw new TypeError('연결 실패');
    return next();
  }) as typeof fetch;
  configureApi({ navigate: (p) => navigated.push(p), showError: (m) => shown.push(m), promptPin: async () => false });
});

test('세션 읽기가 실패하면 실패 상태가 되고, 다시 읽어 성공하면 풀린다 (#489)', async () => {
  responses = [fail(500, 'internal_error')];
  assert.equal(await refreshSession(), null);
  assert.equal(isSessionFailed(), true);

  responses = []; // 네트워크 오류도 같다
  assert.equal(await refreshSession(), null);
  assert.equal(isSessionFailed(), true);

  responses = [ok(session)];
  assert.deepEqual(await refreshSession(), session);
  assert.equal(isSessionFailed(), false);
});

test('로그아웃이 실패해도 던지지 않고, 응답 전 다시 눌러도 한 번만 보낸다', async () => {
  responses = [fail(500, 'internal_error')];
  await Promise.all([logout(), logout()]);
  assert.deepEqual(calls, ['DELETE /api/session']);
  assert.equal(shown.length, 1);
  assert.deepEqual(navigated, []);

  responses = [ok()];
  await logout();
  assert.deepEqual(navigated, ['/login']);
});

test('잠금이 실패해도 던지지 않고 세션을 다시 읽지 않는다', async () => {
  responses = [fail(500, 'internal_error')];
  await lockPin();
  assert.deepEqual(calls, ['DELETE /api/session/pin']);
});
