import { useEffect, useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { CountUp } from '@/components/common/CountUp';
import { toast } from '@/components/common/Toast';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api, type Page } from '@/lib/api';
import { HANDLING, NEXT_ACTION_LABEL, STATUS_TABS, nextMark, type HandlingMark, type InsightStatus } from '@/lib/insights';
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
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');

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
    Promise.all(STATUS_TABS.map((t) => api<Page<Row>>(`/api/insights?status=${t.status}&page=0&size=1`, { silent: true }).then((p) => [t.status, p.totalElements] as const)))
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
  const total = counts ? STATUS_TABS.reduce((a, t) => a + counts[t.status], 0) : null;

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
      toast(`더 불러오지 못했어요. ${errorText(err)}`, 'warn');
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
      toast(next ? `${r.name} 님을 「${HANDLING.find(([m]) => m === next)?.[1]}」로 표시했어요` : `${r.name} 님의 처리 표시를 지웠어요`);
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
            <h1 className="h1">재방문 관리</h1>
            <p className="sub">왜 관리가 필요한지 이해하고, 고객에게 맞는 다음 행동을 결정하세요.</p>
          </div>
          <AppLink href="/campaigns" className="btn">캠페인 관리</AppLink>
        </header>

        <section className="card summary" aria-label="관리 대상 요약">
          <div className="sum-item">
            <span className="meta">관리 대상</span>
            <b className="num">{total === null ? '-' : <CountUp value={total} format={(n) => n + '명'} />}</b>
          </div>
          {STATUS_TABS.map((t) => (
            <div className="sum-item" key={t.status}>
              <span className="meta">{t.label}</span>
              <b className="num">{counts ? <CountUp value={counts[t.status]} format={(n) => n + '명'} /> : '-'}</b>
            </div>
          ))}
        </section>

        <div className="col" style={{ gap: 12, minWidth: 0 }}>
          <div className="toolbar">
            <div className="tabs" role="tablist" aria-label="관리 대상">
              {STATUS_TABS.map((t) => (
                <button type="button" role="tab" aria-selected={tab === t.status} onClick={() => setTab(t.status)} key={t.status}>
                  {t.label} <span className="num">{counts ? counts[t.status] : ''}</span>
                </button>
              ))}
            </div>
            <button type="button" className="rule-toggle" aria-expanded={showRule} onClick={() => setShowRule(!showRule)}>
              우선순위 규칙 {showRule ? '닫기' : '보기'}
            </button>
          </div>

          {showRule && (
            <section className="card rules">
              <ol>
                <li>경과 비율 높은 순 — (오늘 − 예측 종료일) ÷ 이탈 신호 기간</li>
                <li>마케팅 수신 동의 고객 먼저</li>
                <li>최근 6개월 결제 합계 높은 순</li>
              </ol>
              <p className="meta">다음 예약이 있는 고객과 「관리 종료」로 표시한 고객은 이 목록에서 빠져요. 수신 동의가 없는 고객에게는 캠페인을 보낼 수 없어요.</p>
            </section>
          )}

          <section className="card list" aria-label="관리 대상 고객">
            <div className="irow ihead" aria-hidden="true">
              <span></span>
              <span>고객 · 관리 상태</span>
              <span>예상 방문 기간</span>
              <span>마지막 시술 · 경과</span>
              <span>다음 액션</span>
              <span>처리 표시</span>
            </div>
            {!cur ? (
              <p className="meta list-foot">불러오는 중…</p>
            ) : cur.error ? (
              <p className="meta list-foot">불러오지 못했어요. {cur.error}</p>
            ) : cur.rows.length === 0 ? (
              <p className="meta list-foot">이 상태의 관리 대상이 없어요.</p>
            ) : (
              <div className="kp-fade" key={tab}>
                {cur.rows.map((r, i) => {
                  const t = STATUS_TABS.find((x) => x.status === r.status);
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
                          {t ? <span className={'chip ' + t.cls}>{t.label}</span> : null}
                        </span>
                        <span className="meta">{r.marketingConsent ? '수신 동의' : '수신 미동의'}</span>
                      </div>
                      <div className="cell">
                        <span className="lbl-m">예상 방문 기간</span>
                        <b className="num">
                          {md(r.startDate)} ~ {md(r.endDate)}
                        </b>
                        <span className="meta">{CATEGORY_LABEL[r.category]}</span>
                      </div>
                      <div className="cell">
                        <span className="lbl-m">마지막 시술 · 경과</span>
                        <b className="num">{md(r.lastServiceDate)}</b>
                        <span className="meta num">{r.elapsedDays}일 경과</span>
                      </div>
                      <div className="cell">
                        <span className="lbl-m">다음 액션</span>
                        {r.nextAction && r.nextAction !== 'REQUEST_CONSENT' ? (
                          // 이 고객 · 카테고리로 새 캠페인을 시작하고, 다음 액션과 같은 용도의 템플릿을 추천한다 (FR-PRD-06 · FR-CMP-01)
                          <AppLink href={`/campaigns/new?${new URLSearchParams({ customerIds: String(r.customerId), category: r.category, purpose: r.nextAction })}`} className="btn btn-p btn-sm">
                            {NEXT_ACTION_LABEL[r.nextAction] ?? r.nextAction} 캠페인
                          </AppLink>
                        ) : (
                          <span className="meta">{r.nextAction ? NEXT_ACTION_LABEL.REQUEST_CONSENT : '없음 (수신 거부)'}</span>
                        )}
                      </div>
                      <div className="acts">
                        {HANDLING.map(([m, label]) => (
                          <button
                            key={m}
                            type="button"
                            className="btn btn-sm"
                            aria-pressed={r.handlingMark === m}
                            disabled={busyKey === key}
                            style={r.handlingMark === m ? { background: '#7A4450', borderColor: '#7A4450', color: '#fff' } : undefined}
                            onClick={() => void mark(r, m)}
                          >
                            {label}
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
                {cur.total}명 중 우선순위 상위 {cur.rows.length}명 표시 · 연락함 · 보류는 중복 연락을 막는 표시이고, 다음 방문이 생기면 자동으로 풀려요.
                {cur.rows.length < cur.total ? (
                  <button type="button" className="btn btn-sm" style={{ marginLeft: 8 }} disabled={moreTab === tab} onClick={() => void loadMore()}>
                    {moreTab === tab ? '불러오는 중…' : '더 보기'}
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
