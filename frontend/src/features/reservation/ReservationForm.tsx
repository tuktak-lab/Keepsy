import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api, type Page } from '@/lib/api';
import { CHANNEL_LABEL, hmOf, seoulParts, type Channel, type DateStr } from '@/lib/calendar';
import { defaultMinutes, detailsText, MINUTE_STEPS, toRequest, validate, type FormValues } from '@/lib/reservationForm';

// 예약 등록 · 일정 변경 폼 (FR-RSV-01 · 04). 요청 규칙은 docs CONTRACTS 6장 「예약 (RSV)」.
// 시작 시각은 서버가 알려 주는 가능 시각(영업시간 밖 · 휴무 · 겹침 제외)에서만 고른다. 저장할 때 서버가 다시 검사한다.
// 신규 고객 등록은 다음 PR, 고객 연결은 예약 상세(#128)에서 한다.

type StaffRef = { id: number; name: string };
type Menu = { id: number; name: string; durationMinutes: number };
type CustomerItem = { customerId: number; name: string; phone: string };
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
}) {
  const { editId, initialDate, staff, onClose, onSaved } = props;
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
  const [own, setOwn] = useState<{ date: DateStr; staffId: number; hm: string } | null>(null); // 수정 중인 예약의 원래 자리
  const [hadCustomer, setHadCustomer] = useState(false);
  const [customerName, setCustomerName] = useState('');
  const [noShow, setNoShow] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<CustomerItem[]>([]);
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
        setOwn({ date: at.date, staffId: d.staff.id, hm: hmOf(d.startAt) });
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

  // 고객 검색 (이름 포함 · 휴대폰 뒷 4자리, FR-CUS-02). 입력이 멈추면 한 번만 부른다
  useEffect(() => {
    const q = search.trim();
    if (!q) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      api<Page<CustomerItem>>(`/api/customers?q=${encodeURIComponent(q)}&size=8`, { silent: true })
        .then((p) => !cancelled && setResults(p.items))
        .catch(() => !cancelled && setResults([]));
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [search]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !busy && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [busy, onClose]);

  const set = (patch: Partial<FormValues>) => {
    setError('');
    setV((cur) => ({ ...cur, ...patch }));
  };

  const pickCustomer = (c: CustomerItem) => {
    set({ customerId: c.customerId });
    setCustomerName(c.name);
    setSearch('');
    setResults([]);
    setNoShow(null);
    // 고객을 고르면 그 고객의 노쇼 횟수를 함께 보여 준다 (C8)
    api<{ noShowCount: number }>(`/api/customers/${c.customerId}`, { silent: true })
      .then((d) => setNoShow(d.noShowCount))
      .catch(() => setNoShow(null));
  };

  const hasCustomer = editing ? hadCustomer : v.customerMode === 'pick';

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
      toast(editing ? '예약을 변경했어요.' : '예약을 등록했어요.');
      onSaved(values.date);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message);
        if (err.code === 'reservation_conflict') setTick((n) => n + 1); // 그 사이 시간이 찼을 수 있어 가능 시각을 다시 읽는다
      } else {
        setError('잠시 후 다시 시도해 주세요');
      }
    } finally {
      setBusy(false);
    }
  }

  const title = editing ? '일정 변경' : '예약 등록';

  return (
    <div className="overlay" onClick={() => !busy && onClose()}>
      <form className="card modal" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <h2 className="h2" style={{ fontSize: '19px' }}>
          {title}
        </h2>

        {!ready ? (
          <p className="meta">불러오는 중…</p>
        ) : (
          <>
            <div>
              <span className="lbl">예약 경로</span>
              <div className="pills">
                {STORE_FIRST.map((c) => (
                  <button key={c} type="button" className="pill" aria-pressed={v.channel === c} onClick={() => set({ channel: c })}>
                    {CHANNEL_LABEL[c]}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <span className="lbl">고객</span>
              {editing ? (
                <p className="cust-fixed">{hadCustomer ? customerName || '이름 없음' : '고객 없는 예약'}</p>
              ) : (
                <>
                  {v.customerMode === 'pick' && v.customerId !== null ? (
                    <div className="cust-sel">
                      <b>{customerName}</b>
                      {noShow !== null ? <span className={'num' + (noShow > 0 ? ' warn' : '')}>노쇼 {noShow}회</span> : null}
                      <button type="button" className="ib" aria-label="고객 다시 고르기" onClick={() => set({ customerId: null })}>
                        ×
                      </button>
                    </div>
                  ) : (
                    <>
                      <input
                        className="inp"
                        placeholder="이름 또는 휴대폰 뒷 4자리로 검색"
                        value={search}
                        disabled={v.customerMode === 'none'}
                        onChange={(e) => setSearch(e.target.value)}
                        autoFocus
                      />
                      {search.trim() && results.length > 0 ? (
                        <ul className="cust-list">
                          {results.map((c) => (
                            <li key={c.customerId}>
                              <button type="button" onClick={() => pickCustomer(c)}>
                                <b>{c.name}</b>
                                <span className="num">{c.phone}</span>
                              </button>
                            </li>
                          ))}
                        </ul>
                      ) : null}
                      {search.trim() && results.length === 0 ? <p className="meta" style={{ marginTop: 6 }}>검색 결과가 없어요.</p> : null}
                    </>
                  )}
                  <label className="chk" style={{ marginTop: '8px' }}>
                    <input
                      type="checkbox"
                      checked={v.customerMode === 'none'}
                      onChange={(e) => set(e.target.checked ? { customerMode: 'none', customerId: null, memo: '' } : { customerMode: 'pick' })}
                    />
                    <span>고객 없이 시간만 막기 (다른 앱으로 받은 예약)</span>
                  </label>
                </>
              )}
            </div>

            <div className="grid3">
              <div>
                <span className="lbl">날짜</span>
                <input className="inp" type="date" value={v.date} onChange={(e) => set({ date: e.target.value })} />
              </div>
              <div>
                <span className="lbl">담당 스탭</span>
                <select className="inp" value={v.staffId ?? ''} onChange={(e) => set({ staffId: Number(e.target.value) })}>
                  {staff.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <span className="lbl">소요 시간</span>
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
                      {m >= 60 ? `${Math.floor(m / 60)}시간${m % 60 ? ' 30분' : ''}` : '30분'}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div>
              <span className="lbl">시작 시각</span>
              <select className="inp" value={startTime} disabled={loaded === null} onChange={(e) => set({ startTime: e.target.value })}>
                <option value="">{loaded === null ? '불러오는 중…' : '시각 선택'}</option>
                {options.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
              {loaded !== null && options.length === 0 ? (
                <p className="hint" style={{ marginTop: 8 }}>
                  이 날짜에는 예약할 수 있는 시각이 없어요. 영업시간 밖이거나 휴무일, 또는 이미 예약이 찬 시간이에요.
                </p>
              ) : null}
            </div>

            <div>
              <span className="lbl">예정 시술 (1개 이상 필수)</span>
              {menus !== null && menus.length === 0 ? (
                <p className="hint">등록된 시술 메뉴가 없어요. 매장 설정에서 메뉴를 먼저 등록해 주세요.</p>
              ) : (
                <div className="pills">
                  {(menus ?? []).map((m) => {
                    const on = v.menuIds.includes(m.id);
                    return (
                      <button
                        key={m.id}
                        type="button"
                        className="pill"
                        aria-pressed={on}
                        onClick={() => set({ menuIds: on ? v.menuIds.filter((id) => id !== m.id) : [...v.menuIds, m.id] })}
                      >
                        {m.name} · {m.durationMinutes}분
                      </button>
                    );
                  })}
                </div>
              )}
              <label className="chk" style={{ marginTop: '8px' }}>
                <input type="checkbox" checked={v.consultFirst} onChange={(e) => set({ consultFirst: e.target.checked })} />
                <span>상담 후 진행 — 손님이 시술을 정하지 못했어요. 가장 가능성 높은 시술을 골라 주세요.</span>
              </label>
            </div>

            {hasCustomer ? (
              <div>
                <span className="lbl">메모</span>
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

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
          <button type="button" className="btn" disabled={busy} onClick={onClose}>
            취소
          </button>
          <button type="submit" className="btn btn-p" disabled={busy || !ready}>
            {busy ? '저장 중…' : editing ? '변경 저장' : '등록'}
          </button>
        </div>
      </form>
    </div>
  );
}
