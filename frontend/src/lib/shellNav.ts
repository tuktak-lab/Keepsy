// 공통 셸(app/(shop)/layout.tsx)의 경로 규칙: 사이드바 활성 메뉴와 상단 경로 표시. 화면이 각자 넘기던 값을 pathname에서 정한다 (docs ARCHITECTURE 4장 「화면 경로」)

const HOME = '/dashboard';

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

const CRUMB: Record<string, string> = {
  '/dashboard': 'Customer Intelligence / Overview',
  '/reservations': '파트너 센터 / 예약 · 일정',
  '/reservations/detail': '파트너 센터 / 예약 · 일정 / 예약 상세',
  '/reservations/closing': '파트너 센터 / 예약 · 일정 / 시술 마감',
  '/customers': 'Customer Intelligence / 고객 목록',
  '/customers/new': 'Customer Intelligence / 고객 등록',
  '/customers/import': 'Customer Intelligence / 고객 목록 / CSV 일괄 등록',
  '/customers/retention': 'Customer Intelligence / 고객 목록 / 파기 대상',
  '/insights': 'Customer Intelligence / 재방문 관리',
  '/campaigns': '파트너 센터 / 캠페인 관리',
  '/campaigns/new': '파트너 센터 / 캠페인 관리 / 새 캠페인',
  '/campaigns/detail': '파트너 센터 / 캠페인 / 결과',
  '/campaigns/templates': 'Campaign / 템플릿 관리',
  '/campaigns/reconfirm': '파트너 센터 / 캠페인 관리 / 동의 재확인',
  '/virtual-phone': 'Campaign / 가상 휴대폰',
  '/analytics': '파트너 센터 / 성과 분석',
  '/settings': '파트너 센터 / 매장 설정',
};

/** 끝의 `/`를 뗀다 (정적 내보내기 설정에 따라 붙을 수 있다) */
const clean = (pathname: string) => (pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname);

/** 사이드바에서 켤 메뉴 id. 모르는 경로는 대시보드 */
export function activeMenu(pathname: string): string {
  const p = clean(pathname);
  const hit = ACTIVE.find(([, heads]) => heads.some((h) => p === h || p.startsWith(h + '/')));
  return hit ? hit[0] : 'dashboard';
}

/** 상단 경로 표시. 고객 상세는 고객 이름(화면이 알려 준다)이 붙고, 아직 모르면 「고객 상세」 */
export function crumbOf(pathname: string, detailName?: string | null): string {
  const p = clean(pathname);
  if (p === '/customers/detail') return `Customer Intelligence / 고객 목록 / ${detailName || '고객 상세'}`;
  return CRUMB[p] ?? CRUMB[HOME];
}
