// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeEmail, splitBody } from './mailbox.ts';

test('normalizeEmail: 형식 · 길이 검사, 앞뒤 공백은 다듬는다', () => {
  assert.equal(normalizeEmail(' shop@example.com '), 'shop@example.com');
  for (const bad of ['', 'shop', 'shop@', '@example.com', 'a b@example.com', 'shop@example']) assert.equal(normalizeEmail(bad), null);
  assert.equal(normalizeEmail('a'.repeat(250) + '@b.co') !== null, true); // 정확히 255자는 허용
  assert.equal(normalizeEmail('a'.repeat(251) + '@b.co'), null); // 256자는 거절
});

test('splitBody: 재설정 링크만 링크가 되고 앞뒤 글자는 그대로', () => {
  const body = '아래 링크에서 30분 안에 새 비밀번호를 정해 주세요.\n/password-reset?token=Ab-_09xZ\n요청하지 않았다면 무시해 주세요.';
  assert.deepEqual(splitBody(body), [
    { text: '아래 링크에서 30분 안에 새 비밀번호를 정해 주세요.\n' },
    { text: '/password-reset?token=Ab-_09xZ', href: '/password-reset?token=Ab-_09xZ' },
    { text: '\n요청하지 않았다면 무시해 주세요.' },
  ]);
});

test('splitBody: 다른 주소 · 스크립트 · 토큰 없는 주소는 링크로 만들지 않는다', () => {
  for (const body of ['https://evil.example/x', 'javascript:alert(1)', '/password-reset?token=', '/login?token=abc', '<a href="/x">x</a>']) {
    assert.deepEqual(splitBody(body), [{ text: body }]);
  }
  assert.deepEqual(splitBody(''), []);
});
