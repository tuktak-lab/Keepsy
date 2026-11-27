// 공통 셸(app/(shop)/layout.tsx)의 경로 규칙: 사이드바 활성 메뉴. 화면이 각자 넘기던 값을 pathname에서 정한다 (docs ARCHITECTURE 4장 「화면 경로」)

/** 사이드바 메뉴 id → 그 메뉴에 속한 경로 머리 (`/customers`는 `/customers/…`에 맞고 `/customers-old`에는 맞지 않는다) */
const ACTIVE: [string, string[]][] = [
  ['dashboard', ['/dashboard']],
  ['schedule', ['/reservations']],
  ['customers', ['/customers']],
  ['insights', ['/insights']],
  ['campaigns', ['/campaigns', '/virtual-phone']],
  ['performance', ['/analytics']],
  ['settings', ['/settings']],
];

/** 끝의 `/`를 뗀다 (정적 내보내기 설정에 따라 붙을 수 있다) */
const clean = (pathname: string) => (pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname);

/** 사이드바에서 켤 메뉴 id. 모르는 경로는 대시보드 */
export function activeMenu(pathname: string): string {
  const p = clean(pathname);
  const hit = ACTIVE.find(([, heads]) => heads.some((h) => p === h || p.startsWith(h + '/')));
  return hit ? hit[0] : 'dashboard';
}
