// 기능 검색 (FR-SCH-01 · 02). 키워드 · 동의어 사전은 정적 파일 public/search-keywords.json (docs ARCHITECTURE 4장).
// 상단 검색은 기능 화면 이동만 한다. 기간 인식 · 고객 검색 · 자물쇠 표시 · 미매칭 로그 · 초성은 범위 제외 (F74)

export type FeatureEntry = { path: string; keywords: string[] };

export const KEYWORDS_URL = '/search-keywords.json';

/** 비교용: 대소문자와 띄어쓰기를 무시한다 ("고객등록" = "고객 등록", "csv" = "CSV") */
const norm = (s: string) => s.toLowerCase().replace(/\s+/g, '');

/**
 * 입력에 맞는 기능 경로. 없으면 null.
 * ① 키워드와 정확히 같으면 그 기능 ② 아니면 입력에 들어 있는 키워드 중 가장 긴(구체적인) 것의 기능.
 * 길이가 같으면 사전에서 먼저 나온 기능 (예: "고객 등록"은 "고객"보다 길어 고객 등록 화면으로 간다)
 */
export function matchFeature(entries: FeatureEntry[], text: string): string | null {
  const q = norm(text);
  if (!q) return null;
  const exact = entries.find((e) => e.keywords.some((k) => norm(k) === q));
  if (exact) return exact.path;
  let best: { path: string; len: number } | null = null;
  for (const e of entries) {
    for (const k of e.keywords) {
      const n = norm(k);
      if (n && q.includes(n) && (best === null || n.length > best.len)) best = { path: e.path, len: n.length };
    }
  }
  return best?.path ?? null;
}

/** `/` 또는 Ctrl+K(맥은 ⌘K)로 검색창에 포커스. 입력 중인 칸에서 누른 `/`는 글자로 둔다 */
export function isSearchShortcut(e: { key: string; ctrlKey: boolean; metaKey: boolean }, typing: boolean): boolean {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') return true;
  return e.key === '/' && !typing && !e.ctrlKey && !e.metaKey;
}
