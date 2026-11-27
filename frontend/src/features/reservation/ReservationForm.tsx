import { useEffect, useRef, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { useIndustries } from '@/hooks/useIndustries';
import { ApiError, api } from '@/lib/api';
import { CHANNEL_LABEL, hmOf, seoulParts, type Channel, type DateStr } from '@/lib/calendar';
import { t } from '@/lib/i18n';
import { pickableMenus } from '@/lib/menus';
import { defaultMinutes, detailsText, MINUTE_STEPS, toRequest, validate, type FormValues } from '@/lib/reservationForm';
import { ACTION_TEXT, actionPath, canMarkNoShow, confirmText, noShowTooEarly, type StatusAction } from '@/lib/reservationActions';
import { CustomerSearch, type CustomerPick } from './CustomerSearch';

// 예약 등록 · 일정 변경 폼 (FR-RSV-01 · 04). 요청 규칙은 docs CONTRACTS 6장 「예약 (RSV)」.
// 시작 시각은 서버가 알려 주는 가능 시각(영업시간 밖 · 휴무 · 겹침 제외)에서만 고른다. 저장할 때 서버가 다시 검사한다.
// 고객은 CustomerSearch로 찾거나 바로 등록한다. 고객 없는 예약에 고객 연결은 예약 상세(#128)에서 한다.
// 일정 변경 창에서는 예약 취소 · 노쇼 처리도 한다 (FR-RSV-03, lib/reservationActions). 되돌릴 수 없어 한 번 더 확인한다.

type StaffRef = { id: number; name: string };
type Menu = { id: number; category: string; name: string; durationMinutes: number };
type Detail = {
  startAt: string;
  minutes: number;
  channel: Channel;
  consultFirst: boolean;
  memo: string | null;
  staff: StaffRef;
  services: { serviceMenuId: number }[];
  customer: { id: number; name: string | null } | null;
};

const CHANNELS = Object.keys(CHANNEL_LABEL) as Channel[];
const STORE_FIRST: Channel[] = ['STORE', ...CHANNELS.filter((c) => c !== 'STORE')];

export function ReservationForm(props: {
  editId: number | null;
  initialDate: DateStr;
  staff: StaffRef[];
  onClose: () => void;
  onSaved: (date: DateStr) => void;
  onStale: () => void; // 그 사이 예약이 다른 곳에서 바뀌었음을 알게 되면 캘린더를 다시 읽는다
}) {
  const { editId, initialDate, staff, onClose, onSaved, onStale } = props;
  const editing = editId !== null;

  const [v, setV] = useState<FormValues>({
    channel: 'STORE',
    customerMode: 'pick',
    customerId: null,
    staffId: staff[0]?.id ?? null,
    date: initialDate,
    startTime: '',
    minutes: 30,
    menuIds: [],
    consultFirst: false,
    memo: '',
  });
  const [minutesTouched, setMinutesTouched] = useState(false); // 소요 시간을 직접 고르기 전에는 시술 합계를 따른다
  const [ready, setReady] = useState(!editing);
  const [menus, setMenus] = useState<Menu[] | null>(null);
  const industries = useIndustries(); // 꺼 둔 업종의 메뉴는 새로 고르는 선택지에서 뺀다 (이미 고른 메뉴는 남긴다)
  const [own, setOwn] = useState<{ date: DateStr; staffId: number; hm: string; startMs: number } | null>(null); // 수정 중인 예약의 원래 자리
  const [confirming, setConfirming] = useState<StatusAction | null>(null); // 취소 · 노쇼 확인 중
  const [openedAt] = useState(() => Date.now()); // 노쇼 가능 여부를 가르는 "지금" (창을 연 시각)
  const [hadCustomer, setHadCustomer] = useState(false);
  const [customerName, setCustomerName] = useState('');
  const [noShow, setNoShow] = useState<number | null>(null);
  const pickedId = useRef<number | null>(null); // 마지막으로 고른 고객. 늦게 도착한 앞 고객의 응답으로 노쇼 횟수를 바꾸지 않으려고 둔다
  const [timesFor, setTimesFor] = useState<{ key: string; list: string[] } | null>(null);
  const [tick, setTick] = useState(0); // 겹침으로 거절되면 가능 시각을 다시 읽는다
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // 시술 메뉴 (소요 시간 합계가 기본값, FR-RSV-01)
  useEffect(() => {
    let cancelled = false;
    api<Menu[]>('/api/service-menus')
      .then((list) => !cancelled && setMenus(list))
      .catch(() => !cancelled && setMenus([]));
    return () => {
      cancelled = true;
    };
  }, []);

  // 수정: 예약 상세로 폼을 채운다
  useEffect(() => {
    if (editId === null) return;
    let cancelled = false;
    api<Detail>(`/api/reservations/${editId}`)
      .then((d) => {
        if (cancelled) return;
        const at = seoulParts(d.startAt);
        setOwn({ date: at.date, staffId: d.staff.id, hm: hmOf(d.startAt), startMs: Date.parse(d.startAt) });
        setHadCustomer(d.customer !== null);
        setCustomerName(d.customer?.name ?? '');
        setMinutesTouched(true);
        setV({
          channel: d.channel,
          customerMode: d.customer ? 'pick' : 'none',
          customerId: d.customer?.id ?? null,
          staffId: d.staff.id,
          date: at.date,
          startTime: hmOf(d.startAt),
          minutes: d.minutes,
          menuIds: d.services.map((s) => s.serviceMenuId),
          consultFirst: d.consultFirst,
          memo: d.memo ?? '',
        });
        setReady(true);
      })
      .catch(() => !cancelled && onClose());
    return () => {
      cancelled = true;
    };
  }, [editId, onClose]);

  const minutes = minutesTouched
    ? v.minutes
    : defaultMinutes((menus ?? []).filter((m) => v.menuIds.includes(m.id)).map((m) => m.durationMinutes));

  // 예약 가능한 시작 시각 (스탭 · 날짜 · 소요 시간이 바뀔 때마다)
  const timesKey = `${v.staffId}|${v.date}|${minutes}|${tick}`;
  useEffect(() => {
    if (!ready || v.staffId === null || !v.date) return;
    let cancelled = false;
    api<{ startTimes: string[] }>(`/api/reservations/available-times?staffId=${v.staffId}&date=${v.date}&minutes=${minutes}`, { silent: true })
      .then((r) => !cancelled && setTimesFor({ key: timesKey, list: r.startTimes.map(hmOf) }))
      .catch(() => !cancelled && setTimesFor({ key: timesKey, list: [] }));
    return () => {
      cancelled = true;
    };
  }, [ready, v.staffId, v.date, minutes, timesKey]);

  const loaded = timesFor?.key === timesKey ? timesFor.list : null;
  // 수정 중인 예약의 원래 시각은 자기 자신이 차지하고 있어 목록에서 빠지므로, 같은 날 · 같은 스탭이면 선택지에 더한다
  const keepOwn = own !== null && own.date === v.date && own.staffId === v.staffId;
  const options = loaded ? [...new Set([...loaded, ...(keepOwn ? [own.hm] : [])])].sort() : [];
  const startTime = options.includes(v.startTime) ? v.startTime : '';

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !busy && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [busy, onClose]);

  const set = (patch: Partial<FormValues>) => {
    setError('');
    setV((cur) => ({ ...cur, ...patch }));
  };

  const pickCustomer = (c: CustomerPick, created: boolean) => {
    set({ customerId: c.id });
    setCustomerName(c.name);
    pickedId.current = c.id;
    // 고객을 고르면 그 고객의 노쇼 횟수를 함께 보여 준다 (C8). 방금 등록한 고객은 노쇼가 없다
    setNoShow(created ? 0 : null);
    if (!created) {
      // 응답이 올 때 마지막으로 고른 고객이 그대로일 때만 반영한다 (빠르게 다른 고객으로 바꾼 경우 앞 고객 응답은 버린다)
      api<{ noShowCount: number }>(`/api/customers/${c.id}`, { silent: true })
        .then((d) => pickedId.current === c.id && setNoShow(d.noShowCount))
        .catch(() => pickedId.current === c.id && setNoShow(null));
    }
  };

  const hasCustomer = editing ? hadCustomer : v.customerMode === 'pick';
  const pickable = pickableMenus(menus ?? [], industries, v.menuIds);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const values: FormValues = { ...v, minutes, startTime };
    const problem = validate(values, editing);
    if (problem) return setError(problem);
    setBusy(true);
    setError('');
    try {
      const body = toRequest(values, editing, hasCustomer);
      // 겹침 · 입력 오류는 이 창 안에서 보여 주려고 silent. 로그인 · PIN 관련 code는 api.ts가 처리한다 (원장 PIN 창은 예약 제한 고객일 때)
      if (editing) await api(`/api/reservations/${editId}`, { method: 'PATCH', body, silent: true });
      else await api('/api/reservations', { method: 'POST', body, silent: true });
      toast(editing ? t('reservation.form.updated') : t('reservation.form.created'));
      onSaved(values.date);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message);
        if (err.code === 'reservation_conflict') setTick((n) => n + 1); // 그 사이 시간이 찼을 수 있어 가능 시각을 다시 읽는다
      } else {
        setError(t('common.pin.tryLater'));
      }
    } finally {
      setBusy(false);
    }
  }

  // 예약 취소 · 노쇼 처리: 성공하면 그 예약이 있던 주를 다시 읽는다
  async function changeStatus(action: StatusAction) {
    if (editId === null || own === null) return;
    setBusy(true);
    setError('');
    try {
      await api(actionPath(editId, action), { method: 'POST', silent: true });
      toast(ACTION_TEXT[action].done);
      onSaved(own.date);
    } catch (err) {
      setConfirming(null);
      setError(err instanceof ApiError ? err.message : t('common.pin.tryLater')); // 예: 이미 처리된 예약(conflict), 시작 전 노쇼
      if (err instanceof ApiError && err.code === 'conflict') onStale();
    } finally {
      setBusy(false);
    }
  }

  const canNoShow = own !== null && canMarkNoShow(own.startMs, openedAt);
  const title = editing ? t('reservation.form.editTitle') : t('reservation.form.newTitle');

  return (
    <div className="overlay" onClick={() => !busy && onClose()}>
      <form className="card modal" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <h2 className="h2" style={{ fontSize: '19px' }}>
          {title}
        </h2>

        {!ready ? (
          <p className="meta">{t('common.loading')}</p>
        ) : (
          <>
            <div>
              <span className="lbl">{t('reservation.form.channel')}</span>
              <div className="pills">
                {STORE_FIRST.map((c) => (
                  <button key={c} type="button" className="pill" aria-pressed={v.channel === c} onClick={() => set({ channel: c })}>
                    {CHANNEL_LABEL[c]}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <span className="lbl">{t('common.customer')}</span>
              {editing ? (
                <p className="cust-fixed">{hadCustomer ? customerName || t('reservation.noName') : t('reservation.legend.noCustomer')}</p>
              ) : (
                <>
                  {v.customerMode === 'pick' && v.customerId !== null ? (
                    <div className="cust-sel">
                      <b>{customerName}</b>
                      {noShow !== null ? <span className={'num' + (noShow > 0 ? ' warn' : '')}>{t('reservation.form.noShow', { n: noShow })}</span> : null}
                      <button type="button" className="ib" aria-label={t('reservation.form.pickAgain')} onClick={() => set({ customerId: null })}>
                        ×
                      </button>
                    </div>
                  ) : (
                    // key: 「고객 없이 시간만 막기」를 켰다 끄면 검색 · 등록 칸을 처음 상태로 되돌린다
                    <CustomerSearch key={v.customerMode} disabled={v.customerMode === 'none'} onPick={pickCustomer} />
                  )}
                  <label className="chk" style={{ marginTop: '8px' }}>
                    <input
                      type="checkbox"
                      checked={v.customerMode === 'none'}
                      onChange={(e) => set(e.target.checked ? { customerMode: 'none', customerId: null, memo: '' } : { customerMode: 'pick' })}
                    />
                    <span>{t('reservation.form.blockOnly')}</span>
                  </label>
                </>
              )}
            </div>

            <div className="grid3">
              <div>
                <span className="lbl">{t('reservation.form.date')}</span>
                <input className="inp" type="date" value={v.date} onChange={(e) => set({ date: e.target.value })} />
              </div>
              <div>
                <span className="lbl">{t('common.staff')}</span>
                <select className="inp" value={v.staffId ?? ''} onChange={(e) => set({ staffId: Number(e.target.value) })}>
                  {staff.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <span className="lbl">{t('reservation.form.duration')}</span>
                <select
                  className="inp"
                  value={minutes}
                  onChange={(e) => {
                    setMinutesTouched(true);
                    set({ minutes: Number(e.target.value) });
                  }}
                >
                  {MINUTE_STEPS.map((m) => (
                    <option key={m} value={m}>
                      {m >= 60 ? (m % 60 ? t('reservation.form.hoursHalf', { h: Math.floor(m / 60) }) : t('reservation.form.hours', { h: Math.floor(m / 60) })) : t('reservation.form.min30')}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div>
              <span className="lbl">{t('reservation.form.startTime')}</span>
              <select className="inp" value={startTime} disabled={loaded === null} onChange={(e) => set({ startTime: e.target.value })}>
                <option value="">{loaded === null ? t('common.loading') : t('reservation.form.pickTime')}</option>
                {options.map((opt) => (
                  <option key={opt} value={opt}>
                    {opt}
                  </option>
                ))}
              </select>
              {loaded !== null && options.length === 0 ? (
                <p className="hint" style={{ marginTop: 8 }}>
                  {t('reservation.form.noSlots')}
                </p>
              ) : null}
            </div>

            <div>
              <span className="lbl">{t('reservation.form.services')}</span>
              {menus !== null && industries !== undefined && pickable.length === 0 ? (
                <p className="hint">{t('reservation.form.noMenus')}</p>
              ) : (
                <div className="pills">
                  {pickable.map((m) => {
                    const on = v.menuIds.includes(m.id);
                    return (
                      <button
                        key={m.id}
                        type="button"
                        className="pill"
                        aria-pressed={on}
                        onClick={() => set({ menuIds: on ? v.menuIds.filter((id) => id !== m.id) : [...v.menuIds, m.id] })}
                      >
                        {t('reservation.form.menuLine', { name: m.name, minutes: m.durationMinutes })}
                      </button>
                    );
                  })}
                </div>
              )}
              <label className="chk" style={{ marginTop: '8px' }}>
                <input type="checkbox" checked={v.consultFirst} onChange={(e) => set({ consultFirst: e.target.checked })} />
                <span>{t('reservation.form.consentLine')}</span>
              </label>
            </div>

            {hasCustomer ? (
              <div>
                <span className="lbl">{t('common.memo')}</span>
                <textarea className="inp" maxLength={1000} value={v.memo} onChange={(e) => set({ memo: e.target.value })} />
              </div>
            ) : null}

            {error ? (
              <span className="err" role="alert">
                {error}
              </span>
            ) : null}
          </>
        )}

        {confirming ? (
          <div className="confirm" role="alertdialog" aria-label={t('reservation.confirmAria', { action: ACTION_TEXT[confirming].button })}>
            <p>{confirmText(confirming, hadCustomer)}</p>
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
          <div className="form-actions">
            {editing && ready ? (
              <div className="status-actions">
                <button type="button" className="btn btn-danger-o" disabled={busy} onClick={() => setConfirming('cancel')}>
                  {ACTION_TEXT.cancel.button}
                </button>
                <button
                  type="button"
                  className="btn btn-danger-o"
                  disabled={busy || !canNoShow}
                  title={canNoShow ? undefined : noShowTooEarly()}
                  onClick={() => setConfirming('no-show')}
                >
                  {ACTION_TEXT['no-show'].button}
                </button>
              </div>
            ) : null}
            <div className="form-actions-main">
              <button type="button" className="btn" disabled={busy} onClick={onClose}>
                {editing ? t('common.close') : t('common.cancel')}
              </button>
              <button type="submit" className="btn btn-p" disabled={busy || !ready}>
                {busy ? t('common.saving') : editing ? t('reservation.form.saveChanges') : t('reservation.form.create')}
              </button>
            </div>
          </div>
        )}
      </form>
    </div>
  );
}
