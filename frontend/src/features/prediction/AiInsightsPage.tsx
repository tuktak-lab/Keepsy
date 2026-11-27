import { useEffect, useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { CountUp } from '@/components/common/CountUp';
import { toast } from '@/components/common/Toast';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api, type Page } from '@/lib/api';
import { t } from '@/lib/i18n';
import { HANDLING, STATUS_TABS, nextActionLabel, nextMark, type HandlingMark, type InsightStatus } from '@/lib/insights';
import { CATEGORY_LABEL, type Category } from '@/lib/menus';
import styleText from './AiInsightsPage.css?inline';

// 재방문 관리 (FR-PRD-05 · 06 · 07 · 09). 기능 키 INSIGHT (권한은 API가 검사).
// API (docs CONTRACTS 4장): GET /api/insights?status= (우선순위 순 페이지, 관리 종료 행은 서버가 뺀다),
// PUT /api/customers/{customerId}/predictions/{category}/handling (처리 표시, 다음 방문 때 서버가 자동 해제)

type Row = {
  customerId: number;
  name: string;
  category: Category;
  status: InsightStatus;
  lastServiceDate: string;
  elapsedDays: number;
  startDate: string;
  endDate: string;
  marketingConsent: boolean;
  nextAction: string | null;
  handlingMark: HandlingMark | null;
};

const SIZE = 20;
const md = (d: string) => `${Number(d.slice(5, 7))}.${d.slice(8, 10)}`;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : t('common.pin.tryLater'));

export default function AiInsightsPage() {
  useStyleSheet('AiInsightsPage', styleText);
  const [tab, setTab] = useState<InsightStatus>('DUE');
  const [counts, setCounts] = useState<Record<InsightStatus, number> | null>(null);
  const [list, setList] = useState<{ tab: InsightStatus; rows: Row[]; total: number; error?: string } | null>(null);
  const [moreTab, setMoreTab] = useState<InsightStatus | null>(null); // 어느 탭이 더 불러오는 중인지 (탭을 바꿔도 다른 탭의 버튼은 막지 않는다)
  const [busyKey, setBusyKey] = useState('');
  const [showRule, setShowRule] = useState(false);

  // 탭마다 인원 (요약 카드 · 탭 숫자)
  useEffect(() => {
    let cancelled = false;
    Promise.all(STATUS_TABS.map((tb) => api<Page<Row>>(`/api/insights?status=${tb.status}&page=0&size=1`, { silent: true }).then((p) => [tb.status, p.totalElements] as const)))
      .then((pairs) => !cancelled && setCounts(Object.fromEntries(pairs) as Record<InsightStatus, number>))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    api<Page<Row>>(`/api/insights?status=${tab}&page=0&size=${SIZE}`, { silent: true })
      .then((p) => !cancelled && setList({ tab, rows: p.items, total: p.totalElements }))
      .catch((err) => !cancelled && setList({ tab, rows: [], total: 0, error: errorText(err) }));
    return () => {
      cancelled = true;
    };
  }, [tab]);

  const cur = list?.tab === tab ? list : null;
  const total = counts ? STATUS_TABS.reduce((a, tb) => a + counts[tb.status], 0) : null;

  async function loadMore() {
    if (!cur || moreTab === tab) return; // 같은 탭에서 이미 불러오는 중이면 다시 부르지 않는다 (같은 페이지가 두 번 붙는 것 방지)
    const asked = tab;
    setMoreTab(asked);
    try {
      const p = await api<Page<Row>>(`/api/insights?status=${asked}&page=${Math.ceil(cur.rows.length / SIZE)}&size=${SIZE}`, { silent: true });
      // 요청한 탭이 아직 화면의 탭일 때만 붙인다. 그 사이 탭을 바꿨으면 이전 탭 데이터로 새 탭 목록을 덮지 않는다
      setList((l) => {
        if (!l || l.tab !== asked) return l;
        const have = new Set(l.rows.map((r) => `${r.customerId}-${r.category}`));
        return { ...l, rows: [...l.rows, ...p.items.filter((r) => !have.has(`${r.customerId}-${r.category}`))], total: p.totalElements };
      });
    } catch (err) {
      toast(t('prediction.loadMoreFailed', { reason: errorText(err) }), 'warn');
    } finally {
      setMoreTab((m) => (m === asked ? null : m));
    }
  }

  // 처리 표시 (FR-PRD-07): 같은 표시를 다시 누르면 지운다. 관리 종료는 서버가 목록에서 빼므로 화면에서도 뺀다
  async function mark(r: Row, clicked: HandlingMark) {
    const key = `${r.customerId}-${r.category}`;
    const next = nextMark(r.handlingMark, clicked);
    setBusyKey(key);
    try {
      await api(`/api/customers/${r.customerId}/predictions/${r.category}/handling`, { method: 'PUT', body: { mark: next }, silent: true });
      setList((l) => {
        if (!l) return l;
        if (next === 'CLOSED') return { ...l, rows: l.rows.filter((x) => x !== r), total: l.total - 1 };
        return { ...l, rows: l.rows.map((x) => (x === r ? { ...x, handlingMark: next } : x)) };
      });
      if (next === 'CLOSED') setCounts((c) => (c ? { ...c, [r.status]: c[r.status] - 1 } : c));
      toast(next ? t('prediction.marked', { name: r.name, mark: t(HANDLING.find(([m]) => m === next)![1]) }) : t('prediction.unmarked', { name: r.name }));
    } catch (err) {
      toast(errorText(err), 'warn');
    } finally {
      setBusyKey('');
    }
  }

  return (
    <>
      <div className="wrap">
        <header className="head">
          <div>
            <h1 className="h1">{t('prediction.title')}</h1>
            <p className="sub">{t('prediction.sub')}</p>
          </div>
          <AppLink href="/campaigns" className="btn">{t('shell.nav.campaigns')}</AppLink>
        </header>

        <section className="card summary" aria-label={t('prediction.summary.label')} data-guide="insights-summary">
          <div className="sum-item">
            <span className="meta">{t('prediction.summary.total')}</span>
            <b className="num">{total === null ? '-' : <CountUp value={total} format={(n) => t('common.persons', { n })} />}</b>
          </div>
          {STATUS_TABS.map((tb) => (
            <div className="sum-item" key={tb.status}>
              <span className="meta">{t(tb.labelKey)}</span>
              <b className="num">{counts ? <CountUp value={counts[tb.status]} format={(n) => t('common.persons', { n })} /> : '-'}</b>
            </div>
          ))}
        </section>

        <div className="col" style={{ gap: 12, minWidth: 0 }}>
          <div className="toolbar" data-guide="insights-tabs">
            <div className="tabs" role="tablist" aria-label={t('prediction.summary.total')}>
              {STATUS_TABS.map((tb) => (
                <button type="button" role="tab" aria-selected={tab === tb.status} onClick={() => setTab(tb.status)} key={tb.status}>
                  {t(tb.labelKey)} <span className="num">{counts ? counts[tb.status] : ''}</span>
                </button>
              ))}
            </div>
            <button type="button" className="rule-toggle" aria-expanded={showRule} onClick={() => setShowRule(!showRule)}>
              {showRule ? t('prediction.rule.hide') : t('prediction.rule.show')}
            </button>
          </div>

          {showRule && (
            <section className="card rules">
              <ol>
                <li>{t('prediction.rule.1')}</li>
                <li>{t('prediction.rule.2')}</li>
                <li>{t('prediction.rule.3')}</li>
              </ol>
              <p className="meta">{t('prediction.rule.note')}</p>
            </section>
          )}

          <section className="card list" aria-label={t('prediction.list.label')}>
            <div className="irow ihead" aria-hidden="true">
              <span></span>
              <span>{t('prediction.col.customer')}</span>
              <span>{t('prediction.col.period')}</span>
              <span>{t('prediction.col.last')}</span>
              <span>{t('prediction.col.action')}</span>
              <span>{t('prediction.col.handling')}</span>
            </div>
            {!cur ? (
              <p className="meta list-foot">{t('common.loading')}</p>
            ) : cur.error ? (
              <p className="meta list-foot">{t('prediction.loadFailed', { reason: cur.error })}</p>
            ) : cur.rows.length === 0 ? (
              <p className="meta list-foot">{t('prediction.empty')}</p>
            ) : (
              <div className="kp-fade" key={tab}>
                {cur.rows.map((r, i) => {
                  const stat = STATUS_TABS.find((x) => x.status === r.status);
                  const key = `${r.customerId}-${r.category}`;
                  return (
                    <article className="irow" key={key}>
                      <div className="thumb">
                        <span>{CATEGORY_LABEL[r.category]}</span>
                      </div>
                      <div className="c-name">
                        <span className="nm">
                          <span className="rank num">{i + 1}</span>
                          <AppLink href={`/customers/detail?id=${r.customerId}`} style={{ color: '#221D1B' }}>
                            <strong>{r.name}</strong>
                          </AppLink>
                          {stat ? <span className={'chip ' + stat.cls}>{t(stat.labelKey)}</span> : null}
                        </span>
                        <span className="meta">{r.marketingConsent ? t('common.consent') : t('common.noConsent')}</span>
                      </div>
                      <div className="cell">
                        <span className="lbl-m">{t('prediction.col.period')}</span>
                        <b className="num">
                          {md(r.startDate)} ~ {md(r.endDate)}
                        </b>
                        <span className="meta">{CATEGORY_LABEL[r.category]}</span>
                      </div>
                      <div className="cell">
                        <span className="lbl-m">{t('prediction.col.last')}</span>
                        <b className="num">{md(r.lastServiceDate)}</b>
                        <span className="meta num">{t('common.elapsedDays', { days: r.elapsedDays })}</span>
                      </div>
                      <div className="cell" data-guide={i === 0 ? 'insights-action' : undefined}>
                        <span className="lbl-m">{t('prediction.col.action')}</span>
                        {r.nextAction && r.nextAction !== 'REQUEST_CONSENT' ? (
                          // 이 고객 · 카테고리로 새 캠페인을 시작하고, 다음 액션과 같은 용도의 템플릿을 추천한다 (FR-PRD-06 · FR-CMP-01)
                          <AppLink href={`/campaigns/new?${new URLSearchParams({ customerIds: String(r.customerId), category: r.category, purpose: r.nextAction })}`} className="btn btn-p btn-sm">
                            {t('prediction.actionCampaign', { action: nextActionLabel(r.nextAction) })}
                          </AppLink>
                        ) : (
                          <span className="meta">{r.nextAction ? nextActionLabel('REQUEST_CONSENT') : t('prediction.noAction')}</span>
                        )}
                      </div>
                      <div className="acts" data-guide={i === 0 ? 'insights-handling' : undefined}>
                        {HANDLING.map(([m, labelKey]) => (
                          <button
                            key={m}
                            type="button"
                            // 선택된 표시는 공통 주 버튼(btn-p)으로. 유리 버튼 위에 색만 덮으면 흰 테두리 · 윗줄이 남아 어긋나 보인다
                            className={'btn btn-sm' + (r.handlingMark === m ? ' btn-p' : '')}
                            aria-pressed={r.handlingMark === m}
                            disabled={busyKey === key}
                            onClick={() => void mark(r, m)}
                          >
                            {t(labelKey)}
                          </button>
                        ))}
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
            {cur && !cur.error && cur.rows.length > 0 ? (
              <p className="meta list-foot">
                {t('prediction.foot', { total: cur.total, shown: cur.rows.length })}
                {cur.rows.length < cur.total ? (
                  <button type="button" className="btn btn-sm" style={{ marginLeft: 8 }} disabled={moreTab === tab} onClick={() => void loadMore()}>
                    {moreTab === tab ? t('common.loading') : t('prediction.more')}
                  </button>
                ) : null}
              </p>
            ) : null}
          </section>
        </div>
      </div>
    </>
  );
}
