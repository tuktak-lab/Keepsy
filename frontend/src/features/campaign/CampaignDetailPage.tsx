import { Fragment, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api, type Page } from '@/lib/api';
import { CAMPAIGN_STATUS_LABEL, SEND_STATUS_LABEL, rateText, revenuePath } from '@/lib/campaignResult';
import { RESERVATION_STATUS_LABEL, dotDate } from '@/lib/customerDetail';
import { formatDateTime, formatWon } from '@/lib/format';
import { CATEGORY_LABEL, type Category } from '@/lib/menus';
import type { ReservationStatus } from '../customer/types';
import styleText from '../reservation/SchedulePage.css?inline';
import { t } from '@/lib/i18n';

// 캠페인 결과 (FR-ANL-01 · 04 · 09, FR-CMP-10). 경로: /campaigns/detail?id=
// API (docs CONTRACTS 4장): GET /api/campaigns/{id} (고객별 발송 결과 · 귀속 예약 · 방문) · …/funnel · …/control-comparison (CAMPAIGN_VIEW), …/revenue (SALES_ANALYTICS)
// 고객별 연결 기록 (FR-ANL-04): 예약 · 방문은 캠페인 조회 권한으로, 금액은 매출 분석 권한으로 따로 받는다 (명세서 2장). 매출이 잠기면 그 열만 비운다
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
  /** 발송 기록 ID. 대조군이면 null */
  messageId: number | null;
  reservations: { id: number; startAt: string; status: ReservationStatus }[];
  visits: { id: number; visitDate: string }[];
};
type Revenue = { revenue: number; customers?: { customerId: number; revenue: number }[] };
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

const errorText = (err: unknown) => (err instanceof ApiError ? err.message : t('campaign.tryReopen'));

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
  // 고객별 연결 매출 (FR-ANL-04). 받은 페이지만큼 쌓는다
  const [byCustomer, setByCustomer] = useState<{ id: number; won: Map<number, number> } | null>(null);
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
        // 연결 매출은 첫 페이지 고객의 값과 함께 받는다
        return api<Revenue>(revenuePath(id, campaign.messages.items.map((m) => m.customerId)), { silent: true })
          .then((r) => {
            if (cancelled) return;
            setRevenue({ id, value: r.revenue });
            setByCustomer({ id, won: new Map((r.customers ?? []).map((x) => [x.customerId, x.revenue])) });
          })
          .catch((err) => !cancelled && setRevenue({ id, value: null, error: errorText(err) }));
      })
      .catch((err) => !cancelled && setLoaded({ id, error: err instanceof ApiError && err.code === 'not_found' ? 'notfound' : errorText(err) }));
    return () => {
      cancelled = true;
    };
  }, [id, validId, reload]);

  const current = loaded?.id === id ? loaded : null;
  const c = current?.campaign;
  const rev = revenue?.id === id ? revenue : null;
  const won = byCustomer?.id === id ? byCustomer.won : null;

  async function loadMore() {
    if (!c) return;
    setMoreBusy(true);
    setMoreError('');
    try {
      const next = await api<Campaign>(`/api/campaigns/${id}?page=${Math.ceil(messages.length / 20)}&size=20`, { silent: true });
      setMessages((cur) => [...cur, ...next.messages.items]);
      // 매출을 볼 수 있을 때만 새 페이지 고객의 연결 매출을 더 받는다. 실패하면 그 칸만 '-'로 둔다
      if (rev?.value != null && next.messages.items.length) {
        api<Revenue>(revenuePath(id, next.messages.items.map((m) => m.customerId)), { silent: true })
          .then((r) =>
            setByCustomer((cur) => {
              const map = new Map(cur?.id === id ? cur.won : []);
              (r.customers ?? []).forEach((x) => map.set(x.customerId, x.revenue));
              return { id, won: map };
            }),
          )
          .catch(() => undefined);
      }
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
            <h1 className="h1">{c?.name ?? t('campaign.detail.defaultTitle')}</h1>
            <p className="sub">
              <AppLink href="/campaigns" className="p-link">
                {t('campaign.detail.toList')}
              </AppLink>
            </p>
          </div>
        </header>

        <section className="card detail-card wide" aria-label={t('campaign.detail.defaultTitle')}>
          {!validId || current?.error === 'notfound' ? (
            <p className="meta">{t('campaign.detail.notFound')}</p>
          ) : current?.error ? (
            <p className="meta">
              {t('prediction.loadFailed', { reason: current.error })}{' '}
              <button type="button" className="btn btn-sm" onClick={() => setReload((n) => n + 1)}>
                {t('common.retry')}
              </button>
            </p>
          ) : !c || !current?.funnel || !current.control ? (
            <p className="meta">{t('common.loading')}</p>
          ) : (
            <div className="detail">
              <dl className="d-dl">
                <dt>{t('campaign.detail.status')}</dt>
                <dd className="strong">{CAMPAIGN_STATUS_LABEL[c.status] ?? c.status}</dd>
                <dt>{t('campaign.detail.category')}</dt>
                <dd>{c.category ? CATEGORY_LABEL[c.category] : t('customer.msgKind.reconfirm')}</dd>
                <dt>{t('campaign.detail.sent')}</dt>
                <dd className="num">{c.sentAt ? formatDateTime(c.sentAt) : c.scheduledAt ? t('campaign.list.scheduledAt', { at: formatDateTime(c.scheduledAt) }) : t('campaign.detail.notSentYet')}</dd>
                <dt>{t('campaign.detail.targets')}</dt>
                <dd className="num">
                  {t('campaign.detail.targetLine', { n: c.targetCount, control: c.controlCount, percent: c.controlPercent })}
                </dd>
                <dt>{t('campaign.detail.review')}</dt>
                <dd>{c.reviewedBy?.name ?? t('campaign.detail.beforeReview')}</dd>
              </dl>

              <div className="box" role="note">
                <p>{t('campaign.detail.mockNote')}</p>
              </div>

              <Funnel f={current.funnel} />
              <ControlComparison k={current.control} />

              <div>
                <span className="lbl">{t('campaign.detail.revenue')}</span>
                {!rev ? (
                  <p className="meta">{t('common.loading')}</p>
                ) : rev.value === null ? (
                  <p className="meta">{t('campaign.detail.revenueLocked', { reason: rev.error ?? '' })}</p>
                ) : (
                  <p className="num" style={{ fontSize: 20, fontWeight: 600 }}>
                    {formatWon(rev.value)}
                  </p>
                )}
                <p className="meta">{t('campaign.detail.revenueNote')}</p>
              </div>

              <div>
                <span className="lbl">
                  {t('campaign.detail.byCustomer')} <span className="meta num">{t('campaign.detail.countParen', { n: c.messages.totalElements })}</span>
                </span>
                {messages.length === 0 ? (
                  <p className="meta">{t('campaign.detail.noResults')}</p>
                ) : (
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13.5 }}>
                    <thead>
                      <tr className="meta" style={{ textAlign: 'left' }}>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>{t('common.customer')}</th>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>{t('campaign.detail.colPhone')}</th>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>{t('campaign.detail.colResult')}</th>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>{t('campaign.detail.colTime')}</th>
                        <th style={{ padding: '6px 4px', fontWeight: 500 }}>{t('campaign.detail.colLinks')}</th>
                        <th style={{ padding: '6px 4px', fontWeight: 500, textAlign: 'right' }}>{t('campaign.detail.colRevenue')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {messages.map((m) => (
                        <tr key={m.customerId} style={{ borderTop: '1px solid #EFE8E2' }}>
                          <td style={{ padding: '8px 4px' }}>
                            {m.customerDeleted ? (
                              <span className="meta">{t('campaign.list.deletedCustomer')}</span>
                            ) : (
                              <AppLink href={`/customers/detail?id=${m.customerId}`} className="p-link">
                                {m.customerName ?? t('reservation.noName')}
                              </AppLink>
                            )}
                          </td>
                          <td className="num" style={{ padding: '8px 4px' }}>
                            {m.phone ?? '-'}
                          </td>
                          <td style={{ padding: '8px 4px' }}>
                            {m.controlGroup ? t('campaign.detail.control') : (SEND_STATUS_LABEL[m.sendStatus ?? ''] ?? '-')}
                            {m.failureReason ? <span className="meta"> · {m.failureReason}</span> : null}
                          </td>
                          <td className="num meta" style={{ padding: '8px 4px' }}>
                            {m.resultAt ? formatDateTime(m.resultAt) : '-'}
                          </td>
                          <td style={{ padding: '8px 4px' }}>
                            {m.reservations.length + m.visits.length === 0 ? (
                              <span className="meta">-</span>
                            ) : (
                              <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                                {m.reservations.map((r) => (
                                  <AppLink key={`r${r.id}`} href={`/reservations/detail?id=${r.id}`} className="p-link num">
                                    {t('customer.messages.resv', { date: formatDateTime(r.startAt), status: RESERVATION_STATUS_LABEL[r.status] })}
                                  </AppLink>
                                ))}
                                {m.visits.map((v) => (
                                  <span key={`v${v.id}`} className="num">
                                    {t('customer.messages.visit', { date: dotDate(v.visitDate) })}
                                  </span>
                                ))}
                              </span>
                            )}
                          </td>
                          <td className="num" style={{ padding: '8px 4px', textAlign: 'right' }}>
                            {m.messageId !== null && won?.has(m.customerId) ? formatWon(won.get(m.customerId)!) : '-'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                {messages.length < c.messages.totalElements ? (
                  <button type="button" className="btn btn-sm" style={{ marginTop: 8 }} disabled={moreBusy} onClick={() => void loadMore()}>
                    {moreBusy ? t('common.loading') : t('customer.moreBtn')}
                  </button>
                ) : null}
                {moreError && (
                  <p className="meta" role="alert" style={{ color: '#8A2F3F', marginTop: 6 }}>
                    {moreError}
                  </p>
                )}
                <p className="meta" style={{ marginTop: 6 }}>
                  {t('campaign.detail.clickNote')}
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
    [t('campaign.detail.funnelSent'), f.sentCount, f.sentCount],
    [t('campaign.detail.funnelReached'), f.reachedCount, f.sentCount],
    [t('campaign.detail.funnelRebooked'), f.rebookedCount, f.reachedCount],
    [t('campaign.detail.funnelRevisited'), f.revisitedCount, f.rebookedCount],
  ];
  return (
    <div>
      <span className="lbl">{t('campaign.detail.funnel')}</span>
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
                <b>{t('common.persons', { n })}</b> {rate ? <span className="meta">· {rate}</span> : null}
              </span>
            </li>
          );
        })}
      </ul>
      <p className="meta" style={{ marginTop: 6 }}>
        {t('campaign.detail.funnelNote')}
      </p>
    </div>
  );
}

/** 발송군 vs 대조군 재예약률 · 주기 내 재방문율 (FR-ANL-09, E2, F36 · F57). 분모가 0인 비율은 '-' */
function ControlComparison({ k }: { k: Control }) {
  const rows: [string, number, number][] = [
    [t('campaign.detail.rateSent'), k.rebookedCount, k.reachedCount],
    [t('campaign.detail.rateControl'), k.controlRebookedCount, k.controlCount],
    [t('campaign.detail.cycleSent'), k.reachedCycleRevisitedCount, k.reachedCycleCount],
    [t('campaign.detail.cycleControl'), k.controlCycleRevisitedCount, k.controlCycleCount],
  ];
  return (
    <div>
      <span className="lbl">{t('campaign.detail.compare')}</span>
      {k.controlCount === 0 ? (
        <p className="meta">{t('campaign.detail.noControl')}</p>
      ) : (
        <dl className="d-dl" style={{ gridTemplateColumns: '160px 1fr' }}>
          {rows.map(([label, n, base]) => (
            <Fragment key={label}>
              <dt>{label}</dt>
              <dd className="num">
                <b>{rateText(n, base) ?? '-'}</b> <span className="meta">{t('campaign.detail.ratio', { n, base })}</span>
              </dd>
            </Fragment>
          ))}
        </dl>
      )}
      <p className="meta" style={{ marginTop: 6 }}>
        {t('campaign.detail.cycleNote')}
      </p>
    </div>
  );
}
