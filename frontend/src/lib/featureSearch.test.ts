// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { isSearchShortcut, matchFeature, type FeatureEntry } from './featureSearch.ts';

const entries = JSON.parse(readFileSync(new URL('../../public/search-keywords.json', import.meta.url), 'utf8')) as FeatureEntry[];

test('키워드 사전은 정적 파일이고 경로는 / 로 시작한다', () => {
  assert.ok(entries.length > 0);
  for (const e of entries) {
    assert.match(e.path, /^\//);
    assert.ok(e.keywords.length > 0);
  }
});

test('입력에 키워드가 들어 있으면 그 기능 경로', () => {
  assert.equal(matchFeature(entries, '지난달 매출'), '/analytics');
  assert.equal(matchFeature(entries, ' 고객 등록 '), '/customers');
});

test('맞는 기능이 없거나 빈 입력이면 null', () => {
  assert.equal(matchFeature(entries, '없는 기능'), null);
  assert.equal(matchFeature(entries, '   '), null);
});

test('단축키: Ctrl+K · ⌘K는 언제나, / 는 입력 중이 아닐 때만', () => {
  const key = (k: string, ctrl = false, meta = false) => ({ key: k, ctrlKey: ctrl, metaKey: meta });
  assert.equal(isSearchShortcut(key('k', true), false), true);
  assert.equal(isSearchShortcut(key('K', false, true), true), true);
  assert.equal(isSearchShortcut(key('/'), false), true);
  assert.equal(isSearchShortcut(key('/'), true), false);
  assert.equal(isSearchShortcut(key('k'), false), false);
});
