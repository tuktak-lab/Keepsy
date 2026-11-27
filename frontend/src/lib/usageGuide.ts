// 탭별 사용 가이드 (#468). 상단바 [사용 가이드]가 지금 탭(activeMenu)의 단계를 Driver.js로 차례로 강조한다.
// 강조 대상은 화면의 `data-guide` 속성으로 찾는다 (CSS 클래스 · 문구가 바뀌어도 가이드가 깨지지 않게).
import type { MessageKey } from './messages/ko.ts';

export type GuideStep = { target: string; title: MessageKey; body: MessageKey };

/** 사이드바 메뉴 id(shellNav.activeMenu) → 그 탭의 안내 단계. 화면에 없는 대상은 실행할 때 건너뛴다 */
export const GUIDE_STEPS: Record<string, GuideStep[]> = {
  dashboard: [
    { target: 'dashboard-kpis', title: 'guide.dashboard.kpis.title', body: 'guide.dashboard.kpis.body' },
    { target: 'dashboard-today', title: 'guide.dashboard.today.title', body: 'guide.dashboard.today.body' },
    { target: 'dashboard-unclosed', title: 'guide.dashboard.unclosed.title', body: 'guide.dashboard.unclosed.body' },
    { target: 'dashboard-care', title: 'guide.dashboard.care.title', body: 'guide.dashboard.care.body' },
  ],
  schedule: [
    { target: 'schedule-new', title: 'guide.schedule.new.title', body: 'guide.schedule.new.body' },
    { target: 'schedule-calendar', title: 'guide.schedule.calendar.title', body: 'guide.schedule.calendar.body' },
    { target: 'schedule-view', title: 'guide.schedule.view.title', body: 'guide.schedule.view.body' },
    { target: 'schedule-grid', title: 'guide.schedule.grid.title', body: 'guide.schedule.grid.body' },
  ],
  customers: [
    { target: 'customers-actions', title: 'guide.customers.actions.title', body: 'guide.customers.actions.body' },
    { target: 'customers-search', title: 'guide.customers.search.title', body: 'guide.customers.search.body' },
    { target: 'customers-list', title: 'guide.customers.list.title', body: 'guide.customers.list.body' },
    { target: 'customers-detail', title: 'guide.customers.detail.title', body: 'guide.customers.detail.body' },
  ],
  insights: [
    { target: 'insights-summary', title: 'guide.insights.summary.title', body: 'guide.insights.summary.body' },
    { target: 'insights-tabs', title: 'guide.insights.tabs.title', body: 'guide.insights.tabs.body' },
    { target: 'insights-action', title: 'guide.insights.action.title', body: 'guide.insights.action.body' },
    { target: 'insights-handling', title: 'guide.insights.handling.title', body: 'guide.insights.handling.body' },
  ],
  campaigns: [
    { target: 'campaigns-actions', title: 'guide.campaigns.actions.title', body: 'guide.campaigns.actions.body' },
    { target: 'campaigns-kpis', title: 'guide.campaigns.kpis.title', body: 'guide.campaigns.kpis.body' },
    { target: 'campaigns-list', title: 'guide.campaigns.list.title', body: 'guide.campaigns.list.body' },
    { target: 'campaigns-detail', title: 'guide.campaigns.detail.title', body: 'guide.campaigns.detail.body' },
  ],
  performance: [
    { target: 'analytics-period', title: 'guide.analytics.period.title', body: 'guide.analytics.period.body' },
    { target: 'analytics-kpis', title: 'guide.analytics.kpis.title', body: 'guide.analytics.kpis.body' },
    { target: 'analytics-revisit', title: 'guide.analytics.revisit.title', body: 'guide.analytics.revisit.body' },
    { target: 'analytics-campaigns', title: 'guide.analytics.campaigns.title', body: 'guide.analytics.campaigns.body' },
  ],
  settings: [
    { target: 'settings-nav', title: 'guide.settings.nav.title', body: 'guide.settings.nav.body' },
    { target: 'settings-shop', title: 'guide.settings.shop.title', body: 'guide.settings.shop.body' },
    { target: 'settings-menu', title: 'guide.settings.menu.title', body: 'guide.settings.menu.body' },
    { target: 'settings-staff', title: 'guide.settings.staff.title', body: 'guide.settings.staff.body' },
    { target: 'settings-lock', title: 'guide.settings.lock.title', body: 'guide.settings.lock.body' },
  ],
};

/** 그 탭의 단계 중 화면에 대상이 있는 것만, 정한 순서대로 (권한 · 빈 데이터로 없는 대상은 건너뜀) */
export function availableSteps(menu: string, exists: (target: string) => boolean): GuideStep[] {
  return (GUIDE_STEPS[menu] ?? []).filter((s) => exists(s.target));
}

/** `[data-guide="…"]` 선택자 */
export const guideSelector = (target: string) => `[data-guide="${target}"]`;
