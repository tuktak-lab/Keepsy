import { useEffect, useState, type ReactNode } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { openPinDialog } from '@/components/common/PinDialog';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import styleText from './DashboardPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { CountUp } from '@/components/common/CountUp';
import { api, type Page } from '@/lib/api';
import { PERIODS, type Period } from '@/lib/analytics';
import { CHANNEL_LABEL, DOW_LABEL, hmOf, type Channel } from '@/lib/calendar';
import { rateText } from '@/lib/campaignResult';
import { bookedCount, isActive, ratioText } from '@/lib/dashboard';
import { NEXT_ACTION_LABEL, STATUS_TABS } from '@/lib/insights';
import { CATEGORY_LABEL, type Category } from '@/lib/menus';
import { useSession } from '@/lib/session';

// CRM 대시보드 (FR-DSH-01~06). 대시보드 자체는 DASHBOARD_SEARCH(공용 고정)이고, 아래 두 영역만 기능 키가 다르다 (명세서 2장):
// - 오늘 관리할 고객 · 재방문 상태 · 주기 도래 숫자: INSIGHT / 최근 캠페인 성과 · 재예약 전환율: CAMPAIGN_VIEW
//   PIN으로 잠겨 있으면 화면을 열자마자 PIN 창을 띄우지 않고 영역을 가려 두었다가, [PIN 입력] 뒤에 불러온다 (FR-DSH-02 · 04)
// API (docs CONTRACTS 4장): GET /api/dashboard/today · care-targets · recent-campaigns · booking-channels, GET /api/insights(상태별 인원), GET /api/customers(전체 인원)

const logo = logoImg.src;

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
const SEGS = [
  { status: 'SCHEDULED', label: '예정', rule: '다음 방문 예측 기간 전' },
  { status: 'DUE', label: '주기 도래', rule: '예측 기간 안 · 예약 없음' },
  { status: 'CHURN_SIGNAL', label: '이탈 신호', rule: '예측 종료 후 이탈 신호 기간 안' },
  { status: 'LONG_ABSENT', label: '장기 미방문', rule: '이탈 신호 기간 이후' },
];
const md = (iso: string) => `${Number(iso.slice(5, 7))}.${iso.slice(8, 10)}`;
const resvName = (r: Resv) => r.customer?.name ?? `고객 없는 예약 · ${CHANNEL_LABEL[r.channel] ?? r.channel}`;

/** PIN으로 잠긴 영역: 가려 두고 버튼으로 PIN을 받는다 (인증되면 세션이 바뀌어 다시 불러온다) */
function Locked(props: { what: string }) {
  return (
    <div style={{ padding: '18px 0', display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'flex-start' }}>
      <p className="kp-card-sub" style={{ margin: 0 }}>「{props.what}」는 PIN으로 잠긴 기능이에요. PIN을 입력하면 보여요.</p>
      <button type="button" className="kp-btn p" onClick={() => void openPinDialog({ owner: false, message: '' })}>
        PIN 입력
      </button>
    </div>
  );
}

function Card(props: { title: string; sub?: string; head?: ReactNode; className?: string; children: ReactNode }) {
  return (
    <section className={'kp-card ' + (props.className ?? 'kp-care')} aria-label={props.title}>
      <div className="kp-card-head">
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
  const [channels, setChannels] = useState<{ period: Period; value: Channels } | null>(null);
  const [drawn, setDrawn] = useState(false);

  // 첫 진입 시 도넛이 0에서 채워지도록 다음 프레임에 켭니다
  useEffect(() => {
    const r = requestAnimationFrame(() => setDrawn(true));
    return () => cancelAnimationFrame(r);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api<Today>('/api/dashboard/today', { silent: true }).then((t) => !cancelled && setToday(t), () => {});
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!customerOpen) return;
    let cancelled = false;
    api<Page<unknown>>('/api/customers?page=0&size=1', { silent: true }).then((p) => !cancelled && setCustomers(p.totalElements), () => {});
    return () => {
      cancelled = true;
    };
  }, [customerOpen]);

  useEffect(() => {
    if (!insightOpen) return;
    let cancelled = false;
    api<Care[]>('/api/dashboard/care-targets', { silent: true }).then((c) => !cancelled && setCare(c), () => {});
    Promise.all(SEGS.map((s) => api<Page<unknown>>(`/api/insights?status=${s.status}&page=0&size=1`, { silent: true }).then((p) => [s.status, p.totalElements] as const)))
      .then((pairs) => !cancelled && setSegCounts(Object.fromEntries(pairs)))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [insightOpen]);

  useEffect(() => {
    if (!campaignOpen) return;
    let cancelled = false;
    api<Recent[]>('/api/dashboard/recent-campaigns', { silent: true }).then((r) => !cancelled && setRecent(r), () => {});
    return () => {
      cancelled = true;
    };
  }, [campaignOpen]);

  useEffect(() => {
    let cancelled = false;
    api<Channels>(`/api/dashboard/booking-channels?period=${period}`, { silent: true }).then((value) => !cancelled && setChannels({ period, value }), () => {});
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
    <div style={{ display: 'flex', flexWrap: 'wrap', minHeight: '1040px' }}>
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="dashboard" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb="Customer Intelligence / Overview" />
        <div className="kp-wrap">
          <div className="kp-hero">
            <div>
              <h1 className="kp-hello">
                안녕하세요,
                <br />
                <img src={logo} alt="Keepsy" className="kp-hello-logo" />
                입니다
              </h1>
              <p className="kp-lead">오늘도 고객과 더 가까워지는 하루가 되길 바라요.</p>
            </div>
            <AppLink href="/insights" className="kp-cta">
              오늘 관리할 고객 보기
            </AppLink>
          </div>

          <section className="kp-kpis kp-card" aria-label="고객 현황 요약">
            {kpi('/customers', '전체 고객', num(customers), customerOpen ? '삭제된 고객 제외' : 'PIN 인증 후 표시')}
            {kpi('/reservations', '오늘 예약', num(todayList?.length), todayList ? `확정 ${todayList.filter((r) => r.status === 'CONFIRMED').length} · 상담 후 진행 ${todayList.filter((r) => r.consultFirst).length}` : '')}
            {kpi('/reservations', '마감 안 된 예약', num(today?.unclosed.length), '지난 예약 · 시술 마감 필요')}
            {kpi('/insights', '주기 도래 고객', num(insightOpen ? segCounts?.DUE : null), insightOpen ? '예약 없음 · 우선순위 순' : 'PIN 인증 후 표시')}
            {kpi(
              latest ? `/campaigns/detail?id=${latest.id}` : '/analytics',
              '재예약 전환율',
              latest ? (rateText(latest.rebookedCount, latest.reachedCount) ?? '-') : '-',
              !campaignOpen ? 'PIN 인증 후 표시' : latest ? `최근 캠페인 · ${latest.name}` : '발송한 캠페인 없음',
            )}
          </section>

          <div className="kp-low">
            <Card title="오늘 예약" sub="시간순 · 취소 · 노쇼 제외">
              {!todayList ? (
                <p className="kp-card-sub">불러오는 중…</p>
              ) : todayList.length === 0 ? (
                <p className="kp-card-sub">오늘 예약이 없어요.</p>
              ) : (
                todayList.map((r) => (
                  <div className="kp-care-row" key={r.id}>
                    <span className="num" style={{ fontWeight: 600, color: '#8A6F66', fontSize: '13px', width: 44 }}>{hmOf(r.startAt)}</span>
                    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
                      <AppLink href={r.customer ? `/customers/detail?id=${r.customer.id}` : `/reservations/detail?id=${r.id}`} style={{ fontWeight: 600, fontSize: '15px' }}>
                        {r.customer?.hasCaution ? <span title="주의사항이 있는 고객" aria-label="주의사항 있음">⚠️ </span> : null}
                        {resvName(r)}
                      </AppLink>
                      <span style={{ fontSize: '12.5px', color: '#6B605B' }}>
                        {r.services.map((s) => s.name).join(' · ')} · {r.staff.name}
                        {r.consultFirst ? ' · 상담 후 진행' : ''}
                      </span>
                    </div>
                    {r.status === 'COMPLETED' ? <span className="kp-tag" style={{ background: '#ECE6E1', color: '#4A3F3B' }}>완료</span> : null}
                    {!r.customer ? (
                      <AppLink href={`/reservations/detail?id=${r.id}`} className="kp-btn">
                        고객 연결
                      </AppLink>
                    ) : null}
                  </div>
                ))
              )}
            </Card>

            <Card title="마감 안 된 예약" sub="시작 시각이 지났는데 마감 · 취소 · 노쇼 처리가 안 됐어요" className="kp-act">
              {!today ? (
                <p className="kp-card-sub">불러오는 중…</p>
              ) : today.unclosed.length === 0 ? (
                <p className="kp-card-sub">마감할 예약이 없어요.</p>
              ) : (
                today.unclosed.map((r) => (
                  <AppLink href={`/reservations/closing?id=${r.id}`} className="kp-act-row" key={r.id} style={{ color: 'inherit' }}>
                    <span className="num" style={{ fontWeight: 600, color: '#8A6F66', fontSize: '13px' }}>{md(r.startAt)}</span>
                    <span style={{ display: 'flex', flexDirection: 'column' }}>
                      <strong style={{ fontSize: '14px' }}>{resvName(r)}</strong>
                      <span style={{ fontSize: '13px', color: '#4A3F3B' }}>
                        {r.services.map((s) => s.name).join(' · ')} · {hmOf(r.startAt)} · 시술 마감 필요
                      </span>
                    </span>
                  </AppLink>
                ))
              )}
            </Card>
          </div>

          <div className="kp-low">
            <Card
              title="오늘 관리할 고객"
              sub="주기 도래 · 이탈 신호 중 우선순위 상위 10명 (경과 비율 → 수신 동의 → 최근 6개월 결제 순)"
              head={insightOpen ? <AppLink href="/insights" className="kp-btn">전체 보기</AppLink> : null}
            >
              {!insightOpen ? (
                <Locked what="인사이트" />
              ) : !care ? (
                <p className="kp-card-sub">불러오는 중…</p>
              ) : care.length === 0 ? (
                <p className="kp-card-sub">오늘 관리할 고객이 없어요.</p>
              ) : (
                care.map((c) => {
                  const t = STATUS_TABS.find((x) => x.status === c.status);
                  return (
                    <div className="kp-care-row" key={`${c.customerId}-${c.category}`}>
                      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
                        <AppLink href={`/customers/detail?id=${c.customerId}`} style={{ fontWeight: 600, fontSize: '15px' }}>
                          {c.name}
                        </AppLink>
                        <span style={{ fontSize: '12.5px', color: '#6B605B' }}>
                          {CATEGORY_LABEL[c.category]} · {c.elapsedDays}일 경과 · {c.marketingConsent ? '수신 동의' : '수신 미동의'}
                        </span>
                      </div>
                      {t ? <span className="kp-tag" style={{ background: c.status === 'DUE' ? '#F5EBD3' : '#F4E0DC', color: c.status === 'DUE' ? '#6B4509' : '#8F2F2F' }}>{t.label}</span> : null}
                      {c.nextAction && c.nextAction !== 'REQUEST_CONSENT' ? (
                        <AppLink href="/campaigns" className="kp-btn p">{NEXT_ACTION_LABEL[c.nextAction]}</AppLink>
                      ) : (
                        <span style={{ fontSize: '12.5px', color: '#6B605B' }}>{c.nextAction ? NEXT_ACTION_LABEL.REQUEST_CONSENT : '액션 없음'}</span>
                      )}
                    </div>
                  );
                })
              )}
            </Card>

            <Card title="최근 캠페인 성과" sub="최근 3개 캠페인 재예약 전환율 (재예약 ÷ 도달)" className="kp-act">
              {!campaignOpen ? (
                <Locked what="캠페인 목록 · 결과 조회" />
              ) : !recent ? (
                <p className="kp-card-sub">불러오는 중…</p>
              ) : recent.length === 0 ? (
                <p className="kp-card-sub">아직 발송한 캠페인이 없어요.</p>
              ) : (
                recent.map((c) => (
                  <AppLink href={`/campaigns/detail?id=${c.id}`} className="kp-act-row" key={c.id} style={{ color: 'inherit', gridTemplateColumns: '1fr auto' }}>
                    <span style={{ display: 'flex', flexDirection: 'column' }}>
                      <strong style={{ fontSize: '14px' }}>{c.name}</strong>
                      <span style={{ fontSize: '13px', color: '#4A3F3B' }}>
                        {md(c.sentAt)} 발송 · {CATEGORY_LABEL[c.category] ?? c.category} · 재예약 {c.rebookedCount} / 도달 {c.reachedCount}명
                      </span>
                    </span>
                    <b className="num" style={{ fontSize: '20px', color: '#7A4450' }}>{rateText(c.rebookedCount, c.reachedCount) ?? '-'}</b>
                  </AppLink>
                ))
              )}
            </Card>
          </div>

          <div className="kp-low">
            <Card title="이번 주 예약" sub="요일별 예약 건수 (취소 · 노쇼 제외)">
              {!today ? (
                <p className="kp-card-sub">불러오는 중…</p>
              ) : (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 10, alignItems: 'end', height: 150, marginTop: 14 }}>
                  {today.week.map((d, i) => {
                    const n = bookedCount(d.counts);
                    return (
                      <div key={d.date} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, height: '100%', justifyContent: 'flex-end' }}>
                        <b className="num" style={{ fontSize: 14 }}>{n}</b>
                        <span style={{ width: '60%', height: `${(n / weekMax) * 90}px`, minHeight: 3, borderRadius: 6, background: '#7A4450' }} aria-hidden="true" />
                        <span style={{ fontSize: 12.5, color: '#6B605B' }}>
                          {DOW_LABEL[i]} <span className="num">{md(d.date)}</span>
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>

            <Card
              title="예약 경로"
              sub={ch ? `${ch.from} ~ ${ch.to} · ${ch.total}건 · 시드 데이터 비율이에요` : '기간 안 예약의 경로 비율'}
              className="kp-act"
              head={
                <div className="kp-seg" role="group" aria-label="기간">
                  {PERIODS.filter(([p]) => p !== 'CUSTOM').map(([p, label]) => (
                    <button type="button" key={p} aria-pressed={period === p} onClick={() => setPeriod(p)}>
                      {label}
                    </button>
                  ))}
                </div>
              }
            >
              {!ch ? (
                <p className="kp-card-sub">불러오는 중…</p>
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

          <section className="kp-card kp-rel" aria-label="고객 재방문 상태">
            <div className="kp-card-head">
              <div>
                <h2 className="kp-card-title">고객 재방문 상태</h2>
                <p className="kp-card-sub">시술별 방문 주기 예측으로 보는 현재 상태 (고객 × 시술 카테고리, 다음 예약 · 관리 종료 제외)</p>
              </div>
            </div>
            {!insightOpen ? (
              <Locked what="인사이트" />
            ) : !segCounts ? (
              <p className="kp-card-sub">불러오는 중…</p>
            ) : (
              <div className="kp-rel-body">
                <div className="kp-donut">
                  <svg width="224" height="224" viewBox="0 0 224 224" role="img" aria-label={`재방문 상태 분포: ${SEGS.map((s) => `${s.label} ${segCounts[s.status]}`).join(', ')}`}>
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
                    <span>관리 대상</span>
                  </div>
                </div>
                <div className="kp-seglist">
                  {SEGS.map((s) => (
                    <AppLink href="/insights" className="kp-segrow" key={s.status}>
                      <span className="kp-dot" style={{ background: SEG_COLOR[s.status] }}></span>
                      <span className="name">{s.label}</span>
                      <span className="rule">{s.rule}</span>
                      <span className="n num">{segCounts[s.status]}명</span>
                    </AppLink>
                  ))}
                </div>
              </div>
            )}
          </section>

          <p className="kp-foot">모의 데이터로 생성한 값이에요. 예약 경로 비율은 시드 데이터 기준이에요.</p>
        </div>
      </main>
    </div>
  );
}
