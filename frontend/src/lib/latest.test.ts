import { test } from 'node:test';
import assert from 'node:assert/strict';
import { latestOnly } from './latest.ts';

/** 밖에서 끝낼 수 있는 Promise */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

test('먼저 보낸 요청이 늦게 와도 마지막 요청의 응답만 반영한다', async () => {
  const l = latestOnly();
  const got: string[] = [];
  const a = deferred<string>();
  const b = deferred<string>();
  const ra = l.run(a.promise, (v) => got.push(v), () => got.push('error'));
  const rb = l.run(b.promise, (v) => got.push(v), () => got.push('error'));
  b.resolve('고객 B');
  a.resolve('고객 A');
  await Promise.all([ra, rb]);
  assert.deepEqual(got, ['고객 B']);
});

test('지난 요청의 실패도 무시한다', async () => {
  const l = latestOnly();
  const got: string[] = [];
  const a = deferred<string>();
  const ra = l.run(a.promise, (v) => got.push(v), () => got.push('error'));
  const rb = l.run(Promise.resolve('새 값'), (v) => got.push(v), () => got.push('error'));
  a.reject(new Error('늦은 실패'));
  await Promise.all([ra, rb]);
  assert.deepEqual(got, ['새 값']);
});

test('cancel 뒤에 도착한 응답은 버린다 (화면을 떠남)', async () => {
  const l = latestOnly();
  const got: string[] = [];
  const a = deferred<string>();
  const ra = l.run(a.promise, (v) => got.push(v), () => got.push('error'));
  l.cancel();
  a.resolve('늦은 값');
  await ra;
  assert.deepEqual(got, []);
});
