import { useCallback, useEffect, useState } from 'react';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';
import { useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api } from '@/lib/api';
import { CHANNEL_LABEL, dayIndex, dowLabel, endMs, formatMinutes, fullDateText, seoulParts, todayInSeoul, toneOf, type Channel, type ReservationStatus } from '@/lib/calendar';
import { useNowMs } from '@/lib/clock';
import { formatWon } from '@/lib/format';
import { ACTION_TEXT, actionPath, canClose, canMarkNoShow, closeTooEarly, confirmText, noShowTooEarly, type StatusAction } from '@/lib/reservationActions';
import { CustomerSearch, type CustomerPick } from './CustomerSearch';
import { PastVisits } from './PastVisits';
import { ReservationForm } from './ReservationForm';
import styleText from './SchedulePage.css?inline';

// 예약 상세 (FR-RSV-03 상태, FR-DSH-01 · E3 고객 없는 예약의 [고객 연결]). 경로: /reservations/detail?id=
// API: GET /api/reservations/{id} · PUT /api/reservations/{id}/customer · POST /api/reservations/{id}/cancel · /no-show (docs CONTRACTS 6장 「예약 (RSV)」)
// 시술 마감(결제 · 방문 기록)은 별도 화면(/reservations/closing, #129)이고, 여기서는 그리로 가는 진입 버튼만 둔다.

type StaffRef = { id: number; name: string };
type Detail = {
  id: number;
  status: ReservationStatus;
  startAt: string;
  minutes: number;
  channel: Channel;
  consultFirst: boolean;
  memo: string | null;
  staff: StaffRef;
  services: { serviceMenuId: number; name: string; price: number }[];
  customer: { id: number; name: string | null; serviceCaution: string | null; healthCaution: string | null } | null;
  visitId: number | null;
};
type Loaded = { id: number; detail?: Detail; error?: 'notfound' | 'error' };

const STATUS_CHIP: Record<ReservationStatus, [MessageKey, string]> = {
  CONFIRMED: ['reservation.status.confirmed', 'booked'],
  COMPLETED: ['reservation.status.completed', 'done'],
  CANCELED: ['reservation.status.canceled', 'canceled'],
  NO_SHOW: ['reservation.status.noShow', 'noshow'],
};

export default function ReservationDetailPage() {
  useStyleSheet('SchedulePage', styleText);

  const params = useSearchParams();
  const id = Number(params.get('id'));
  const validId = Number.isInteger(id) && id > 0;
  const nowMs = useNowMs();

  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reload, setReload] = useState(0);
  const [staff, setStaff] = useState<StaffRef[]>([]);
  const [editing, setEditing] = useState(false); // 일정 변경 창
  const [linking, setLinking] = useState(false); // 고객 연결 창
  const [linkError, setLinkError] = useState('');
  const [confirming, setConfirming] = useState<StatusAction | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // 일정 변경 창이 스탭 목록을 쓴다
  useEffect(() => {
    let cancelled = false;
    api<StaffRef[]>('/api/staff')
      .then((list) => !cancelled && setStaff(list))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!validId) return;
    let cancelled = false;
    api<Detail>(`/api/reservations/${id}`, { silent: true })
      .then((detail) => !cancelled && setLoaded({ id, detail }))
      .catch((err) => !cancelled && setLoaded({ id, error: err instanceof ApiError && err.code === 'not_found' ? 'notfound' : 'error' }));
    return () => {
      cancelled = true;
    };
  }, [id, validId, reload]);

  const current = loaded?.id === id ? loaded : null;
  const detail = current?.detail;
  const refresh = useCallback(() => setReload((n) => n + 1), []);
  const closeEditing = useCallback(() => setEditing(false), []);
  const afterEdit = useCallback(() => {
    setEditing(false);
    setReload((n) => n + 1);
  }, []);

  async function changeStatus(action: StatusAction) {
    setBusy(true);
    setError('');
    try {
      await api(actionPath(id, action), { method: 'POST', silent: true });
      toast(ACTION_TEXT[action].done);
      setConfirming(null);
      refresh();
    } catch (err) {
      setConfirming(null);
      setError(err instanceof ApiError ? err.message : t('common.pin.tryLater')); // 예: 이미 처리된 예약, 시작 전 노쇼
      if (err instanceof ApiError && err.code === 'conflict') refresh();
    } finally {
      setBusy(false);
    }
  }

  async function linkCustomer(c: CustomerPick) {
    setLinkError('');
    try {
      const res = await api<{ notice: string | null }>(`/api/reservations/${id}/customer`, { method: 'PUT', body: { customerId: c.id }, silent: true });
      setLinking(false);
      toast(t('reservation.linkedToast', { name: c.name }));
      if (res?.notice) toast(res.notice, 'warn'); // 예약 제한 고객은 PIN 없이 연결하되 안내만 한다 (FR-CUS-14, E3)
      refresh();
    } catch (err) {
      setLinkError(err instanceof ApiError ? err.message : t('common.pin.tryLater'));
      if (err instanceof ApiError && err.code === 'conflict') refresh();
    }
  }

  const at = detail ? seoulParts(detail.startAt) : null;
  const tone = detail && nowMs ? toneOf(detail.status, endMs(detail.startAt, detail.minutes), nowMs) : null;
  const [chipLabel, chipClass]: [MessageKey | '', string] = detail ? (tone === 'unclosed' ? ['reservation.state.unclosed', 'unclosed'] : STATUS_CHIP[detail.status]) : ['', ''];
  const confirmed = detail?.status === 'CONFIRMED';
  const hasCustomer = detail?.customer != null;
  const startMs = detail ? Date.parse(detail.startAt) : 0;
  const canNoShow = nowMs > 0 && canMarkNoShow(startMs, nowMs);
  // 시계를 읽기 전(nowMs 0)에는 막지 않는다. 서버가 다시 검사한다 (F71)
  const canCloseNow = !at || nowMs === 0 || canClose(at.date, todayInSeoul(nowMs));
  const total = detail ? detail.services.reduce((sum, s) => sum + s.price, 0) : 0;
  const route = detail ? (CHANNEL_LABEL[detail.channel] ?? detail.channel) : '';
  const backHref = at ? `/reservations?date=${at.date}` : '/reservations';

  const notes: string[] = [];
  if (detail) {
    if (detail.consultFirst) notes.push(t('reservation.detail.noteConsult'));
    if (!hasCustomer && confirmed) notes.push(t('reservation.detail.noteNoCustomer'));
    if (tone === 'unclosed') notes.push(t('reservation.detail.noteUnclosed'));
  }

  return (
    <>
      <div className="wrap">
        <header className="head">
          <div>
            <h1 className="h1">{t('reservation.detail.title')}</h1>
            <p className="sub">
              <AppLink href={backHref} className="p-link">
                {t('reservation.detail.toSchedule')}
              </AppLink>
            </p>
          </div>
        </header>

        {/* 예약 상세 오른쪽에 지난 방문 (#445). 좁은 화면은 아래로 쌓인다 */}
        <div className="detail-row">
          <section className="card detail-card" aria-label={t('reservation.detail.title')}>
            {!validId || current?.error === 'notfound' ? (
              <p className="meta">{t('reservation.notFound')}</p>
            ) : current?.error === 'error' ? (
              <p className="meta">
                {t('reservation.loadFailed')}{' '}
                <button type="button" className="btn btn-sm" onClick={() => setReload((n) => n + 1)}>
                  {t('common.retry')}
                </button>
              </p>
            ) : !detail || !at ? (
              <p className="meta">{t('common.loading')}</p>
            ) : (
              <div className="detail">
                <div className="d-top">
                  <span className={'st-chip s-' + chipClass}>{chipLabel ? t(chipLabel) : ''}</span>
                  <span className="meta num">{t('reservation.detail.number', { id: detail.id })}</span>
                </div>

                <div className="d-name">
                  {detail.customer ? (
                    <b>
                      {detail.customer.name ? (
                        <AppLink href={`/customers/detail?id=${detail.customer.id}`}>{detail.customer.name}</AppLink>
                      ) : (
                        t('reservation.noName')
                      )}
                    </b>
                  ) : (
                    <b>{t('reservation.detail.noCustomerInfo')}</b>
                  )}
                </div>

                {detail.customer?.serviceCaution || detail.customer?.healthCaution ? (
                  <div className="box warn" role="note" aria-label={t('reservation.detail.cautionLabel')}>
                    {detail.customer.serviceCaution ? (
                      <>
                        <span className="lbl">{t('reservation.detail.serviceCaution')}</span>
                        <p>{detail.customer.serviceCaution}</p>
                      </>
                    ) : null}
                    {detail.customer.healthCaution ? (
                      <>
                        <span className="lbl" style={{ marginTop: detail.customer.serviceCaution ? 8 : 0 }}>
                          {t('reservation.detail.healthCaution')}
                        </span>
                        <p>{detail.customer.healthCaution}</p>
                      </>
                    ) : null}
                  </div>
                ) : null}

                <dl className="d-dl">
                  <dt>{t('reservation.detail.when')}</dt>
                  <dd className="strong num">
                    {fullDateText(at.date)} ({dowLabel(dayIndex(at.date))}){' '}
                    {formatMinutes(at.minutes)} – {formatMinutes(at.minutes + detail.minutes)} ({t('common.minutes', { n: detail.minutes })})
                  </dd>
                  <dt>{t('common.staff')}</dt>
                  <dd>{detail.staff.name}</dd>
                  <dt>{t('reservation.form.channel')}</dt>
                  <dd>{route}</dd>
                  <dt>{t('reservation.detail.services')}</dt>
                  <dd>
                    {detail.services.map((s) => (
                      <div key={s.serviceMenuId}>
                        {s.name} <span className="num meta">{formatWon(s.price)}</span>
                      </div>
                    ))}
                  </dd>
                  <dt>{t('reservation.detail.estimate')}</dt>
                  <dd className="strong num">{detail.consultFirst ? t('reservation.detail.decideAfter') : formatWon(total)}</dd>
                  {detail.memo ? (
                    <>
                      <dt>{t('common.memo')}</dt>
                      <dd style={{ whiteSpace: 'pre-line' }}>{detail.memo}</dd>
                    </>
                  ) : null}
                  {detail.visitId !== null ? (
                    <>
                      <dt>{t('reservation.detail.visitRecord')}</dt>
                      <dd>{t('reservation.detail.closedDone')}</dd>
                    </>
                  ) : null}
                </dl>

                {notes.length ? (
                  <div className="box">
                    <ul>
                      {notes.map((n) => (
                        <li key={n}>{n}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}

                {error ? (
                  <span className="err" role="alert">
                    {error}
                  </span>
                ) : null}

                {confirmed ? (
                  confirming ? (
                    <div className="confirm" role="alertdialog" aria-label={t('reservation.confirmAria', { action: ACTION_TEXT[confirming].button })}>
                      <p>{confirmText(confirming, hasCustomer)}</p>
                      <div className="confirm-actions">
                        <button type="button" className="btn btn-sm" disabled={busy} onClick={() => setConfirming(null)}>
                          {t('common.back')}
                        </button>
                        <button type="button" className="btn btn-sm btn-danger" disabled={busy} onClick={() => void changeStatus(confirming)}>
                          {busy ? t('common.processing') : ACTION_TEXT[confirming].button}
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="d-acts">
                      {canCloseNow ? (
                        <AppLink href={`/reservations/closing?id=${id}`} className="btn btn-p btn-w">
                          {t('reservation.closing.title')}
                        </AppLink>
                      ) : (
                        <>
                          <button type="button" className="btn btn-p btn-w" disabled style={{ opacity: 0.5, cursor: 'not-allowed' }}>
                            {t('reservation.closing.title')}
                          </button>
                          {at ? <p className="meta">{closeTooEarly(at.date)}</p> : null}
                        </>
                      )}
                      <div className="act-2">
                        <button type="button" className="btn btn-w" onClick={() => setEditing(true)} disabled={staff.length === 0}>
                          {t('reservation.detail.reschedule')}
                        </button>
                        {!hasCustomer ? (
                          <button type="button" className="btn btn-w" onClick={() => { setLinkError(''); setLinking(true); }}>
                            {t('reservation.linkCustomer')}
                          </button>
                        ) : null}
                      </div>
                      <div className="sub-acts">
                        <button type="button" className="lk danger" onClick={() => setConfirming('cancel')}>
                          {ACTION_TEXT.cancel.button}
                        </button>
                        <button
                          type="button"
                          className="lk danger"
                          disabled={!canNoShow}
                          title={canNoShow ? undefined : noShowTooEarly()}
                          onClick={() => setConfirming('no-show')}
                          style={canNoShow ? undefined : { opacity: 0.5, cursor: 'not-allowed' }}
                        >
                          {ACTION_TEXT['no-show'].button}
                        </button>
                      </div>
                    </div>
                  )
                ) : (
                  <div className="d-acts">
                    {detail.status === 'COMPLETED' ? (
                      <AppLink href={`/reservations/closing?id=${id}`} className="btn btn-w">
                        {t('reservation.detail.extra')}
                      </AppLink>
                    ) : null}
                    <p className="meta">{t('reservation.detail.locked')}</p>
                  </div>
                )}
              </div>
            )}
          </section>
          {detail?.customer?.name ? <PastVisits customerId={detail.customer.id} /> : null}
        </div>
      </div>

      {editing && detail ? (
        <ReservationForm key={detail.id} editId={detail.id} initialDate={at?.date ?? ''} staff={staff} onClose={closeEditing} onSaved={afterEdit} onStale={refresh} />
      ) : null}

      {linking ? (
        <div className="overlay" onClick={() => setLinking(false)}>
          <div className="card modal" role="dialog" aria-modal="true" aria-label={t('reservation.linkCustomer')} onClick={(e) => e.stopPropagation()}>
            <h2 className="h2" style={{ fontSize: '19px' }}>
              {t('reservation.linkCustomer')}
            </h2>
            <p className="meta">{t('reservation.detail.linkHelp')}</p>
            <div>
              <span className="lbl">{t('common.customer')}</span>
              <CustomerSearch onPick={(c) => void linkCustomer(c)} />
            </div>
            {linkError ? (
              <span className="err" role="alert">
                {linkError}
              </span>
            ) : null}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
              <button type="button" className="btn" onClick={() => setLinking(false)}>
                {t('common.close')}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
