// 실행: npm test (Node 내장 테스트 러너, Node 24가 .ts를 바로 실행)
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { api, ApiError, configureApi, CSRF_HEADER, type PinPrompt } from './api.ts';
import { formatDateTime } from './format.ts';

type Call = { url: string; init: RequestInit };
let calls: Call[];
let responses: Response[];
let navigated: string[];
let shown: string[];
let prompts: PinPrompt[];
let pinResult: boolean;

function errorResponse(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({ error: { code, message } }), { status });
}

function headersOf(call: Call): Record<string, string> {
  return call.init.headers as Record<string, string>;
}

beforeEach(() => {
  calls = [];
  responses = [];
  navigated = [];
  shown = [];
  prompts = [];
  pinResult = true;
  (globalThis as { document?: unknown }).document = { cookie: 'other=1; XSRF-TOKEN=tok%3D1' };
  globalThis.fetch = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    const next = responses.shift();
    if (!next) throw new Error('응답이 준비되지 않음');
    return next;
  }) as typeof fetch;
  configureApi({
    navigate: (path) => navigated.push(path),
    showError: (message) => shown.push(message),
    promptPin: async (prompt) => {
      prompts.push(prompt);
      return pinResult;
    },
  });
});

test('성공 응답은 JSON으로 돌려주고, 빈 응답은 undefined', async () => {
  responses.push(new Response(JSON.stringify({ items: [], page: 0, size: 20, totalElements: 0 })));
  assert.deepEqual(await api('/api/customers?page=0&size=20'), { items: [], page: 0, size: 20, totalElements: 0 });
  responses.push(new Response(null, { status: 204 }));
  assert.equal(await api('/api/session', { method: 'DELETE' }), undefined);
});

test('같은 출처 /api 호출, 세션 쿠키 포함', async () => {
  responses.push(new Response('{}'));
  await api('/api/session');
  assert.equal(calls[0].url, '/api/session');
  assert.equal(calls[0].init.credentials, 'same-origin');
});

test('GET에는 CSRF 헤더를 붙이지 않는다', async () => {
  responses.push(new Response('{}'));
  await api('/api/session');
  assert.equal(headersOf(calls[0])[CSRF_HEADER], undefined);
});

for (const method of ['POST', 'PUT', 'PATCH', 'DELETE'] as const) {
  test(`${method}에는 쿠키의 CSRF 토큰을 헤더로 붙인다`, async () => {
    responses.push(new Response('{}'));
    await api('/api/customers', { method, body: { name: '테스트' } });
    assert.equal(headersOf(calls[0])[CSRF_HEADER], 'tok=1');
    assert.equal(headersOf(calls[0])['Content-Type'], 'application/json');
    assert.equal(calls[0].init.body, '{"name":"테스트"}');
  });
}

test('FormData 본문은 그대로 보내고 Content-Type을 정하지 않는다', async () => {
  responses.push(new Response('{}'));
  const form = new FormData();
  await api('/api/customers/import', { method: 'POST', body: form });
  assert.equal(calls[0].init.body, form);
  assert.equal(headersOf(calls[0])['Content-Type'], undefined);
});

test('unauthorized → /login 이동 후 ApiError', async () => {
  responses.push(errorResponse(401, 'unauthorized', '로그인이 필요합니다'));
  await assert.rejects(api('/api/customers'), (e: ApiError) => e.code === 'unauthorized' && e.status === 401);
  assert.deepEqual(navigated, ['/login']);
  assert.deepEqual(shown, []);
});

test('worker_required → /shift 이동 후 ApiError', async () => {
  responses.push(errorResponse(403, 'worker_required', '근무자를 선택해 주세요'));
  await assert.rejects(api('/api/customers'), (e: ApiError) => e.code === 'worker_required');
  assert.deepEqual(navigated, ['/shift']);
});

test('pin_required → PIN 입력 창, 인증되면 같은 요청을 다시 보낸다', async () => {
  responses.push(errorResponse(403, 'pin_required', 'PIN을 입력해 주세요'), new Response('{"ok":true}'));
  assert.deepEqual(await api('/api/campaigns', { method: 'POST', body: { a: 1 } }), { ok: true });
  assert.deepEqual(prompts, [{ owner: false, message: 'PIN을 입력해 주세요' }]);
  assert.equal(calls.length, 2);
  assert.equal(calls[1].url, '/api/campaigns');
  assert.equal(calls[1].init.method, 'POST');
  assert.equal(calls[1].init.body, '{"a":1}');
});

test('pin_required에서 PIN을 취소하면 다시 보내지 않고 ApiError', async () => {
  pinResult = false;
  responses.push(errorResponse(403, 'pin_required', 'PIN을 입력해 주세요'));
  await assert.rejects(api('/api/campaigns'), (e: ApiError) => e.code === 'pin_required');
  assert.equal(calls.length, 1);
  assert.deepEqual(shown, []);
});

test('owner_pin_required → 원장 PIN 입력 창에 message를 함께 넘긴다', async () => {
  responses.push(errorResponse(403, 'owner_pin_required', '예약 제한 고객입니다'), new Response('{}'));
  await api('/api/reservations', { method: 'POST', body: {} });
  assert.deepEqual(prompts, [{ owner: true, message: '예약 제한 고객입니다' }]);
  assert.equal(calls.length, 2);
});

test('동시에 여러 요청이 PIN을 요구해도 PIN 입력 창은 한 번만 띄운다', async () => {
  responses.push(
    errorResponse(403, 'pin_required', 'PIN'),
    errorResponse(403, 'pin_required', 'PIN'),
    new Response('{"n":1}'),
    new Response('{"n":2}'),
  );
  const results = await Promise.all([api('/api/a'), api('/api/b')]);
  assert.equal(prompts.length, 1);
  assert.deepEqual(results, [{ n: 1 }, { n: 2 }]);
});

test('그 밖의 code → message를 그대로 보여 주고 ApiError (details 포함)', async () => {
  responses.push(
    new Response(JSON.stringify({ error: { code: 'conflict', message: '남은 예약이 있습니다', details: [{ id: 1 }] } }), {
      status: 409,
    }),
  );
  await assert.rejects(api('/api/staff/1/deactivate', { method: 'POST' }), (e: ApiError) => {
    assert.equal(e.code, 'conflict');
    assert.equal(e.status, 409);
    assert.deepEqual(e.details, [{ id: 1 }]);
    return true;
  });
  assert.deepEqual(shown, ['남은 예약이 있습니다']);
  assert.deepEqual(navigated, []);
});

test('silent면 그 밖의 code를 보여 주지 않고 ApiError만 던진다 (권한 code 처리는 그대로)', async () => {
  responses.push(errorResponse(400, 'pin_invalid', 'PIN이 맞지 않습니다'));
  await assert.rejects(api('/api/session/pin', { method: 'POST', body: { pin: '0000' }, silent: true }), (e: ApiError) => e.code === 'pin_invalid');
  assert.deepEqual(shown, []);

  responses.push(errorResponse(403, 'worker_required', '근무자를 선택해 주세요'));
  await assert.rejects(api('/api/session/pin', { method: 'POST', body: { pin: '0000' }, silent: true }));
  assert.deepEqual(navigated, ['/shift']);
});

test('오류 모양이 아닌 응답은 internal_error로 보고 기본 문구를 보여 준다', async () => {
  responses.push(new Response('Bad Gateway', { status: 502 }));
  await assert.rejects(api('/api/customers'), (e: ApiError) => e.code === 'internal_error' && e.status === 502);
  assert.deepEqual(shown, ['잠시 후 다시 시도해 주세요']);
});

test('시각은 Asia/Seoul로 표시한다', () => {
  assert.equal(formatDateTime('2026-11-23T05:30:00Z'), '2026-11-23 14:30');
  assert.equal(formatDateTime('2026-11-23T23:59:00+09:00'), '2026-11-23 23:59');
  assert.equal(formatDateTime('2026-11-23T15:10:00Z'), '2026-11-24 00:10');
});
