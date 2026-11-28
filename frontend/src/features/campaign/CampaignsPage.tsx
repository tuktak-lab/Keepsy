import { useEffect, useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { PinGate } from '@/components/common/PinGate';
import { CountUp } from '@/components/common/CountUp';
import { toast } from '@/components/common/Toast';
import { ApiError, api, type Page } from '@/lib/api';
import { CAMPAIGN_STATUS_LABEL, SEND_STATUS_LABEL } from '@/lib/campaignResult';
import { formatDateTime } from '@/lib/format';
import { CATEGORY_LABEL, type Category } from '@/lib/menus';
import styleText from './CampaignsPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { t } from '@/lib/i18n';

// 캠페인 목록 (FR-CMP-10). 경로: /campaigns. 기능 키 CAMPAIGN_VIEW (PinGate, 권한 검사는 API).
// API (docs CONTRACTS 6장 CMP): GET /api/campaigns (목록) · GET /api/campaigns/{id} (상세 · 고객별 발송 결과)
// 고객별 결과 전체 · 퍼널 · 대조군 비교는 캠페인 결과 화면(/campaigns/detail?id=)에서 본다. 작성은 /campaigns/new.

type Campaign = {
  id: number;
  name: string;
  category: Category | null;
  messageKind: 'AD' | 'INFO';
  status: string;
  targetCount: number;
  controlCount: number;
  scheduledAt: string | null;
  sentAt: string | null;
  cancelReason: string | null;
};
type Message = {
  customerId: number;
  customerName: string | null;
  customerDeleted: boolean;
  phone: string | null;
  controlGroup: boolean;
  sendStatus: string | null;
  failureReason: string | null;
};
type Detail = Campaign & {
  controlPercent: number;
  reviewedBy: { id: number; name: string } | null;
  reviewedAt: string | null;
  canceledAt: string | null;
  messages: Page<Message>;
};

const LIST_SIZE = 100;
const RESULT_SIZE = 100;
const FAILURES_SHOWN = 5;
const POLL_MS = 3000;
const KIND_LABEL: Record<string, string> = {
  get AD() {
    return t('campaign.kind.ad');
  },
  get INFO() {
    return t('campaign.kind.info');
  },
};
const CANCEL_LABEL: Record<string, string> = {
  get USER_CANCELED() {
    return t('campaign.cancel.user');
  },
  get DAILY_LIMIT_EXCEEDED() {
    return t('campaign.cancel.limit');
  },
  get NIGHT_TIME() {
    return t('campaign.cancel.night');
  },
};
// 상태 칩 [배경, 글자]
const STATUS_CHIP: Record<string, [string, string]> = {
  DRAFT: ['#F5EBD3', '#6B4509'],
  SCHEDULED: ['#F1E4E0', '#683945'],
  SENDING: ['#EEF2EE', '#3F6150'],
  SENT: ['#F9EBE8', '#7A4450'],
  CANCELED: ['#F1ECE9', '#6B605B'],
};
const unit = (u: string) => (n: number) => n + u;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : t('campaign.tryReopen'));

/** 'YYYY-MM' (서울) */
const seoulMonth = (d: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit' }).format(d);

/** 목록 한 줄의 일정 글자 */
function scheduleText(c: Campaign): string {
  if (c.status === 'CANCELED') return `${CAMPAIGN_STATUS_LABEL.CANCELED}${c.cancelReason ? ` · ${CANCEL_LABEL[c.cancelReason] ?? c.cancelReason}` : ''}`;
  if (c.sentAt) return t('campaign.list.sentAt', { at: formatDateTime(c.sentAt) });
  if (c.scheduledAt) return t('campaign.list.scheduledAt', { at: formatDateTime(c.scheduledAt) });
  return t('campaign.list.notSent');
}

function Chip(props: { status: string }) {
  const [bg, fg] = STATUS_CHIP[props.status] ?? STATUS_CHIP.CANCELED;
  return (
    <span className="cp-chip" style={{ background: bg, color: fg }}>
      {CAMPAIGN_STATUS_LABEL[props.status] ?? props.status}
    </span>
  );
}

function Body() {
  const [items, setItems] = useState<Campaign[] | null>(null);
  const [total, setTotal] = useState(0);
  const [listError, setListError] = useState('');
  const [sel, setSel] = useState<number | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  // 어느 캠페인의 오류인지 같이 둬서, 다른 캠페인을 고르면 이전 오류를 보이지 않는다
  const [detailError, setDetailError] = useState<{ id: number; message: string } | null>(null);
  // 예약 취소 뒤 목록 · 요약을 다시 받는다
  const [version, setVersion] = useState(0);

  useEffect(() => {
    // 화면을 떠났거나 다시 받기 시작했으면 지난 응답은 버린다 (#344)
    let alive = true;
    api<Page<Campaign>>(`/api/campaigns?page=0&size=${LIST_SIZE}`, { silent: true })
      .then((p) => {
        if (!alive) return;
        setItems(p.items);
        setTotal(p.totalElements);
        setListError('');
        setSel((cur) => cur ?? p.items[0]?.id ?? null);
      })
      .catch((err) => alive && setListError(errorText(err)));
    return () => {
      alive = false;
    };
  }, [version]);

  useEffect(() => {
    if (sel === null) return;
    let alive = true;
    api<Detail>(`/api/campaigns/${sel}?page=0&size=${RESULT_SIZE}`, { silent: true })
      .then((d) => alive && (setDetail(d), setDetailError(null)))
      .catch((err) => alive && setDetailError({ id: sel, message: errorText(err) }));
    return () => {
      alive = false;
    };
  }, [sel, version]);

  // 발송 중이거나 결과 대기가 남아 있으면 가상 발송기 주기(3초)마다 다시 받는다. 다 끝나면 멈춘다
  const inFlight =
    Boolean(items?.some((c) => c.status === 'SENDING')) || Boolean(detail?.messages.items.some((m) => m.sendStatus === 'PENDING'));
  useEffect(() => {
    if (!inFlight) return;
    const timer = setTimeout(() => setVersion((v) => v + 1), POLL_MS);
    return () => clearTimeout(timer);
  }, [inFlight, version]);

  const thisMonth = seoulMonth(new Date());
  const scheduled = items?.filter((c) => c.status === 'SCHEDULED').length ?? 0;
  const sentThisMonth = items?.filter((c) => c.sentAt && seoulMonth(new Date(c.sentAt)) === thisMonth).length ?? 0;
  const shown = detail && detail.id === sel ? detail : null;
  const shownError = detailError && detailError.id === sel ? detailError.message : '';
  const retry = () => setVersion((v) => v + 1);
  // 목록을 아직 못 받았으면 0건 대신 「-」
  const kpi = (n: number) => (items === null ? '-' : <CountUp value={n} format={unit(t('campaign.unit.cases'))} />);

  return (
    <div className="cp-wrap">
      <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
        <div>
          <h1 className="cp-h1">{t('shell.nav.campaigns')}</h1>
          <p className="cp-sub">{t('campaign.list.sub')}</p>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }} data-guide="campaigns-actions">
          <AppLink href="/campaigns/templates" className="cp-btn">{t('campaign.list.templates')}</AppLink>
          <AppLink href="/campaigns/reconfirm" className="cp-btn">{t('campaign.list.reconfirm')}</AppLink>
          <AppLink href="/virtual-phone" className="cp-btn">{t('campaign.list.virtualPhone')}</AppLink>
          <AppLink href="/campaigns/new" className="cp-btn p">{t('campaign.list.new')}</AppLink>
        </div>
      </div>

      <section className="cp-kpis cp-card" aria-label={t('campaign.list.kpiLabel')} data-guide="campaigns-kpis">
        <div className="cp-kpi"><span>{t('campaign.status.scheduled')}</span><b className="num">{kpi(scheduled)}</b><em>{t('campaign.list.kpiCancelable')}</em></div>
        <div className="cp-kpi"><span>{t('campaign.list.kpiMonth')}</span><b className="num">{kpi(sentThisMonth)}</b><em>{t('campaign.list.kpiMonthNote')}</em></div>
        <div className="cp-kpi"><span>{t('campaign.list.kpiTotal')}</span><b className="num">{kpi(total)}</b><em>{t('campaign.list.kpiTotalNote')}</em></div>
        <div className="cp-kpi"><span>{t('campaign.list.kpiResult')}</span><b style={{ fontSize: 16, paddingTop: 8 }}><AppLink href="/analytics" style={{ color: '#7A4450' }}>{t('campaign.list.kpiResultLink')}</AppLink></b><em>{t('campaign.list.kpiResultNote')}</em></div>
      </section>

      {listError && (
        <div className="cp-warn" role="alert">
          {listError}{' '}
          <button type="button" className="cp-lk" onClick={retry}>{t('common.retry')}</button>
        </div>
      )}
      {/* 한 번이라도 받은 목록은 오류 중에도 남겨 두고, 자동 새로고침이 성공하면 오류를 지운다 */}
      {listError && items === null ? null : (
        <div className="cp-grid">
          <section className="cp-card cp-list" aria-label={t('campaign.list.listTitle')} data-guide="campaigns-list">
            <h2>{t('campaign.list.listTitle')}</h2>
            {items === null && <p className="cp-note" style={{ padding: '0 10px' }}>{t('common.loading')}</p>}
            {items?.length === 0 && <p className="cp-note" style={{ padding: '0 10px' }}>{t('campaign.list.empty')}</p>}
            {items?.map((x) => (
              <button type="button" className="cp-item" key={x.id} aria-pressed={sel === x.id} onClick={() => setSel(x.id)}>
                <b>{x.name}</b>
                <span className="cp-meta num">
                  {t('campaign.list.rowMeta', { category: x.category ? CATEGORY_LABEL[x.category] : t('campaign.list.reconfirm'), kind: KIND_LABEL[x.messageKind] ?? x.messageKind, n: x.targetCount })}
                </span>
                <span className="cp-row2">
                  <span className="cp-meta num">{scheduleText(x)}</span>
                  <Chip status={x.status} />
                </span>
              </button>
            ))}
            {items && items.length < total && <p className="cp-note" style={{ padding: '6px 10px 0' }}>{t('campaign.list.onlyRecent', { n: items.length })}</p>}
          </section>

          <section className="cp-card cp-main" aria-label={t('campaign.list.detailLabel')} data-guide="campaigns-detail">
            {items?.length === 0 ? (
              <div className="cp-ok">
                {t('campaign.list.stepHint')}{' '}
                <AppLink href="/campaigns/new" style={{ color: '#7A4450', fontWeight: 600 }}>{t('campaign.list.newLink')}</AppLink>
              </div>
            ) : shownError ? (
              <div className="cp-warn" role="alert">
                {shownError}{' '}
                <button type="button" className="cp-lk" onClick={retry}>{t('common.retry')}</button>
              </div>
            ) : shown ? (
              <div className="kp-slide cp-main-in" key={shown.id} style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
                <Summary campaign={shown} onChanged={() => setVersion((v) => v + 1)} />
              </div>
            ) : (
              items && items.length > 0 && <p className="cp-note">{t('common.loading')}</p>
            )}
          </section>
        </div>
      )}
      <p className="cp-foot">{t('campaign.list.foot')}</p>
    </div>
  );
}

/** 선택한 캠페인 요약과 발송 결과 (FR-CMP-10). 고객별 결과는 앞 RESULT_SIZE명까지 센다 */
function Summary({ campaign: c, onChanged }: { campaign: Detail; onChanged: () => void }) {
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const rows = c.messages.items.filter((m) => !m.controlGroup);
  const count = (s: string) => rows.filter((m) => m.sendStatus === s).length;
  const failures = rows.filter((m) => m.sendStatus === 'FAILED');
  const partial = c.messages.totalElements > c.messages.items.length;
  const started = c.messages.totalElements > 0;

  // 작성 중 · 예약 발송 대기 캠페인 취소 (F7, POST /api/campaigns/{id}/cancel, CAMPAIGN_SEND)
  async function cancel() {
    setBusy(true);
    setError('');
    try {
      await api(`/api/campaigns/${c.id}/cancel`, { method: 'POST', silent: true });
      toast(t('campaign.canceledToast'), 'warn');
      onChanged();
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  }

  return (
    <>
      <div className="cp-row2">
        <h2 style={{ fontSize: 20, fontWeight: 700 }}>{c.name}</h2>
        <Chip status={c.status} />
      </div>
      <span className="cp-meta">
        {c.category ? CATEGORY_LABEL[c.category] : t('customer.msgKind.reconfirm')} · {KIND_LABEL[c.messageKind] ?? c.messageKind}
        {c.reviewedBy && ` · ${t('campaign.list.reviewedBy', { name: c.reviewedBy.name })}${c.reviewedAt ? ` (${formatDateTime(c.reviewedAt)})` : ''}`}
        <br />
        <span className="num">{scheduleText(c)}</span>
      </span>

      <div className="cp-stats">
        <div className="cp-stat"><b className="num">{t('common.persons', { n: c.targetCount })}</b><span>{t('campaign.list.statTarget')}</span></div>
        <div className="cp-stat"><b className="num">{t('common.persons', { n: c.controlCount })}</b><span>{t('campaign.list.statControl', { percent: c.controlPercent })}</span></div>
        <div className="cp-stat"><b className="num">{t('common.persons', { n: c.targetCount - c.controlCount })}</b><span>{t('campaign.list.statSend')}</span></div>
      </div>

      {c.status === 'CANCELED' && c.canceledAt && (
        <div className="cp-ok">
          {t('campaign.list.canceledAt', { at: formatDateTime(c.canceledAt) })}{c.cancelReason ? ` · ${CANCEL_LABEL[c.cancelReason] ?? c.cancelReason}` : ''}.
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <h3 style={{ fontSize: 15.5, fontWeight: 600 }}>{t('campaign.list.sendResult')}</h3>
        {!started ? (
          <p className="cp-note">{t('campaign.list.notStarted')}</p>
        ) : (
          <>
            <div className="cp-excl">
              <span className="cp-ex">{SEND_STATUS_LABEL.SENT} <b className="num">{count('SENT')}</b></span>
              <span className="cp-ex">{SEND_STATUS_LABEL.FAILED} <b className="num">{count('FAILED')}</b></span>
              <span className="cp-ex">{SEND_STATUS_LABEL.PENDING} <b className="num">{count('PENDING')}</b></span>
            </div>
            {partial && <span className="cp-note">{t('campaign.list.partial', { n: c.messages.items.length })}</span>}
            {count('PENDING') > 0 && <span className="cp-note">{t('campaign.list.pendingNote')}</span>}
            {failures.length > 0 && (
              <table className="cp-tbl">
                <thead>
                  <tr>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('common.customer')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('campaign.list.colNumber')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('campaign.list.colFailure')}</th>
                  </tr>
                </thead>
                <tbody>
                  {failures.slice(0, FAILURES_SHOWN).map((m) => (
                    <tr key={m.customerId}>
                      <td>{m.customerDeleted ? t('campaign.list.deletedCustomer') : m.customerName}</td>
                      <td className="num">{m.phone ?? '—'}</td>
                      <td>{m.failureReason ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {failures.length > FAILURES_SHOWN && <span className="cp-note">{t('campaign.list.moreFailures', { n: failures.length - FAILURES_SHOWN })}</span>}
          </>
        )}
      </div>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {error && <div className="cp-warn" role="alert" style={{ width: '100%' }}>{error}</div>}
        {c.status === 'DRAFT' && <AppLink href={`/campaigns/new?id=${c.id}`} className="cp-btn p">{t('campaign.list.continue')}</AppLink>}
        {(c.status === 'DRAFT' || c.status === 'SCHEDULED') &&
          (confirmCancel ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="cp-note">{c.status === 'SCHEDULED' ? t('campaign.list.askCancelSched') : t('campaign.list.askCancel')}</span>
              <button type="button" className="cp-btn" style={{ color: '#8F2F2F' }} disabled={busy} onClick={cancel}>{t('campaign.list.doCancel')}</button>
              <button type="button" className="cp-btn" disabled={busy} onClick={() => setConfirmCancel(false)}>{t('campaign.list.keep')}</button>
            </span>
          ) : (
            <button type="button" className="cp-btn" onClick={() => setConfirmCancel(true)}>{c.status === 'SCHEDULED' ? t('campaign.list.cancelSched') : t('campaign.list.cancel')}</button>
          ))}
        {c.status !== 'DRAFT' && <AppLink href={`/campaigns/detail?id=${c.id}`} className="cp-btn p">{t('campaign.list.viewResult')}</AppLink>}
      </div>
    </>
  );
}

export default function CampaignsPage() {
  useStyleSheet('CampaignsPage', styleText);
  return (
    <>
      <PinGate id="campaigns" title={t('shell.nav.campaigns')}>
        <Body />
      </PinGate>
    </>
  );
}
