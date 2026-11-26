import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api } from '@/lib/api';
import { CHANNEL_LABEL, DOW_LABEL, dayIndex, endMs, formatMinutes, seoulParts, todayInSeoul, toneOf, type Channel, type ReservationStatus } from '@/lib/calendar';
import { useNowMs } from '@/lib/clock';
import { formatWon } from '@/lib/format';
import { ACTION_TEXT, actionPath, canClose, canMarkNoShow, closeTooEarly, confirmText, NO_SHOW_TOO_EARLY, type StatusAction } from '@/lib/reservationActions';
import { CustomerSearch, type CustomerPick } from './CustomerSearch';
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

const STATUS_CHIP: Record<ReservationStatus, [string, string]> = {
  CONFIRMED: ['예약 확정', 'booked'],
  COMPLETED: ['시술 완료', 'done'],
  CANCELED: ['예약 취소', 'canceled'],
  NO_SHOW: ['노쇼', 'noshow'],
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
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요'); // 예: 이미 처리된 예약, 시작 전 노쇼
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
      toast(`${c.name} 고객을 연결했어요.`);
      if (res?.notice) toast(res.notice, 'warn'); // 예약 제한 고객은 PIN 없이 연결하되 안내만 한다 (FR-CUS-14, E3)
      refresh();
    } catch (err) {
      setLinkError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
      if (err instanceof ApiError && err.code === 'conflict') refresh();
    }
  }

  const at = detail ? seoulParts(detail.startAt) : null;
  const tone = detail && nowMs ? toneOf(detail.status, endMs(detail.startAt, detail.minutes), nowMs) : null;
  const [chipLabel, chipClass] = detail ? (tone === 'unclosed' ? ['종료 시각 지남 · 마감 필요', 'unclosed'] : STATUS_CHIP[detail.status]) : ['', ''];
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
    if (detail.consultFirst) notes.push('상담 후 진행 — 시술을 정하지 못한 예약이에요.');
    if (!hasCustomer && confirmed) notes.push('고객 없는 예약 — 다른 앱으로 받은 예약이라 시간만 막아 두었어요. 손님이 오면 고객을 연결하세요.');
    if (tone === 'unclosed') notes.push('종료 시각 지남 · 마감 필요 — 시술 종료 시각이 지났는데 결제 · 방문 기록이 아직 없어요.');
  }

  return (
    <>
      <div className="wrap">
        <header className="head">
          <div>
            <h1 className="h1">예약 상세</h1>
            <p className="sub">
              <AppLink href={backHref} className="p-link">
                ‹ 예약 캘린더로
              </AppLink>
            </p>
          </div>
        </header>

        <section className="card detail-card" aria-label="예약 상세">
          {!validId || current?.error === 'notfound' ? (
            <p className="meta">찾을 수 없는 예약이에요. 예약 캘린더에서 다시 골라 주세요.</p>
          ) : current?.error === 'error' ? (
            <p className="meta">
              예약을 불러오지 못했어요.{' '}
              <button type="button" className="btn btn-sm" onClick={() => setReload((n) => n + 1)}>
                다시 시도
              </button>
            </p>
          ) : !detail || !at ? (
            <p className="meta">불러오는 중…</p>
          ) : (
            <div className="detail">
              <div className="d-top">
                <span className={'st-chip s-' + chipClass}>{chipLabel}</span>
                <span className="meta num">예약 번호 {detail.id}</span>
              </div>

              <div className="d-name">
                {detail.customer ? (
                  <b>
                    {detail.customer.name ? (
                      <AppLink href={`/customers/detail?id=${detail.customer.id}`}>{detail.customer.name}</AppLink>
                    ) : (
                      '이름 없음'
                    )}
                  </b>
                ) : (
                  <b>고객 정보 없음</b>
                )}
              </div>

              {detail.customer?.serviceCaution || detail.customer?.healthCaution ? (
                <div className="box warn" role="note" aria-label="고객 주의사항">
                  {detail.customer.serviceCaution ? (
                    <>
                      <span className="lbl">시술 주의사항</span>
                      <p>{detail.customer.serviceCaution}</p>
                    </>
                  ) : null}
                  {detail.customer.healthCaution ? (
                    <>
                      <span className="lbl" style={{ marginTop: detail.customer.serviceCaution ? 8 : 0 }}>
                        건강 주의사항
                      </span>
                      <p>{detail.customer.healthCaution}</p>
                    </>
                  ) : null}
                </div>
              ) : null}

              <dl className="d-dl">
                <dt>일시</dt>
                <dd className="strong num">
                  {at.date.slice(0, 4)}년 {Number(at.date.slice(5, 7))}월 {Number(at.date.slice(8, 10))}일 ({DOW_LABEL[dayIndex(at.date)]}){' '}
                  {formatMinutes(at.minutes)} – {formatMinutes(at.minutes + detail.minutes)} ({detail.minutes}분)
                </dd>
                <dt>담당 스탭</dt>
                <dd>{detail.staff.name}</dd>
                <dt>예약 경로</dt>
                <dd>{route}</dd>
                <dt>예정 시술</dt>
                <dd>
                  {detail.services.map((s) => (
                    <div key={s.serviceMenuId}>
                      {s.name} <span className="num meta">{formatWon(s.price)}</span>
                    </div>
                  ))}
                </dd>
                <dt>예상 금액</dt>
                <dd className="strong num">{detail.consultFirst ? '상담 후 결정' : formatWon(total)}</dd>
                {detail.memo ? (
                  <>
                    <dt>메모</dt>
                    <dd style={{ whiteSpace: 'pre-line' }}>{detail.memo}</dd>
                  </>
                ) : null}
                {detail.visitId !== null ? (
                  <>
                    <dt>방문 기록</dt>
                    <dd>시술 마감까지 끝났어요.</dd>
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
                  <div className="confirm" role="alertdialog" aria-label={`${ACTION_TEXT[confirming].button} 확인`}>
                    <p>{confirmText(confirming, hasCustomer)}</p>
                    <div className="confirm-actions">
                      <button type="button" className="btn btn-sm" disabled={busy} onClick={() => setConfirming(null)}>
                        돌아가기
                      </button>
                      <button type="button" className="btn btn-sm btn-danger" disabled={busy} onClick={() => void changeStatus(confirming)}>
                        {busy ? '처리 중…' : ACTION_TEXT[confirming].button}
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="d-acts">
                    {canCloseNow ? (
                      <AppLink href={`/reservations/closing?id=${id}`} className="btn btn-p btn-w">
                        시술 마감
                      </AppLink>
                    ) : (
                      <>
                        <button type="button" className="btn btn-p btn-w" disabled style={{ opacity: 0.5, cursor: 'not-allowed' }}>
                          시술 마감
                        </button>
                        {at ? <p className="meta">{closeTooEarly(at.date)}</p> : null}
                      </>
                    )}
                    <div className="act-2">
                      <button type="button" className="btn btn-w" onClick={() => setEditing(true)} disabled={staff.length === 0}>
                        일정 변경
                      </button>
                      {!hasCustomer ? (
                        <button type="button" className="btn btn-w" onClick={() => { setLinkError(''); setLinking(true); }}>
                          고객 연결
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
                        title={canNoShow ? undefined : NO_SHOW_TOO_EARLY}
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
                      추가 결제 · 메모
                    </AppLink>
                  ) : null}
                  <p className="meta">완료 · 취소 · 노쇼한 예약은 일정을 바꿀 수 없어요.</p>
                </div>
              )}
            </div>
          )}
        </section>
      </div>

      {editing && detail ? (
        <ReservationForm key={detail.id} editId={detail.id} initialDate={at?.date ?? ''} staff={staff} onClose={closeEditing} onSaved={afterEdit} onStale={refresh} />
      ) : null}

      {linking ? (
        <div className="overlay" onClick={() => setLinking(false)}>
          <div className="card modal" role="dialog" aria-modal="true" aria-label="고객 연결" onClick={(e) => e.stopPropagation()}>
            <h2 className="h2" style={{ fontSize: '19px' }}>
              고객 연결
            </h2>
            <p className="meta">손님이 온 뒤 이 예약에 고객을 연결해요. 검색에 없으면 바로 등록할 수 있어요.</p>
            <div>
              <span className="lbl">고객</span>
              <CustomerSearch onPick={(c) => void linkCustomer(c)} />
            </div>
            {linkError ? (
              <span className="err" role="alert">
                {linkError}
              </span>
            ) : null}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
              <button type="button" className="btn" onClick={() => setLinking(false)}>
                닫기
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
