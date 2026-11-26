import { Fragment, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api, type Page } from '@/lib/api';
import { CAMPAIGN_STATUS_LABEL, SEND_STATUS_LABEL, rateText } from '@/lib/campaignResult';
import { formatDateTime, formatWon } from '@/lib/format';
import { CATEGORY_LABEL, type Category } from '@/lib/menus';
import styleText from '../reservation/SchedulePage.css?inline';

// 캠페인 결과 (FR-ANL-01 · 04 · 09, FR-CMP-10). 경로: /campaigns/detail?id=
// API (docs CONTRACTS 4장): GET /api/campaigns/{id} (고객별 발송 결과) · …/funnel · …/control-comparison (CAMPAIGN_VIEW), …/revenue (SALES_ANALYTICS)
// 결과는 모의 데이터로 만든 값임을 화면에 밝힌다 (E9).

type Message = {
  customerId: number;
  customerName: string | null;
  customerDeleted: boolean;
  phone: string | null;
  controlGroup: boolean;
  sendStatus: string | null;
  failureReason: string | null;
  resultAt: string | null;
};
type Campaign = {
  id: number;
  name: string;
  category: Category | null;
  status: string;
  targetCount: number;
  controlCount: number;
  controlPercent: number;
  scheduledAt: string | null;
  sentAt: string | null;
  reviewedBy: { id: number; name: string } | null;
  messages: Page<Message>;
};
type Funnel = { sentCount: number; reachedCount: number; rebookedCount: number; revisitedCount: number };
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
type Loaded = { id: number; campaign?: Campaign; funnel?: Funnel; control?: Control; error?: string };

const errorText = (err: unknown) => (err instanceof ApiError ? err.message : '잠시 후 다시 열어 주세요');

export default function CampaignDetailPage() {
  useStyleSheet('SchedulePage', styleText);
  const id = Number(useSearchParams().get('id'));
  const validId = Number.isInteger(id) && id > 0;

  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreError, setMoreError] = useState('');
  // 연결 매출은 「매출 · 성과 분석」 기능 키라 따로 읽는다. PIN을 취소하면 이 칸만 비운다
  const [revenue, setRevenue] = useState<{ id: number; value: number | null; error?: string } | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (!validId) return;
    let cancelled = false;
    Promise.all([
      api<Campaign>(`/api/campaigns/${id}?page=0&size=20`, { silent: true }),
      api<Funnel>(`/api/campaigns/${id}/funnel`, { silent: true }),
      api<Control>(`/api/campaigns/${id}/control-comparison`, { silent: true }),
    ])
      .then(([campaign, funnel, control]) => {
        if (cancelled) return;
        setLoaded({ id, campaign, funnel, control });
        setMessages(campaign.messages.items);
      })
      .catch((err) => !cancelled && setLoaded({ id, error: err instanceof ApiError && err.code === 'not_found' ? 'notfound' : errorText(err) }));
    api<{ revenue: number }>(`/api/campaigns/${id}/revenue`, { silent: true })
      .then((r) => !cancelled && setRevenue({ id, value: r.revenue }))
      .catch((err) => !cancelled && setRevenue({ id, value: null, error: errorText(err) }));
    return () => {
      cancelled = true;
    };
  }, [id, validId, reload]);

  const current = loaded?.id === id ? loaded : null;
  const c = current?.campaign;
  const rev = revenue?.id === id ? revenue : null;

  async function loadMore() {
    if (!c) return;
    setMoreBusy(true);
    setMoreError('');
    try {
      const next = await api<Campaign>(`/api/campaigns/${id}?page=${Math.ceil(messages.length / 20)}&size=20`, { silent: true });
      setMessages((cur) => [...cur, ...next.messages.items]);
    } catch (err) {
      // 실패를 흘리지 않고 버튼 옆에 사유를 보인다 (#344). 이미 받은 결과는 그대로 둔다
      setMoreError(errorText(err));
    } finally {
      setMoreBusy(false);
    }
  }

  return (
    <>
      <div className="wrap">
        <header className="head">
          <div>
            <h1 className="h1">{c?.name ?? '캠페인 결과'}</h1>
            <p className="sub">
              <AppLink href="/campaigns" className="p-link">
                ‹ 캠페인 목록으로
              </AppLink>
            </p>
          </div>
        </header>

        <section className="card detail-card" aria-label="캠페인 결과">
          {!validId || current?.error === 'notfound' ? (
            <p className="meta">찾을 수 없는 캠페인이에요. 캠페인 목록에서 다시 골라 주세요.</p>
          ) : current?.error ? (
            <p className="meta">
              불러오지 못했어요. {current.error}{' '}
              <button type="button" className="btn btn-sm" onClick={() => setReload((n) => n + 1)}>
                다시 시도
              </button>
            </p>
          ) : !c || !current?.funnel || !current.control ? (
            <p className="meta">불러오는 중…</p>
          ) : (
            <div className="detail">
              <dl className="d-dl">
                <dt>상태</dt>
                <dd className="strong">{CAMPAIGN_STATUS_LABEL[c.status] ?? c.status}</dd>
                <dt>카테고리</dt>
                <dd>{c.category ? CATEGORY_LABEL[c.category] : '동의 재확인 안내'}</dd>
                <dt>발송</dt>
                <dd className="num">{c.sentAt ? formatDateTime(c.sentAt) : c.scheduledAt ? `${formatDateTime(c.scheduledAt)} 예약` : '아직 발송 전'}</dd>
                <dt>대상</dt>
                <dd className="num">
                  {c.targetCount}명 (대조군 {c.controlCount}명 · {c.controlPercent}%)
                </dd>
                <dt>검토</dt>
                <dd>{c.reviewedBy?.name ?? '검토 전'}</dd>
              </dl>

              <div className="box" role="note">
                <p>아래 결과는 모의 데이터로 생성한 결과값이에요. 실제 발송 · 예약 결과가 아니에요.</p>
              </div>

              <Funnel f={current.funnel} />
              <ControlComparison k={current.control} />

              <div>
                <span className="lbl">연결 매출</span>
                {!rev ? (
                  <p className="meta">불러오는 중…</p>
                ) : rev.value === null ? (
                  <p className="meta">매출은 「매출 · 성과 분석」 권한(PIN)이 있어야 볼 수 있어요. {rev.error}</p>
                ) : (
                  <p className="num" style={{ fontSize: 20, fontWeight: 600 }}>
                    {formatWon(rev.value)}
                  </p>
                )}
                <p className="meta">이 캠페인이 귀속된 방문의 결제 합계예요. 같은 방문이 다른 캠페인에도 귀속되면 그쪽에도 전액 들어가요.</p>
              </div>

              <div>
                <span className="lbl">
                  고객별 발송 결과 <span className="meta num">({c.messages.totalElements}명)</span>
                </span>
                {messages.length === 0 ? (
                  <p className="meta">발송을 시작하기 전이라 결과가 없어요.</p>
                ) : (
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13.5 }}>
                    <thead>
                      <tr className="meta" style={{ textAlign: 'left' }}>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>고객</th>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>수신 번호</th>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>결과</th>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>시각</th>
                      </tr>
                    </thead>
                    <tbody>
                      {messages.map((m) => (
                        <tr key={m.customerId} style={{ borderTop: '1px solid #EFE8E2' }}>
                          <td style={{ padding: '8px 4px' }}>
                            {m.customerDeleted ? (
                              <span className="meta">삭제된 고객</span>
                            ) : (
                              <AppLink href={`/customers/detail?id=${m.customerId}`} className="p-link">
                                {m.customerName ?? '이름 없음'}
                              </AppLink>
                            )}
                          </td>
                          <td className="num" style={{ padding: '8px 4px' }}>
                            {m.phone ?? '-'}
                          </td>
                          <td style={{ padding: '8px 4px' }}>
                            {m.controlGroup ? '대조군 (발송 안 함)' : (SEND_STATUS_LABEL[m.sendStatus ?? ''] ?? '-')}
                            {m.failureReason ? <span className="meta"> · {m.failureReason}</span> : null}
                          </td>
                          <td className="num meta" style={{ padding: '8px 4px' }}>
                            {m.resultAt ? formatDateTime(m.resultAt) : '-'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                {messages.length < c.messages.totalElements ? (
                  <button type="button" className="btn btn-sm" style={{ marginTop: 8 }} disabled={moreBusy} onClick={() => void loadMore()}>
                    {moreBusy ? '불러오는 중…' : '더 보기'}
                  </button>
                ) : null}
                {moreError && (
                  <p className="meta" role="alert" style={{ color: '#8A2F3F', marginTop: 6 }}>
                    {moreError}
                  </p>
                )}
                <p className="meta" style={{ marginTop: 6 }}>
                  고객 이름을 누르면 고객 상세에서 이 캠페인 뒤의 예약 · 방문 · 결제 기록을 볼 수 있어요.
                </p>
              </div>
            </div>
          )}
        </section>
      </div>
    </>
  );
}

/** 발송 → 도달 → 재예약 → 재방문. 전환율은 앞 단계 대비, 막대는 발송 대비 (FR-ANL-01) */
function Funnel({ f }: { f: Funnel }) {
  const steps: [string, number, number][] = [
    ['발송', f.sentCount, f.sentCount],
    ['도달', f.reachedCount, f.sentCount],
    ['재예약', f.rebookedCount, f.reachedCount],
    ['재방문', f.revisitedCount, f.rebookedCount],
  ];
  return (
    <div>
      <span className="lbl">전환 퍼널</span>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {steps.map(([label, n, base], i) => {
          const rate = i > 0 ? rateText(n, base) : null;
          return (
            <li key={label} style={{ display: 'grid', gridTemplateColumns: '64px 1fr 120px', alignItems: 'center', gap: 10 }}>
              <span>{label}</span>
              <span style={{ height: 10, borderRadius: 99, background: '#EFE8E2', overflow: 'hidden' }}>
                <span style={{ display: 'block', height: '100%', width: `${f.sentCount > 0 ? (n / f.sentCount) * 100 : 0}%`, background: '#7A4450' }} />
              </span>
              <span className="num">
                <b>{n}명</b> {rate ? <span className="meta">· {rate}</span> : null}
              </span>
            </li>
          );
        })}
      </ul>
      <p className="meta" style={{ marginTop: 6 }}>
        전환율은 앞 단계 대비예요. 재예약 전환율(재예약 ÷ 도달)이 캠페인 성과 지표예요. 대조군은 퍼널에 넣지 않아요.
      </p>
    </div>
  );
}

/** 발송군 vs 대조군 재예약률 · 주기 내 재방문율 (FR-ANL-09, E2, F36 · F57). 분모가 0인 비율은 '-' */
function ControlComparison({ k }: { k: Control }) {
  const rows: [string, number, number][] = [
    ['발송군 재예약률', k.rebookedCount, k.reachedCount],
    ['대조군 재예약률', k.controlRebookedCount, k.controlCount],
    ['발송군 주기 내 재방문율', k.reachedCycleRevisitedCount, k.reachedCycleCount],
    ['대조군 주기 내 재방문율', k.controlCycleRevisitedCount, k.controlCycleCount],
  ];
  return (
    <div>
      <span className="lbl">대조군 비교</span>
      {k.controlCount === 0 ? (
        <p className="meta">대조군이 없는 캠페인이라 비교 대상이 아니에요.</p>
      ) : (
        <dl className="d-dl" style={{ gridTemplateColumns: '160px 1fr' }}>
          {rows.map(([label, n, base]) => (
            <Fragment key={label}>
              <dt>{label}</dt>
              <dd className="num">
                <b>{rateText(n, base) ?? '-'}</b> <span className="meta">({n} / {base}명)</span>
              </dd>
            </Fragment>
          ))}
        </dl>
      )}
      <p className="meta" style={{ marginTop: 6 }}>
        주기 내 재방문율은 발송 시각의 예측으로 셀 수 있는 고객만 분모에 넣어요. 예측이 없거나 셀 수 없는 고객은 빠져요.
      </p>
    </div>
  );
}
