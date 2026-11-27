// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { isSearchShortcut, matchFeature, type FeatureEntry } from './featureSearch.ts';

const entries = JSON.parse(readFileSync(new URL('../../public/search-keywords.json', import.meta.url), 'utf8')) as FeatureEntry[];
const app = new URL('../app/', import.meta.url);
const settingsDir = new URL('../features/settings/', import.meta.url);
const settingsSource = readdirSync(settingsDir)
  .filter((f) => f.endsWith('.tsx'))
  .map((f) => readFileSync(new URL(f, settingsDir), 'utf8'))
  .join('\n');

test('키워드 사전은 정적 파일이고 경로는 / 로 시작한다', () => {
  assert.ok(entries.length > 0);
  for (const e of entries) {
    assert.match(e.path, /^\//);
    assert.ok(e.keywords.length > 0);
  }
});

test('사전의 경로는 실제 화면이고, # 앵커는 매장 설정에 있는 구역이다', () => {
  for (const { path } of entries) {
    const [route, anchor] = path.split('#');
    const page = (base: string) => existsSync(new URL(`${base}${route.slice(1)}/page.tsx`.replace('//', '/'), app));
    assert.ok(page('(shop)/') || page(''), `화면 없음: ${path}`);
    if (anchor) assert.ok(settingsSource.includes(`id="${anchor}"`), `구역 없음: ${path}`);
  }
});

test('같은 키워드가 두 기능에 겹치지 않는다 (띄어쓰기 · 대소문자 무시)', () => {
  const seen = new Map<string, string>();
  for (const e of entries) {
    for (const k of e.keywords) {
      const n = k.toLowerCase().replace(/\s+/g, '');
      assert.ok(!seen.has(n) || seen.get(n) === e.path, `「${k}」가 ${seen.get(n)}과 ${e.path}에 겹침`);
      seen.set(n, e.path);
    }
  }
});

test('입력에 키워드가 들어 있으면 그 기능 경로', () => {
  assert.equal(matchFeature(entries, '지난달 매출'), '/analytics');
  assert.equal(matchFeature(entries, '사진'), '/customers'); // 히스토리 · 사진은 고객 상세에 있다 (없는 /history 경로였음)
});

test('정확히 같은 키워드가 부분 일치보다 먼저다', () => {
  assert.equal(matchFeature(entries, '고객'), '/customers');
  assert.equal(matchFeature(entries, '예약'), '/reservations');
  assert.equal(matchFeature(entries, '캠페인'), '/campaigns');
});

test('부분 일치가 여럿이면 더 구체적인(긴) 키워드의 기능으로 간다', () => {
  assert.equal(matchFeature(entries, ' 고객 등록 '), '/customers/new'); // "고객"보다 "고객 등록"
  assert.equal(matchFeature(entries, '손님 등록'), '/customers/new');
  assert.equal(matchFeature(entries, '고객 추가하기'), '/customers/new');
  assert.equal(matchFeature(entries, '오늘 예약 보기'), '/dashboard'); // "예약"보다 "오늘 예약"
  assert.equal(matchFeature(entries, '동의 재확인 안내'), '/campaigns/reconfirm'); // "동의"보다 "동의 재확인"
  assert.equal(matchFeature(entries, '보관 기간 지난 고객'), '/customers/retention'); // 설정의 "보관 기간"보다 구체적
  assert.equal(matchFeature(entries, '보관 기간'), '/settings#st-privacy');
  assert.equal(matchFeature(entries, '새 캠페인'), '/campaigns/new');
  assert.equal(matchFeature(entries, '시술 메뉴 수정'), '/settings#st-menu');
});

test('모호한 입력이 엉뚱한 화면으로 가지 않는다', () => {
  assert.equal(matchFeature(entries, '오늘 매출'), '/analytics'); // "오늘"만으로는 대시보드로 가지 않는다
  assert.equal(matchFeature(entries, '이번 주'), null); // 기간 인식은 범위 제외 (F74)
  assert.equal(matchFeature(entries, '김민지'), null); // 고객 이름 검색은 고객 목록에서 (F74)
});

test('띄어쓰기 · 대소문자는 무시한다', () => {
  assert.equal(matchFeature(entries, '고객등록'), '/customers/new');
  assert.equal(matchFeature(entries, 'csv'), '/customers/import');
  assert.equal(matchFeature(entries, 'pin'), '/settings');
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
