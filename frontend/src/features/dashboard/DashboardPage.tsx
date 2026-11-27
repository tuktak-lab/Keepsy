import { useEffect, useState, type ReactNode } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { openPinDialog } from '@/components/common/PinDialog';
import styleText from './DashboardPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { CountUp } from '@/components/common/CountUp';
import { api, type Page } from '@/lib/api';
import { PERIODS, type Period } from '@/lib/analytics';
import { CHANNEL_LABEL, dowLabel, hmOf, type Channel } from '@/lib/calendar';
import { rateText } from '@/lib/campaignResult';
import { bookedCount, isActive, ratioText } from '@/lib/dashboard';
import { nextActionLabel, STATUS_TABS } from '@/lib/insights';
import { CATEGORY_LABEL, type Category } from '@/lib/menus';
import { useSession } from '@/lib/session';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';

// CRM 대시보드 (FR-DSH-01~06). 대시보드 자체는 DASHBOARD_SEARCH(공용 고정)이고, 아래 두 영역만 기능 키가 다르다 (명세서 2장):
// - 오늘 관리할 고객 · 재방문 상태 · 주기 도래 숫자: INSIGHT / 최근 캠페인 성과 · 재예약 전환율: CAMPAIGN_VIEW
//   PIN으로 잠겨 있으면 화면을 열자마자 PIN 창을 띄우지 않고 영역을 가려 두었다가, [PIN 입력] 뒤에 불러온다 (FR-DSH-02 · 04)
// API (docs CONTRACTS 4장): GET /api/dashboard/today · care-targets · recent-campaigns · booking-channels, GET /api/insights(상태별 인원), GET /api/customers(전체 인원)

type Resv = {
  id: number;
  status: string;
  startAt: string;
  staff: { id: number; name: string };
  consultFirst: boolean;
  channel: Channel;
  customer: { id: number; name: string | null; hasCaution: boolean } | null;
  services: { serviceMenuId: number; name: string }[];
};
type Today = { today: Resv[]; unclosed: Resv[]; week: { date: string; counts: { CONFIRMED: number; COMPLETED: number; CANCELED: number; NO_SHOW: number } }[] };
type Care = { customerId: number; name: string; category: Category; status: string; elapsedDays: number; marketingConsent: boolean; nextAction: string | null };
type Recent = { id: number; name: string; category: Category; sentAt: string; reachedCount: number; rebookedCount: number };
type Channels = { from: string; to: string; total: number; channels: { channel: Channel; count: number; ratio: number }[] };

const SEG_COLOR: Record<string, string> = { SCHEDULED: '#F0DAD6', DUE: '#7A4450', CHURN_SIGNAL: '#CC9A9E', LONG_ABSENT: '#C9BDB9' };
/** 오늘 예약은 처음에 이만큼만 보이고 [모두 보기]로 펼친다 */
const TODAY_FOLD = 5;

const SEGS: { status: string; label: MessageKey; rule: MessageKey }[] = [
  { status: 'SCHEDULED', label: 'dashboard.seg.scheduled', rule: 'dashboard.seg.ruleScheduled' },
  { status: 'DUE', label: 'prediction.status.due', rule: 'dashboard.seg.ruleDue' },
  { status: 'CHURN_SIGNAL', label: 'prediction.status.churn', rule: 'dashboard.seg.ruleChurn' },
  { status: 'LONG_ABSENT', label: 'prediction.status.long', rule: 'dashboard.seg.ruleLong' },
];
const md = (iso: string) => `${Number(iso.slice(5, 7))}.${iso.slice(8, 10)}`;
const resvName = (r: Resv) => r.customer?.name ?? t('dashboard.noCustomerResv', { channel: CHANNEL_LABEL[r.channel] ?? r.channel });

/** PIN으로 잠긴 영역: 가려 두고 버튼으로 PIN을 받는다 (인증되면 세션이 바뀌어 다시 불러온다) */
function Locked(props: { what: string }) {
  return (
    <div style={{ padding: '18px 0', display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'flex-start' }}>
      <p className="kp-card-sub" style={{ margin: 0 }}>{t('dashboard.locked.body', { what: props.what })}</p>
      <button type="button" className="kp-btn p" onClick={() => void openPinDialog({ owner: false, message: '' })}>
        {t('dashboard.locked.button')}
      </button>
    </div>
  );
}

function Card(props: { title: string; sub?: string; head?: ReactNode; className?: string; guide?: string; children: ReactNode }) {
  return (
    <section className={'kp-card ' + (props.className ?? 'kp-care')} aria-label={props.title}>
      <div className="kp-card-head" data-guide={props.guide}>
        <div>
          <h2 className="kp-card-title">{props.title}</h2>
          {props.sub ? <p className="kp-card-sub" style={{ marginLeft: 0 }}>{props.sub}</p> : null}
        </div>
        {props.head}
      </div>
      {props.children}
    </section>
  );
}

export default function DashboardPage() {
  useStyleSheet('DashboardPage', styleText);
  const session = useSession();
  const open = (key: string) => !!session && (session.features[key] === 'PUBLIC' || session.pinStaff !== null);
  const insightOpen = open('INSIGHT');
  const campaignOpen = open('CAMPAIGN_VIEW');
  const customerOpen = open('CUSTOMER_VIEW');

  const [today, setToday] = useState<Today | null>(null);
  const [customers, setCustomers] = useState<number | null>(null);
  const [care, setCare] = useState<Care[] | null>(null);
  const [segCounts, setSegCounts] = useState<Record<string, number> | null>(null);
  const [recent, setRecent] = useState<Recent[] | null>(null);
  const [period, setPeriod] = useState<Period>('THIS_MONTH');
  const [todayOpen, setTodayOpen] = useState(false);
  const [channels, setChannels] = useState<{ period: Period; value: Channels } | null>(null);
  const [drawn, setDrawn] = useState(false);
  // 카드별 불러오기 실패 (#346). 실패를 삼키지 않고 그 카드에 「불러오지 못함」을 보인다
  const [failed, setFailed] = useState<Record<string, boolean>>({});
  const markFailed = (key: string) => setFailed((f) => ({ ...f, [key]: true }));
  const loadingText = (key: string) => (failed[key] ? t('dashboard.failed') : t('common.loading'));

  // 첫 진입 시 도넛이 0에서 채워지도록 다음 프레임에 켭니다
  useEffect(() => {
    const r = requestAnimationFrame(() => setDrawn(true));
    return () => cancelAnimationFrame(r);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api<Today>('/api/dashboard/today', { silent: true }).then((t) => !cancelled && setToday(t), () => !cancelled && markFailed('today'));
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!customerOpen) return;
    let cancelled = false;
    api<Page<unknown>>('/api/customers?page=0&size=1', { silent: true }).then((p) => !cancelled && setCustomers(p.totalElements), () => !cancelled && markFailed('customers'));
    return () => {
      cancelled = true;
    };
  }, [customerOpen]);

  useEffect(() => {
    if (!insightOpen) return;
    let cancelled = false;
    api<Care[]>('/api/dashboard/care-targets', { silent: true }).then((c) => !cancelled && setCare(c), () => !cancelled && markFailed('care'));
    Promise.all(SEGS.map((s) => api<Page<unknown>>(`/api/insights?status=${s.status}&page=0&size=1`, { silent: true }).then((p) => [s.status, p.totalElements] as const)))
      .then((pairs) => !cancelled && setSegCounts(Object.fromEntries(pairs)))
      .catch(() => !cancelled && markFailed('segments'));
    return () => {
      cancelled = true;
    };
  }, [insightOpen]);

  useEffect(() => {
    if (!campaignOpen) return;
    let cancelled = false;
    api<Recent[]>('/api/dashboard/recent-campaigns', { silent: true }).then((r) => !cancelled && setRecent(r), () => !cancelled && markFailed('recent'));
    return () => {
      cancelled = true;
    };
  }, [campaignOpen]);

  useEffect(() => {
    let cancelled = false;
    api<Channels>(`/api/dashboard/booking-channels?period=${period}`, { silent: true }).then((value) => !cancelled && setChannels({ period, value }), () => !cancelled && markFailed(`channels-${period}`));
    return () => {
      cancelled = true;
    };
  }, [period]);

  const todayList = today?.today.filter((r) => isActive(r.status)) ?? null;
  const ch = channels?.period === period ? channels.value : null;
  const weekMax = today ? Math.max(1, ...today.week.map((d) => bookedCount(d.counts))) : 1;
  const latest = recent?.[0];

  // 도넛 (관리 상태별 고객 × 시술 행)
  const segTotal = segCounts ? SEGS.reduce((a, s) => a + segCounts[s.status], 0) : 0;
  const R = 98;
  const C = 2 * Math.PI * R;
  const lens = SEGS.map((s) => (segTotal ? ((segCounts?.[s.status] ?? 0) / segTotal) * C : 0));
  const arcs = SEGS.map((s, i) => {
    const before = lens.slice(0, i).reduce((a, b) => a + b, 0);
    return { color: SEG_COLOR[s.status], dash: `${Math.max(lens[i] - 3, 0)} ${C - lens[i] + 3}`, off: -before };
  });

  const kpi = (href: string, head: string, value: ReactNode, note: ReactNode) => (
    <AppLink href={href} className="kp-kpi" key={head}>
      <div className="kp-kpi-head">{head}</div>
      <div className="kp-kpi-val num">{value}</div>
      <div className="kp-kpi-note">{note}</div>
    </AppLink>
  );
  const num = (n: number | null | undefined) => (n === null || n === undefined ? '-' : <CountUp value={n} />);

  return (
    <>
      <div className="kp-wrap">
        <div className="kp-hero">
          <div>
            <p className="kp-eyebrow">KEEPSY · SMART BEAUTY CRM</p>
            <h1 className="kp-hello">
              {t('dashboard.hero.line1')}
              <br />
              <em>{t('dashboard.hero.line2')}</em>
            </h1>
            <p className="kp-lead">{t('dashboard.hero.lead')}</p>
          </div>
          <AppLink href="/insights" className="kp-cta">
            {t('dashboard.hero.cta')}
          </AppLink>
        </div>

        <section className="kp-kpis kp-card" aria-label={t('dashboard.kpi.label')} data-guide="dashboard-kpis">
          {kpi('/customers', t('dashboard.kpi.customers'), num(customers), !customerOpen ? t('dashboard.kpi.pinNote') : failed.customers ? t('dashboard.kpi.failedNote') : t('dashboard.kpi.customersNote'))}
          {kpi('/reservations', t('dashboard.kpi.today'), num(todayList?.length), todayList ? t('dashboard.kpi.todayNote', { confirmed: todayList.filter((r) => r.status === 'CONFIRMED').length, consult: todayList.filter((r) => r.consultFirst).length }) : failed.today ? t('dashboard.kpi.failedNote') : '')}
          {kpi('/reservations', t('dashboard.kpi.unclosed'), num(today?.unclosed.length), failed.today ? t('dashboard.kpi.failedNote') : t('dashboard.kpi.unclosedNote'))}
          {kpi('/insights', t('dashboard.kpi.due'), num(insightOpen ? segCounts?.DUE : null), !insightOpen ? t('dashboard.kpi.pinNote') : failed.segments ? t('dashboard.kpi.failedNote') : t('dashboard.kpi.dueNote'))}
          {kpi(
            latest ? `/campaigns/detail?id=${latest.id}` : '/analytics',
            t('dashboard.kpi.rebook'),
            latest ? (rateText(latest.rebookedCount, latest.reachedCount) ?? '-') : '-',
            !campaignOpen ? t('dashboard.kpi.pinNote') : failed.recent ? t('dashboard.kpi.failedNote') : latest ? t('dashboard.kpi.rebookNote', { name: latest.name }) : t('dashboard.kpi.noCampaign'),
          )}
        </section>

        {/* 두 열을 각각 세로로 쌓는다. 맨 위에 그래프(이번 주 예약 | 고객 재방문 상태), 그 아래 오늘 할 일.
            열마다 카드 높이가 내용만큼이라 짧은 카드가 옆 카드 높이로 늘어나지 않는다 */}
        <div className="kp-cols">
          <div className="kp-col">
            <Card title={t('dashboard.week.title')} sub={t('dashboard.week.sub')}>
              {!today ? (
                <p className="kp-card-sub">{loadingText('today')}</p>
              ) : (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 10, alignItems: 'end', height: 150, marginTop: 14 }}>
                  {today.week.map((d, i) => {
                    const n = bookedCount(d.counts);
                    return (
                      <div key={d.date} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, height: '100%', justifyContent: 'flex-end' }}>
                        <b className="num" style={{ fontSize: 14 }}>{n}</b>
                        <span style={{ width: '60%', height: `${(n / weekMax) * 90}px`, minHeight: 3, borderRadius: 6, background: '#7A4450' }} aria-hidden="true" />
                        <span style={{ fontSize: 12.5, color: '#6B605B' }}>
                          {dowLabel(i)} <span className="num">{md(d.date)}</span>
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>

            <Card title={t('dashboard.kpi.today')} sub={t('dashboard.today.sub')} guide="dashboard-today">
              {!todayList ? (
                <p className="kp-card-sub">{loadingText('today')}</p>
              ) : todayList.length === 0 ? (
                <p className="kp-card-sub">{t('dashboard.today.empty')}</p>
              ) : (
                <>
                  {(todayOpen ? todayList : todayList.slice(0, TODAY_FOLD)).map((r) => (
                    <div className="kp-care-row" key={r.id}>
                      <span className="num" style={{ fontWeight: 600, color: '#8A6F66', fontSize: '13px', width: 44 }}>{hmOf(r.startAt)}</span>
                      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
                        <AppLink href={r.customer ? `/customers/detail?id=${r.customer.id}` : `/reservations/detail?id=${r.id}`} style={{ fontWeight: 600, fontSize: '15px' }}>
                          {r.customer?.hasCaution ? (
                            <span title={t('dashboard.today.cautionTitle')} aria-label={t('dashboard.today.cautionLabel')} role="img" style={{ display: 'inline-flex', verticalAlign: '-2px', marginRight: 4, color: '#8F2F2F' }}>
                              {/* 경고 삼각형 (Lucide triangle-alert, ISC) */}
                              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                                <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3" />
                                <path d="M12 9v4" />
                                <path d="M12 17h.01" />
                              </svg>
                            </span>
                          ) : null}
                          {resvName(r)}
                        </AppLink>
                        <span style={{ fontSize: '12.5px', color: '#6B605B' }}>
                          {r.services.map((s) => s.name).join(' · ')} · {r.staff.name}
                          {r.consultFirst ? ` · ${t('dashboard.today.consult')}` : ''}
                        </span>
                      </div>
                      {r.status === 'COMPLETED' ? <span className="kp-tag" style={{ background: '#ECE6E1', color: '#4A3F3B' }}>{t('dashboard.today.done')}</span> : null}
                      {!r.customer ? (
                        <AppLink href={`/reservations/detail?id=${r.id}`} className="kp-btn">
                          {t('dashboard.today.link')}
                        </AppLink>
                      ) : null}
                    </div>
                  ))}
                  {todayList.length > TODAY_FOLD ? (
                    <button type="button" className="kp-fold" aria-expanded={todayOpen} onClick={() => setTodayOpen((o) => !o)}>
                      {todayOpen ? t('dashboard.today.collapse') : t('dashboard.today.expand', { n: todayList.length })}
                    </button>
                  ) : null}
                </>
              )}
            </Card>

            <Card title={t('dashboard.kpi.unclosed')} sub={t('dashboard.unclosed.sub')} className="kp-act" guide="dashboard-unclosed">
              {!today ? (
                <p className="kp-card-sub">{loadingText('today')}</p>
              ) : today.unclosed.length === 0 ? (
                <p className="kp-card-sub">{t('dashboard.unclosed.empty')}</p>
              ) : (
                today.unclosed.map((r) => (
                  <AppLink href={`/reservations/closing?id=${r.id}`} className="kp-act-row" key={r.id} style={{ color: 'inherit' }}>
                    <span className="num" style={{ fontWeight: 600, color: '#8A6F66', fontSize: '13px' }}>{md(r.startAt)}</span>
                    <span style={{ display: 'flex', flexDirection: 'column' }}>
                      <strong style={{ fontSize: '14px' }}>{resvName(r)}</strong>
                      <span style={{ fontSize: '13px', color: '#4A3F3B' }}>
                        {r.services.map((s) => s.name).join(' · ')} · {hmOf(r.startAt)} · {t('dashboard.kpi.unclosedNote')}
                      </span>
                    </span>
                  </AppLink>
                ))
              )}
            </Card>

            <Card title={t('dashboard.recent.title')} sub={t('dashboard.recent.sub')} className="kp-act">
              {!campaignOpen ? (
                <Locked what={t('dashboard.locked.campaigns')} />
              ) : !recent ? (
                <p className="kp-card-sub">{loadingText('recent')}</p>
              ) : recent.length === 0 ? (
                <p className="kp-card-sub">{t('dashboard.recent.empty')}</p>
              ) : (
                recent.map((c) => (
                  <AppLink href={`/campaigns/detail?id=${c.id}`} className="kp-act-row" key={c.id} style={{ color: 'inherit', gridTemplateColumns: '1fr auto' }}>
                    <span style={{ display: 'flex', flexDirection: 'column' }}>
                      <strong style={{ fontSize: '14px' }}>{c.name}</strong>
                      <span style={{ fontSize: '13px', color: '#4A3F3B' }}>
                        {t('dashboard.recent.row', { date: md(c.sentAt), category: CATEGORY_LABEL[c.category] ?? c.category, rebooked: c.rebookedCount, reached: c.reachedCount })}
                      </span>
                    </span>
                    <b className="num" style={{ fontSize: '20px', color: '#7A4450' }}>{rateText(c.rebookedCount, c.reachedCount) ?? '-'}</b>
                  </AppLink>
                ))
              )}
            </Card>

            <Card
              title={t('dashboard.channels.title')}
              sub={ch ? t('dashboard.channels.sub', { from: ch.from, to: ch.to, total: ch.total }) : t('dashboard.channels.subEmpty')}
              className="kp-act"
              head={
                <div className="kp-seg" role="group" aria-label={t('dashboard.channels.period')}>
                  {PERIODS.filter(([p]) => p !== 'CUSTOM').map(([p, label]) => (
                    <button type="button" key={p} aria-pressed={period === p} onClick={() => setPeriod(p)}>
                      {t(label)}
                    </button>
                  ))}
                </div>
              }
            >
              {!ch ? (
                <p className="kp-card-sub">{loadingText(`channels-${period}`)}</p>
              ) : (
                ch.channels.map((c) => (
                  <div key={c.channel} style={{ display: 'grid', gridTemplateColumns: '84px 1fr 56px', gap: 10, alignItems: 'center', padding: '8px 0' }}>
                    <span style={{ fontSize: 13 }}>{CHANNEL_LABEL[c.channel] ?? c.channel}</span>
                    <span style={{ height: 8, borderRadius: 99, background: '#EFE8E2', overflow: 'hidden' }}>
                      <span style={{ display: 'block', height: '100%', width: `${c.ratio * 100}%`, background: '#7A4450' }} />
                    </span>
                    <span className="num" style={{ fontSize: 13, textAlign: 'right' }}>{ratioText(c.ratio)}</span>
                  </div>
                ))
              )}
            </Card>
          </div>
          <div className="kp-col">
            <section className="kp-card kp-rel" aria-label={t('dashboard.rel.label')}>
              <div className="kp-card-head">
                <div>
                  <h2 className="kp-card-title">{t('dashboard.rel.label')}</h2>
                  <p className="kp-card-sub">{t('dashboard.rel.sub')}</p>
                </div>
              </div>
              {!insightOpen ? (
                <Locked what={t('dashboard.locked.insights')} />
              ) : !segCounts ? (
                <p className="kp-card-sub">{loadingText('segments')}</p>
              ) : (
                <div className="kp-rel-body">
                  <div className="kp-donut">
                    <svg width="224" height="224" viewBox="0 0 224 224" role="img" aria-label={t('dashboard.rel.donut', { list: SEGS.map((s) => `${t(s.label)} ${segCounts[s.status]}`).join(', ') })}>
                      <g transform="rotate(-90 112 112)" fill="none" strokeWidth="30">
                        {arcs.map((a, i) => (
                          <circle cx="112" cy="112" r={R} stroke={a.color} strokeDasharray={drawn ? a.dash : `0 ${C}`} strokeDashoffset={a.off} className="kp-arc" style={{ transitionDelay: `${i * 90}ms` }} key={i}></circle>
                        ))}
                      </g>
                    </svg>
                    <div className="kp-donut-center">
                      <b className="num">
                        <CountUp value={segTotal} />
                      </b>
                      <span>{t('dashboard.rel.center')}</span>
                    </div>
                  </div>
                  <div className="kp-seglist">
                    {SEGS.map((s) => (
                      <AppLink href="/insights" className="kp-segrow" key={s.status}>
                        <span className="kp-dot" style={{ background: SEG_COLOR[s.status] }}></span>
                        <span className="name">{t(s.label)}</span>
                        <span className="rule">{t(s.rule)}</span>
                        <span className="n num">{t('common.persons', { n: segCounts[s.status] })}</span>
                      </AppLink>
                    ))}
                  </div>
                </div>
              )}
            </section>

            <Card
              title={t('dashboard.care.title')}
              sub={t('dashboard.care.sub')}
              guide="dashboard-care"
              head={insightOpen ? <AppLink href="/insights" className="kp-btn">{t('dashboard.care.all')}</AppLink> : null}
            >
              {!insightOpen ? (
                <Locked what={t('dashboard.locked.insights')} />
              ) : !care ? (
                <p className="kp-card-sub">{loadingText('care')}</p>
              ) : care.length === 0 ? (
                <p className="kp-card-sub">{t('dashboard.care.empty')}</p>
              ) : (
                care.map((c) => {
                  const tab = STATUS_TABS.find((x) => x.status === c.status);
                  return (
                    <div className="kp-care-row" key={`${c.customerId}-${c.category}`}>
                      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
                        <AppLink href={`/customers/detail?id=${c.customerId}`} style={{ fontWeight: 600, fontSize: '15px' }}>
                          {c.name}
                        </AppLink>
                        <span style={{ fontSize: '12.5px', color: '#6B605B' }}>
                          {CATEGORY_LABEL[c.category]} · {t('common.elapsedDays', { days: c.elapsedDays })} · {c.marketingConsent ? t('common.consent') : t('common.noConsent')}
                        </span>
                      </div>
                      {tab ? <span className="kp-tag" style={{ background: c.status === 'DUE' ? '#F5EBD3' : '#F4E0DC', color: c.status === 'DUE' ? '#6B4509' : '#8F2F2F' }}>{t(tab.labelKey)}</span> : null}
                      {c.nextAction && c.nextAction !== 'REQUEST_CONSENT' ? (
                        <AppLink href="/campaigns" className="kp-btn p">{nextActionLabel(c.nextAction)}</AppLink>
                      ) : (
                        <span style={{ fontSize: '12.5px', color: '#6B605B' }}>{c.nextAction ? nextActionLabel('REQUEST_CONSENT') : t('dashboard.care.noAction')}</span>
                      )}
                    </div>
                  );
                })
              )}
            </Card>
          </div>
        </div>

        <p className="kp-foot">{t('dashboard.foot')}</p>
      </div>
    </>
  );
}
