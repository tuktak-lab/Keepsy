// 기능 검색 (FR-SCH-01 · 02). 키워드 · 동의어 사전은 정적 파일 public/search-keywords.json (docs ARCHITECTURE 4장).

export type FeatureEntry = { path: string; keywords: string[] };

export const KEYWORDS_URL = '/search-keywords.json';

/** 입력에 키워드가 들어 있는 첫 기능의 경로. 없으면 null */
export function matchFeature(entries: FeatureEntry[], text: string): string | null {
  const q = text.trim();
  if (!q) return null;
  return entries.find((e) => e.keywords.some((k) => q.includes(k)))?.path ?? null;
}

/** `/` 또는 Ctrl+K(맥은 ⌘K)로 검색창에 포커스. 입력 중인 칸에서 누른 `/`는 글자로 둔다 */
export function isSearchShortcut(e: { key: string; ctrlKey: boolean; metaKey: boolean }, typing: boolean): boolean {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') return true;
  return e.key === '/' && !typing && !e.ctrlKey && !e.metaKey;
}
