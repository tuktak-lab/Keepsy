import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadingCounts, trackLoading } from './loading.ts';
import { api, ApiError } from './api.ts';

const since = (base: { started: number; settled: number }) => {
  const now = loadingCounts();
  return { started: now.started - base.started, settled: now.settled - base.settled };
};

test('성공 · 실패 모두 끝난 것으로 세고, 결과와 오류는 그대로 돌려준다', async () => {
  const base = loadingCounts();
  let resolve!: (v: string) => void;
  const ok = trackLoading(new Promise<string>((r) => (resolve = r)));
  const error = new Error('x');
  const bad = trackLoading(Promise.reject(error));
  assert.equal(since(base).started, 2);
  await assert.rejects(bad, (e) => e === error);
  assert.deepEqual(since(base), { started: 2, settled: 1 });
  resolve('done');
  assert.equal(await ok, 'done');
  assert.deepEqual(since(base), { started: 2, settled: 2 });
});

test('api()는 조회(GET)만 센다. 저장 요청은 세지 않는다', async () => {
  (globalThis as { document?: unknown }).document = { cookie: '' };
  globalThis.fetch = (async (_url: string, init: RequestInit) =>
    init.method === 'GET' ? new Response('{"ok":true}') : new Response(null, { status: 204 })) as typeof fetch;
  const base = loadingCounts();
  assert.deepEqual(await api('/api/staff'), { ok: true });
  await api('/api/session', { method: 'DELETE' });
  assert.deepEqual(since(base), { started: 1, settled: 1 });

  globalThis.fetch = (async () => {
    throw new TypeError('Failed to fetch');
  }) as typeof fetch;
  await assert.rejects(api('/api/staff', { silent: true }), (e) => e instanceof ApiError && e.code === 'network_error');
  assert.deepEqual(since(base), { started: 2, settled: 2 });
});
