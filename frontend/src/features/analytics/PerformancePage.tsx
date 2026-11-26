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
const won = (n: number) => n.toLocaleString() + '원';
const unit = (u: string) => (n: number) => n + u;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');

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
    { label: '발송', n: f.sentCount, c: '#D9AFA6' },
    { label: '도달', n: f.reachedCount, c: '#CC9A9E' },
    { label: '재예약', n: f.rebookedCount, c: '#A9707A' },
    { label: '재방문', n: f.revisitedCount, c: '#7A4450' },
  ];

  return (
    <div className="pf-wrap">
      {/* 1줄: 제목 · 기간 버튼 (고정). 2줄: 설명 · 직접 지정 날짜. 날짜가 없을 때도 2줄 높이를 유지해 아무것도 움직이지 않는다 (#424) */}
      <div className="pf-head">
        <h1 className="pf-h1">성과 분석</h1>
        <div className="pf-seg" role="group" aria-label="기간">
          {PERIODS.map(([p, label]) => (
            <button type="button" key={p} aria-pressed={period === p} onClick={() => setPeriod(p)}>{label}</button>
          ))}
        </div>
        <p className="pf-sub">기간별 매장 지표와 예측 정확도, 캠페인 발송 이후의 재예약 · 재방문을 확인하세요.</p>
        <div className="pf-range">
          {period === 'CUSTOM' ? (
            <>
              <input type="date" className="inp" aria-label="시작일" value={from} onChange={(e) => setFrom(e.target.value)} />~
              <input type="date" className="inp" aria-label="종료일" value={to} onChange={(e) => setTo(e.target.value)} />
            </>
          ) : null}
        </div>
      </div>

      <section className="pf-kpis pf-card" aria-label="매장 기본 지표">
        {!query ? (
          <p className="pf-note" style={{ padding: '12px 24px' }}>시작일과 종료일을 골라 주세요. 시작일은 종료일보다 늦을 수 없어요.</p>
        ) : cur?.error ? (
          <p className="pf-note" style={{ padding: '12px 24px' }}>불러오지 못했어요. {cur.error}</p>
        ) : !cur?.summary || !cur.accuracy ? (
          <p className="pf-note" style={{ padding: '12px 24px' }}>불러오는 중…</p>
        ) : (
          <>
            <div className="pf-kpi"><span>매출</span><b className="num"><CountUp value={cur.summary.revenue} format={won} /></b><em className="num">{cur.summary.from} ~ {cur.summary.to}</em></div>
            <div className="pf-kpi"><span>방문 수</span><b className="num"><CountUp value={cur.summary.visitCount} format={unit('건')} /></b><em>가져온 방문 제외</em></div>
            <div className="pf-kpi"><span>신규 고객</span><b className="num"><CountUp value={cur.summary.newCustomerCount} format={unit('명')} /></b><em>첫 방문일 기준</em></div>
            <div className="pf-kpi">
              <span>예측 적중률</span>
              <b className="num">{rateText(cur.accuracy.hitCount, cur.accuracy.evaluatedCount) ?? '-'}</b>
              <em className="num">
                평가 {cur.accuracy.evaluatedCount}건 · 평균 오차 {cur.accuracy.averageErrorDays === null ? '-' : `${cur.accuracy.averageErrorDays}일`}
              </em>
            </div>
          </>
        )}
      </section>

      <section className="pf-card" aria-label="주기 내 재방문율">
        <div>
          <h2>주기 내 재방문율 (시술 카테고리별)</h2>
          <span className="pf-note">이 기간에 이탈 신호 기간이 끝난 예측 중, 그 안에 다시 방문한 비율</span>
        </div>
        {!cur?.summary ? (
          <p className="pf-note">불러오는 중…</p>
        ) : !cur.summary.cycleRevisits ? (
          <p className="pf-note">서버 계산 준비 중이에요. 준비되면 여기에 카테고리별로 보여요.</p>
        ) : (
          <div>
            {cur.summary.cycleRevisits.map((r) => {
              const rate = rateText(r.revisitedCount, r.endedCount);
              return (
                <div className="pf-frow" key={r.category} style={{ gridTemplateColumns: '110px 1fr 60px 84px' }} onMouseEnter={() => setHover('r' + r.category)} onMouseLeave={() => setHover('')}>
                  <span style={{ fontSize: 13, color: '#4A3F3B' }}>{CATEGORY_LABEL[r.category] ?? r.category}</span>
                  <Bar w={pct(r.revisitedCount, r.endedCount)} color="#7A4450" grown={grown} show={hover === 'r' + r.category} tip={<>{CATEGORY_LABEL[r.category]} <b>{rate ?? '-'}</b> · {r.revisitedCount}/{r.endedCount}명</>} />
                  <b className="num" style={{ textAlign: 'right' }}>{rate ?? '-'}</b>
                  <span className="num pf-note" style={{ textAlign: 'right' }}>{r.revisitedCount}/{r.endedCount}명</span>
                </div>
              );
            })}
          </div>
        )}
      </section>

      <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
        <h2 style={{ fontSize: 19, fontWeight: 600 }}>캠페인 성과</h2>
        {camps && camps.length > 0 ? (
          <div className="pf-seg" role="group" aria-label="캠페인 선택">
            <button type="button" aria-pressed={selId === 'all'} onClick={() => setSel('all')}>전체</button>
            {camps.map((x) => (
              <button type="button" key={x.id} aria-pressed={selId === x.id} onClick={() => setSel(x.id)}>{x.name}</button>
            ))}
          </div>
        ) : null}
      </div>

      {!query ? (
        <p className="pf-note">기간을 정하면 그 기간에 발송한 캠페인의 성과가 보여요.</p>
      ) : campCur?.error ? (
        <p className="pf-note">캠페인 성과를 불러오지 못했어요. {campCur.error}</p>
      ) : !camps || !campCur?.data ? (
        <p className="pf-note">불러오는 중…</p>
      ) : camps.length === 0 ? (
        <p className="pf-note">이 기간에 발송한 캠페인이 없어요.</p>
      ) : (
        <>
          <div className="pf-two">
            <section className="pf-card" aria-label="전환 퍼널">
              <div>
                <h2>발송에서 재방문까지</h2>
                <span className="pf-note">발송 → 도달 → 재예약 → 재방문 · {selId === 'all' ? `캠페인 ${campCur.data.total.campaignCount}개 합계, 여러 캠페인을 받은 고객도 한 번만 집계` : '고객은 캠페인별 1회 집계'}</span>
              </div>
              <div>
                {funnelRows.map((r) => (
                  <div className="pf-frow" key={r.label} onMouseEnter={() => setHover('f' + r.label)} onMouseLeave={() => setHover('')}>
                    <span style={{ fontSize: 13, color: '#4A3F3B' }}>{r.label}</span>
                    <Bar w={pct(r.n, f.sentCount)} color={r.c} grown={grown} show={hover === 'f' + r.label} tip={<>{r.label} <b>{r.n}명</b> · 발송 대비 {pct(r.n, f.sentCount)}%</>} />
                    <b className="num" style={{ textAlign: 'right' }}><CountUp value={r.n} format={unit('명')} /></b>
                    <span className="num pf-note" style={{ textAlign: 'right' }}>{pct(r.n, f.sentCount)}%</span>
                  </div>
                ))}
              </div>
              <span className="pf-note">전환율은 발송 인원 기준이에요. 대조군은 넣지 않아요.</span>
            </section>

            <section className="pf-card" aria-label="대조군 비교">
              <div>
                <h2>대조군 비교</h2>
                <span className="pf-note">발송군과 발송하지 않은 대조군의 재예약률 · 주기 내 재방문율</span>
              </div>
              {selId === 'all' ? (
                <p className="pf-note">위에서 캠페인을 하나 고르면 대조군 비교가 보여요.</p>
              ) : !ctrl ? (
                <p className="pf-note">불러오는 중…</p>
              ) : ctrl.error || !ctrl.value ? (
                <p className="pf-note">불러오지 못했어요. {ctrl.error}</p>
              ) : ctrl.value.controlCount === 0 ? (
                <p className="pf-note">대조군이 없는 캠페인이라 비교 대상이 아니에요.</p>
              ) : (
                <div className="pf-cmp" style={{ gridTemplateColumns: 'repeat(2,minmax(0,1fr))' }}>
                  <div><span>발송군 재예약률</span><b className="num">{rateText(ctrl.value.rebookedCount, ctrl.value.reachedCount) ?? '-'}</b><span className="num">{ctrl.value.rebookedCount}/{ctrl.value.reachedCount}명</span></div>
                  <div><span>대조군 재예약률</span><b className="num">{rateText(ctrl.value.controlRebookedCount, ctrl.value.controlCount) ?? '-'}</b><span className="num">{ctrl.value.controlRebookedCount}/{ctrl.value.controlCount}명</span></div>
                  <div><span>발송군 주기 내 재방문율</span><b className="num">{rateText(ctrl.value.reachedCycleRevisitedCount, ctrl.value.reachedCycleCount) ?? '-'}</b><span className="num">{ctrl.value.reachedCycleRevisitedCount}/{ctrl.value.reachedCycleCount}명</span></div>
                  <div><span>대조군 주기 내 재방문율</span><b className="num">{rateText(ctrl.value.controlCycleRevisitedCount, ctrl.value.controlCycleCount) ?? '-'}</b><span className="num">{ctrl.value.controlCycleRevisitedCount}/{ctrl.value.controlCycleCount}명</span></div>
                </div>
              )}
              <span className="pf-note">대조군은 대상 중 무작위로 발송하지 않은 고객이에요. 인원이 적으면 차이를 단정하기 어려워요.</span>
            </section>
          </div>

          <section className="pf-card" aria-label="캠페인별 성과">
            <h2>캠페인별 성과</h2>
            <div style={{ overflowX: 'auto' }}>
              <table className="pf-tbl">
                <thead>
                  <tr><th>캠페인</th><th>발송</th><th>도달</th><th>재예약</th><th>재방문</th><th>연결 매출</th></tr>
                </thead>
                <tbody>
                  {camps.map((x) => (
                    <tr key={x.id}>
                      <td>
                        <AppLink href={`/campaigns/detail?id=${x.id}`} style={{ color: '#221D1B', fontWeight: 600 }}>{x.name}</AppLink>
                        <div className="pf-note">{x.sentAt ? formatDateTime(x.sentAt) : ''} 발송 · {x.category ? CATEGORY_LABEL[x.category] : '동의 재확인 안내'}</div>
                      </td>
                      <td className="num">{x.sentCount}</td>
                      <td className="num">{x.reachedCount}</td>
                      <td className="num" style={{ color: '#7A4450', fontWeight: 600 }}>{x.rebookedCount} ({rateText(x.rebookedCount, x.reachedCount) ?? '-'})</td>
                      <td className="num">{x.revisitedCount}</td>
                      <td className="num">{won(x.revenue)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr style={{ fontWeight: 600 }}>
                    <td>전체 합계 ({campCur.data.total.campaignCount}개)</td>
                    <td className="num">{campCur.data.total.sentCount}</td>
                    <td className="num">{campCur.data.total.reachedCount}</td>
                    <td className="num" style={{ color: '#7A4450' }}>{campCur.data.total.rebookedCount} ({rateText(campCur.data.total.rebookedCount, campCur.data.total.reachedCount) ?? '-'})</td>
                    <td className="num">{campCur.data.total.revisitedCount}</td>
                    <td className="num">{won(campCur.data.total.revenue)}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
            <span className="pf-note">
              재예약 비율은 도달 대비예요. 한 고객이나 방문이 여러 캠페인에 들어가면 캠페인 줄에는 각각 세고, 전체 합계에는 한 번만 세요. 그래서 줄을 더한 값과 합계가 다를 수 있어요.
              {campCur.data.totalElements > camps.length ? ` 기간 안 캠페인 ${campCur.data.totalElements}개 중 최근 발송한 ${camps.length}개만 표시해요 (합계는 전체).` : ''}
            </span>
            <AppLink href="/campaigns" className="pf-btn" style={{ color: '#fff' }}>유사 캠페인 만들기</AppLink>
          </section>
        </>
      )}

      <section className="pf-card" aria-label="귀속 규칙">
        <h2>귀속 규칙</h2>
        <div className="pf-box">
          캠페인 성과로 인정하는 기준이에요.
          <ul>
            <li>귀속 기간: 발송 후 기본 14일 (매장 설정에서 바꿀 수 있어요)</li>
            <li>같은 시술 카테고리의 예약만 인정</li>
            <li>발송에 성공한 고객만 인정 (대조군 · 실패 제외)</li>
          </ul>
        </div>
      </section>

      <p className="pf-foot">모의 데이터로 생성한 결과값이에요. 발송 후 귀속 기간이 지나지 않은 캠페인은 진행 중 성과예요.</p>
    </div>
  );
}

export default function PerformancePage() {
  useStyleSheet('PerformancePage', styleText);
  return (
    <>
      <PinGate id="performance" title="성과 분석">
        <Body />
      </PinGate>
    </>
  );
}
