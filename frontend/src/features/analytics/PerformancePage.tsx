import { useEffect, useState, type ReactNode } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { PinGate } from '@/components/common/PinGate';
import { CountUp } from '@/components/common/CountUp';
import { ApiError, api } from '@/lib/api';
import { PERIODS, periodQuery, type Funnel, type Period } from '@/lib/analytics';
import { rateText } from '@/lib/campaignResult';
import { formatDateTime } from '@/lib/format';
import { CATEGORY_LABEL, type Category } from '@/lib/menus';
import styleText from './PerformancePage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { t } from '@/lib/i18n';
import { formatWon } from '@/lib/format';

// 성과 분석 (FR-ANL-01 · 02 · 05 · 06 · 07 · 09). 화면 전체가 「매출 · 성과 분석」 기능이다 (SALES_ANALYTICS, PinGate).
// API (docs CONTRACTS 4장): 기간별 GET /api/analytics/summary · /prediction-accuracy,
// 캠페인 성과는 GET /api/analytics/campaigns 한 번 (기간 안에 발송한 광고 캠페인별 퍼널 · 연결 매출과 중복 없는 전체 합계, F65), 고른 캠페인의 GET /api/campaigns/{id}/control-comparison
// 결과는 모의 데이터로 만든 값임을 밝힌다 (E9).

type Summary = {
  from: string;
  to: string;
  revenue: number;
  visitCount: number;
  newCustomerCount: number;
  // shortcut: F58 계약 필드라 백엔드 구현 전에는 오지 않는다. 오면 바로 보이고, 없으면 그 카드만 「준비 중」
  cycleRevisits?: { category: Category; endedCount: number; revisitedCount: number }[];
};
type Accuracy = { evaluatedCount: number; hitCount: number; averageErrorDays: number | null };
type Camp = Funnel & { id: number; name: string; category: Category; sentAt: string; revenue: number };
type CampaignTotal = Funnel & { campaignCount: number; revenue: number };
type Campaigns = { items: Camp[]; totalElements: number; total: CampaignTotal };
const ZERO: Funnel = { sentCount: 0, reachedCount: 0, rebookedCount: 0, revisitedCount: 0 };
const CAMP_SIZE = 100; // API 최대. 기간 안 캠페인이 더 많으면 화면에는 발송이 늦은 순으로 이만큼만 보이고, 합계는 전체 (표 아래에 안내)
type Control = {
  reachedCount: number;
  rebookedCount: number;
  controlCount: number;
  controlRebookedCount: number;
  reachedCycleCount: number;
  reachedCycleRevisitedCount: number;
  controlCycleCount: number;
  controlCycleRevisitedCount: number;
};

const pct = (a: number, b: number) => (b ? Math.round((a / b) * 1000) / 10 : 0);
const unit = (u: string) => (n: number) => n + u;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : t('common.pin.tryLater'));

// 막대: 처음 진입 시 0 → 값으로 한 번 차오르고, 값이 바뀌면 transition 으로 이동. hover 시 정확한 수치 툴팁.
function Bar(props: { w: number; color: string; tip: ReactNode; grown: boolean; show: boolean }) {
  const w = Math.max(0, Math.min(100, props.w));
  return (
    <span className="pf-barwrap">
      <span className="pf-bar"><i style={{ width: props.grown ? `${w}%` : '0%', background: props.color }} /></span>
      {props.show ? <span className="kp-tip" style={{ left: `${Math.max(6, Math.min(94, w))}%`, top: 0 }}>{props.tip}</span> : null}
    </span>
  );
}

function Body() {
  const [period, setPeriod] = useState<Period>('THIS_MONTH');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const query = periodQuery(period, from, to);
  const [stats, setStats] = useState<{ q: string; summary?: Summary; accuracy?: Accuracy; error?: string } | null>(null);
  const [campRes, setCampRes] = useState<{ q: string; data?: Campaigns; error?: string } | null>(null);
  const [sel, setSel] = useState<number | 'all'>('all');
  const [control, setControl] = useState<{ id: number; value?: Control; error?: string } | null>(null);
  const [grown, setGrown] = useState(false);
  const [hover, setHover] = useState('');

  useEffect(() => {
    let r2 = 0;
    const r1 = requestAnimationFrame(() => { r2 = requestAnimationFrame(() => setGrown(true)); });
    return () => { cancelAnimationFrame(r1); cancelAnimationFrame(r2); };
  }, []);

  // 기간 지표 (FR-ANL-05 · 06 · 07)
  useEffect(() => {
    if (!query) return;
    let cancelled = false;
    Promise.all([api<Summary>(`/api/analytics/summary?${query}`, { silent: true }), api<Accuracy>(`/api/analytics/prediction-accuracy?${query}`, { silent: true })])
      .then(([summary, accuracy]) => !cancelled && setStats({ q: query, summary, accuracy }))
      .catch((err) => !cancelled && setStats({ q: query, error: errorText(err) }));
    return () => { cancelled = true; };
  }, [query]);

  // 기간 안에 발송한 캠페인의 퍼널 · 연결 매출과 전체 합계 (FR-ANL-01 · 02)
  useEffect(() => {
    if (!query) return;
    let cancelled = false;
    api<Campaigns>(`/api/analytics/campaigns?${query}&page=0&size=${CAMP_SIZE}`, { silent: true })
      .then((data) => !cancelled && setCampRes({ q: query, data }))
      .catch((err) => !cancelled && setCampRes({ q: query, error: errorText(err) }));
    return () => { cancelled = true; };
  }, [query]);

  // 고른 캠페인의 대조군 비교 (FR-ANL-09)
  useEffect(() => {
    if (sel === 'all') return;
    let cancelled = false;
    api<Control>(`/api/campaigns/${sel}/control-comparison`, { silent: true })
      .then((value) => !cancelled && setControl({ id: sel, value }))
      .catch((err) => !cancelled && setControl({ id: sel, error: errorText(err) }));
    return () => { cancelled = true; };
  }, [sel]);

  const cur = stats && stats.q === query ? stats : null;
  const campCur = campRes && campRes.q === query ? campRes : null;
  const camps = campCur?.data?.items;
  // 기간을 바꿔 고른 캠페인이 목록에서 빠졌으면 「전체」로 본다
  const selId = camps?.some((c) => c.id === sel) ? sel : 'all';
  const f: Funnel = selId === 'all' ? campCur?.data?.total ?? ZERO : camps?.find((c) => c.id === selId) ?? ZERO;
  const ctrl = selId !== 'all' && control?.id === selId ? control : null;
  const funnelRows = [
    { label: t('campaign.detail.funnelSent'), n: f.sentCount, c: '#D9AFA6' },
    { label: t('campaign.detail.funnelReached'), n: f.reachedCount, c: '#CC9A9E' },
    { label: t('campaign.detail.funnelRebooked'), n: f.rebookedCount, c: '#A9707A' },
    { label: t('campaign.detail.funnelRevisited'), n: f.revisitedCount, c: '#7A4450' },
  ];

  return (
    <div className="pf-wrap">
      {/* 1줄: 제목 · 기간 버튼 (고정). 2줄: 설명 · 직접 지정 날짜. 날짜가 없을 때도 2줄 높이를 유지해 아무것도 움직이지 않는다 (#424) */}
      <div className="pf-head" data-guide="analytics-period">
        <h1 className="pf-h1">{t('shell.nav.performance')}</h1>
        <div className="pf-seg" role="group" aria-label={t('dashboard.channels.period')}>
          {PERIODS.map(([p, label]) => (
            <button type="button" key={p} aria-pressed={period === p} onClick={() => setPeriod(p)}>{t(label)}</button>
          ))}
        </div>
        <p className="pf-sub">{t('analytics.sub')}</p>
        <div className="pf-range">
          {period === 'CUSTOM' ? (
            <>
              <input type="date" className="inp" aria-label={t('analytics.from')} value={from} onChange={(e) => setFrom(e.target.value)} />~
              <input type="date" className="inp" aria-label={t('analytics.to')} value={to} onChange={(e) => setTo(e.target.value)} />
            </>
          ) : null}
        </div>
      </div>

      <section className="pf-kpis pf-card" aria-label={t('analytics.kpiLabel')} data-guide="analytics-kpis">
        {!query ? (
          <p className="pf-note" style={{ padding: '12px 24px' }}>{t('analytics.pickDates')}</p>
        ) : cur?.error ? (
          <p className="pf-note" style={{ padding: '12px 24px' }}>{t('prediction.loadFailed', { reason: cur.error })}</p>
        ) : !cur?.summary || !cur.accuracy ? (
          <p className="pf-note" style={{ padding: '12px 24px' }}>{t('common.loading')}</p>
        ) : (
          <>
            <div className="pf-kpi"><span>{t('analytics.revenue')}</span><b className="num"><CountUp value={cur.summary.revenue} format={formatWon} /></b><em className="num">{cur.summary.from} ~ {cur.summary.to}</em></div>
            <div className="pf-kpi"><span>{t('analytics.visits')}</span><b className="num"><CountUp value={cur.summary.visitCount} format={unit(t('campaign.unit.cases'))} /></b><em>{t('analytics.excludeImported')}</em></div>
            <div className="pf-kpi"><span>{t('analytics.newCustomers')}</span><b className="num"><CountUp value={cur.summary.newCustomerCount} format={unit(t('campaign.unit.persons'))} /></b><em>{t('analytics.byFirstVisit')}</em></div>
            <div className="pf-kpi">
              <span>{t('analytics.hitRate')}</span>
              <b className="num">{rateText(cur.accuracy.hitCount, cur.accuracy.evaluatedCount) ?? '-'}</b>
              <em className="num">
                {t('analytics.accuracyLine', { n: cur.accuracy.evaluatedCount, err: cur.accuracy.averageErrorDays === null ? '-' : t('customer.summary.days', { n: cur.accuracy.averageErrorDays }) })}
              </em>
            </div>
          </>
        )}
      </section>

      <section className="pf-card" aria-label={t('analytics.cycleLabel')} data-guide="analytics-revisit">
        <div>
          <h2>{t('analytics.cycleTitle')}</h2>
          <span className="pf-note">{t('analytics.cycleNote')}</span>
        </div>
        {!cur?.summary ? (
          <p className="pf-note">{t('common.loading')}</p>
        ) : !cur.summary.cycleRevisits ? (
          <p className="pf-note">{t('analytics.cyclePreparing')}</p>
        ) : (
          <div>
            {cur.summary.cycleRevisits.map((r) => {
              const rate = rateText(r.revisitedCount, r.endedCount);
              return (
                <div className="pf-frow" key={r.category} style={{ gridTemplateColumns: '110px 1fr 60px 84px' }} onMouseEnter={() => setHover('r' + r.category)} onMouseLeave={() => setHover('')}>
                  <span style={{ fontSize: 13, color: '#4A3F3B' }}>{CATEGORY_LABEL[r.category] ?? r.category}</span>
                  <Bar w={pct(r.revisitedCount, r.endedCount)} color="#7A4450" grown={grown} show={hover === 'r' + r.category} tip={<>{CATEGORY_LABEL[r.category]} <b>{rate ?? '-'}</b> · {t('analytics.ratioPersons', { a: r.revisitedCount, b: r.endedCount })}</>} />
                  <b className="num" style={{ textAlign: 'right' }}>{rate ?? '-'}</b>
                  <span className="num pf-note" style={{ textAlign: 'right' }}>{t('analytics.ratioPersons', { a: r.revisitedCount, b: r.endedCount })}</span>
                </div>
              );
            })}
          </div>
        )}
      </section>

      <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }} data-guide="analytics-campaigns">
        <h2 style={{ fontSize: 19, fontWeight: 600 }}>{t('analytics.campaigns')}</h2>
        {camps && camps.length > 0 ? (
          <div className="pf-seg" role="group" aria-label={t('analytics.pickCampaign')}>
            <button type="button" aria-pressed={selId === 'all'} onClick={() => setSel('all')}>{t('reservation.all')}</button>
            {camps.map((x) => (
              <button type="button" key={x.id} aria-pressed={selId === x.id} onClick={() => setSel(x.id)}>{x.name}</button>
            ))}
          </div>
        ) : null}
      </div>

      {!query ? (
        <p className="pf-note">{t('analytics.pickPeriod')}</p>
      ) : campCur?.error ? (
        <p className="pf-note">{t('analytics.campFailed', { reason: campCur.error })}</p>
      ) : !camps || !campCur?.data ? (
        <p className="pf-note">{t('common.loading')}</p>
      ) : camps.length === 0 ? (
        <p className="pf-note">{t('analytics.noCampaigns')}</p>
      ) : (
        <>
          <div className="pf-two">
            <section className="pf-card" aria-label={t('campaign.detail.funnel')}>
              <div>
                <h2>{t('analytics.funnelTitle')}</h2>
                <span className="pf-note">{t('analytics.funnelFlow')}{selId === 'all' ? t('analytics.funnelAll', { n: campCur.data.total.campaignCount }) : t('analytics.funnelOne')}</span>
              </div>
              <div>
                {funnelRows.map((r) => (
                  <div className="pf-frow" key={r.label} onMouseEnter={() => setHover('f' + r.label)} onMouseLeave={() => setHover('')}>
                    <span style={{ fontSize: 13, color: '#4A3F3B' }}>{r.label}</span>
                    <Bar w={pct(r.n, f.sentCount)} color={r.c} grown={grown} show={hover === 'f' + r.label} tip={<>{r.label} <b>{t('common.persons', { n: r.n })}</b> · {t('analytics.funnelTip', { pct: pct(r.n, f.sentCount) })}</>} />
                    <b className="num" style={{ textAlign: 'right' }}><CountUp value={r.n} format={unit(t('campaign.unit.persons'))} /></b>
                    <span className="num pf-note" style={{ textAlign: 'right' }}>{pct(r.n, f.sentCount)}%</span>
                  </div>
                ))}
              </div>
              <span className="pf-note">{t('analytics.funnelNote')}</span>
            </section>

            <section className="pf-card" aria-label={t('campaign.detail.compare')}>
              <div>
                <h2>{t('campaign.detail.compare')}</h2>
                <span className="pf-note">{t('analytics.controlNote')}</span>
              </div>
              {selId === 'all' ? (
                <p className="pf-note">{t('analytics.pickOneCampaign')}</p>
              ) : !ctrl ? (
                <p className="pf-note">{t('common.loading')}</p>
              ) : ctrl.error || !ctrl.value ? (
                <p className="pf-note">{t('prediction.loadFailed', { reason: ctrl.error ?? '' })}</p>
              ) : ctrl.value.controlCount === 0 ? (
                <p className="pf-note">{t('campaign.detail.noControl')}</p>
              ) : (
                <div className="pf-cmp" style={{ gridTemplateColumns: 'repeat(2,minmax(0,1fr))' }}>
                  <div><span>{t('campaign.detail.rateSent')}</span><b className="num">{rateText(ctrl.value.rebookedCount, ctrl.value.reachedCount) ?? '-'}</b><span className="num">{t('analytics.ratioPersons', { a: ctrl.value.rebookedCount, b: ctrl.value.reachedCount })}</span></div>
                  <div><span>{t('campaign.detail.rateControl')}</span><b className="num">{rateText(ctrl.value.controlRebookedCount, ctrl.value.controlCount) ?? '-'}</b><span className="num">{t('analytics.ratioPersons', { a: ctrl.value.controlRebookedCount, b: ctrl.value.controlCount })}</span></div>
                  <div><span>{t('campaign.detail.cycleSent')}</span><b className="num">{rateText(ctrl.value.reachedCycleRevisitedCount, ctrl.value.reachedCycleCount) ?? '-'}</b><span className="num">{t('analytics.ratioPersons', { a: ctrl.value.reachedCycleRevisitedCount, b: ctrl.value.reachedCycleCount })}</span></div>
                  <div><span>{t('campaign.detail.cycleControl')}</span><b className="num">{rateText(ctrl.value.controlCycleRevisitedCount, ctrl.value.controlCycleCount) ?? '-'}</b><span className="num">{t('analytics.ratioPersons', { a: ctrl.value.controlCycleRevisitedCount, b: ctrl.value.controlCycleCount })}</span></div>
                </div>
              )}
              <span className="pf-note">{t('analytics.controlWhat')}</span>
            </section>
          </div>

          <section className="pf-card" aria-label={t('analytics.perCampaign')}>
            <h2>{t('analytics.perCampaign')}</h2>
            <div style={{ overflowX: 'auto' }}>
              <table className="pf-tbl">
                <thead>
                  <tr><th>{t('analytics.colCampaign')}</th><th>{t('campaign.detail.funnelSent')}</th><th>{t('campaign.detail.funnelReached')}</th><th>{t('campaign.detail.funnelRebooked')}</th><th>{t('campaign.detail.funnelRevisited')}</th><th>{t('campaign.detail.revenue')}</th></tr>
                </thead>
                <tbody>
                  {camps.map((x) => (
                    <tr key={x.id}>
                      <td>
                        <AppLink href={`/campaigns/detail?id=${x.id}`} style={{ color: '#221D1B', fontWeight: 600 }}>{x.name}</AppLink>
                        <div className="pf-note">{t('analytics.sentMeta', { at: x.sentAt ? formatDateTime(x.sentAt) : '', category: x.category ? CATEGORY_LABEL[x.category] : t('customer.msgKind.reconfirm') })}</div>
                      </td>
                      <td className="num">{x.sentCount}</td>
                      <td className="num">{x.reachedCount}</td>
                      <td className="num" style={{ color: '#7A4450', fontWeight: 600 }}>{x.rebookedCount} ({rateText(x.rebookedCount, x.reachedCount) ?? '-'})</td>
                      <td className="num">{x.revisitedCount}</td>
                      <td className="num">{formatWon(x.revenue)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr style={{ fontWeight: 600 }}>
                    <td>{t('analytics.totalRow', { n: campCur.data.total.campaignCount })}</td>
                    <td className="num">{campCur.data.total.sentCount}</td>
                    <td className="num">{campCur.data.total.reachedCount}</td>
                    <td className="num" style={{ color: '#7A4450' }}>{campCur.data.total.rebookedCount} ({rateText(campCur.data.total.rebookedCount, campCur.data.total.reachedCount) ?? '-'})</td>
                    <td className="num">{campCur.data.total.revisitedCount}</td>
                    <td className="num">{formatWon(campCur.data.total.revenue)}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
            <span className="pf-note">
              {t('analytics.rateNote')}
              {campCur.data.totalElements > camps.length ? t('analytics.recentOnly', { total: campCur.data.totalElements, shown: camps.length }) : ''}
            </span>
            <AppLink href="/campaigns" className="pf-btn" style={{ color: '#fff' }}>{t('analytics.similar')}</AppLink>
          </section>
        </>
      )}

      <section className="pf-card" aria-label={t('analytics.attrLabel')}>
        <h2>{t('analytics.attrLabel')}</h2>
        <div className="pf-box">
          {t('analytics.attrIntro')}
          <ul>
            <li>{t('analytics.attr1')}</li>
            <li>{t('analytics.attr2')}</li>
            <li>{t('analytics.attr3')}</li>
          </ul>
        </div>
      </section>

      <p className="pf-foot">{t('analytics.foot')}</p>
    </div>
  );
}

export default function PerformancePage() {
  useStyleSheet('PerformancePage', styleText);
  return (
    <>
      <PinGate id="performance" title={t('shell.nav.performance')}>
        <Body />
      </PinGate>
    </>
  );
}
