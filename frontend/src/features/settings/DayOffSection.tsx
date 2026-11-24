import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { todayInSeoul, type DayOff } from '@/lib/calendar';
import { useNowMs } from '@/lib/clock';
import {
  REASON_MAX,
  formatDayOffDate,
  monthRange,
  sameDays,
  shiftMonth,
  toDayOffRequest,
  toggleDay,
  validateDayOff,
  type DayOffValues,
} from '@/lib/dayOffs';
import { detailsText } from '@/lib/reservationForm';
import { DAYS, DAY_LABEL } from '@/lib/settings';
import { PushedReservations, type PushedReservation } from './PushedReservations';

// 휴무일 (FR-SET-07, C7). 매장 · 스탭 임시 휴무일과 스탭 정기 휴무 요일. 변경은 원장 PIN이 필요하다 (api.ts가 PIN 창을 띄움).
// - 임시 휴무일: GET · POST /api/day-offs, DELETE /api/day-offs/{dayOffId} (docs CONTRACTS 4장). 조회는 최대 31일이라 달 단위로 본다
// - 스탭 정기 휴무 요일: GET /api/staff?includeInactive=true의 regularDaysOff, 바꾸기는 PATCH /api/staff/{staffId}
// 휴무와 겹치는 확정 예약은 자동 취소하지 않고 목록으로 보여 준다 (F12). 매장 정기 휴무(요일)는 영업시간 구역에서 정한다 (FR-SET-01).

type Staff = { id: number; name: string; owner: boolean; regularDaysOff: string[]; status: 'ACTIVE' | 'INACTIVE' };

const errorText = (err: unknown): string =>
  err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : '잠시 후 다시 시도해 주세요';

export function DayOffSection() {
  const now = useNowMs();
  const today = now ? todayInSeoul(now) : null;

  const [staff, setStaff] = useState<Staff[] | null>(null);
  const [staffFailed, setStaffFailed] = useState(false);
  const [staffReload, setStaffReload] = useState(0);
  // 고른 달. 고르기 전에는 이번 달
  const [picked, setPicked] = useState<{ year: number; month0: number } | null>(null);
  const month = picked ?? (today ? { year: Number(today.slice(0, 4)), month0: Number(today.slice(5, 7)) - 1 } : null);
  const range = month ? monthRange(month.year, month.month0) : null;
  const rangeKey = range ? `${range.from}~${range.to}` : null;
  // 어느 달의 결과인지 키로 들고 있어, 달을 바꾸면 그 달 결과가 올 때까지 「불러오는 중」이 된다
  const [result, setResult] = useState<{ key: string; list: DayOff[] | null } | null>(null);
  const [reload, setReload] = useState(0);
  const [pushed, setPushed] = useState<{ title: string; items: PushedReservation[] }>({ title: '', items: [] });

  useEffect(() => {
    let cancelled = false;
    api<Staff[]>('/api/staff?includeInactive=true', { silent: true })
      .then((list) => !cancelled && setStaff(list))
      .catch(() => !cancelled && setStaffFailed(true));
    return () => {
      cancelled = true;
    };
  }, [staffReload]);

  useEffect(() => {
    if (!rangeKey) return;
    let cancelled = false;
    const [from, to] = rangeKey.split('~');
    api<DayOff[]>(`/api/day-offs?from=${from}&to=${to}`, { silent: true })
      .then((list) => !cancelled && setResult({ key: rangeKey, list }))
      .catch(() => !cancelled && setResult({ key: rangeKey, list: null }));
    return () => {
      cancelled = true;
    };
  }, [rangeKey, reload]);

  const current = result && result.key === rangeKey ? result : null;
  const dayOffs = current?.list ?? null;
  const listFailed = current !== null && current.list === null;
  const setDayOffs = (update: (list: DayOff[]) => DayOff[]) => setResult((r) => (r && r.list ? { ...r, list: update(r.list) } : r));
  const setMonth = (m: { year: number; month0: number }) => setPicked(m);
  const retryList = () => {
    setResult(null);
    setReload((n) => n + 1);
  };
  const loadStaff = () => {
    setStaffFailed(false);
    setStaffReload((n) => n + 1);
  };

  const active = (staff ?? []).filter((s) => s.status === 'ACTIVE');
  const staffName = (id: number | null) => (id === null ? null : ((staff ?? []).find((s) => s.id === id)?.name ?? `스탭 ${id}`));

  return (
    <section id="st-dayoff" className="st-card">
      <h2>휴무일</h2>
      <p className="st-meta">휴무인 날은 예약 시간을 고를 수 없어요. 이미 확정 예약이 있는 날을 휴무로 정하면 그 예약 목록을 보여 드려요. 예약은 자동으로 취소하지 않아요.</p>

      {today && staff ? (
        <DayOffForm
          today={today}
          staff={active}
          onAdded={(items, date) => {
            setPushed({ title: '휴무일에 걸린', items });
            // 등록한 날의 달로 옮겨 바로 보이게 한다
            const y = Number(date.slice(0, 4));
            const m0 = Number(date.slice(5, 7)) - 1;
            if (month && month.year === y && month.month0 === m0) retryList();
            else setMonth({ year: y, month0: m0 });
          }}
        />
      ) : null}

      <PushedReservations title={pushed.title} items={pushed.items} onDismiss={() => setPushed({ title: '', items: [] })} />

      <div>
        <div className="st-row" style={{ borderTop: 0, paddingTop: 0 }}>
          <span className="st-lbl grow">임시 휴무일</span>
          {month ? (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
              <button type="button" className="st-btn" aria-label="이전 달" onClick={() => setMonth(shiftMonth(month.year, month.month0, -1))}>
                ‹
              </button>
              <span className="num" style={{ minWidth: 92, textAlign: 'center', fontWeight: 600 }}>
                {month.year}년 {month.month0 + 1}월
              </span>
              <button type="button" className="st-btn" aria-label="다음 달" onClick={() => setMonth(shiftMonth(month.year, month.month0, 1))}>
                ›
              </button>
            </span>
          ) : null}
        </div>
        {listFailed ? (
          <p className="st-meta">
            불러오지 못했어요.{' '}
            <button type="button" className="st-btn" onClick={retryList}>
              다시 시도
            </button>
          </p>
        ) : !dayOffs ? (
          <p className="st-meta">불러오는 중…</p>
        ) : dayOffs.length === 0 ? (
          <p className="st-meta">이 달에 등록된 휴무일이 없어요.</p>
        ) : (
          dayOffs.map((d) => (
            <DayOffRow
              key={d.id}
              dayOff={d}
              staffName={staffName(d.staffId)}
              past={today !== null && d.date < today}
              onDeleted={() => setDayOffs((list) => list.filter((x) => x.id !== d.id))}
            />
          ))
        )}
      </div>

      <div>
        <div className="st-lbl">스탭 정기 휴무 요일</div>
        <p className="st-meta">요일을 눌러 켜고 끈 뒤 저장해요. 매장 전체가 쉬는 요일은 위 「매장 정보 · 영업시간」에서 정기 휴무로 정해요.</p>
        {staffFailed ? (
          <p className="st-meta">
            스탭 목록을 불러오지 못했어요.{' '}
            <button type="button" className="st-btn" onClick={loadStaff}>
              다시 시도
            </button>
          </p>
        ) : !staff ? (
          <p className="st-meta">불러오는 중…</p>
        ) : active.length === 0 ? (
          <p className="st-meta">활성 스탭이 없어요.</p>
        ) : (
          active.map((s) => (
            <RegularDaysRow
              key={s.id}
              staff={s}
              onSaved={(days, items) => {
                setStaff((list) => (list ?? []).map((x) => (x.id === s.id ? { ...x, regularDaysOff: days } : x)));
                setPushed({ title: `${s.name} 님의 정기 휴무 요일에 걸린`, items });
              }}
            />
          ))
        )}
      </div>
    </section>
  );
}

/** 임시 휴무일 등록 줄 */
function DayOffForm(props: { today: string; staff: Staff[]; onAdded: (pushed: PushedReservation[], date: string) => void }) {
  const empty: DayOffValues = { date: '', target: 'SHOP', reason: '' };
  const [values, setValues] = useState<DayOffValues>(empty);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const edit = (patch: Partial<DayOffValues>) => {
    setError('');
    setValues({ ...values, ...patch });
  };

  async function add() {
    const problem = validateDayOff(values, props.today);
    if (problem) return setError(problem);
    setBusy(true);
    setError('');
    try {
      const res = await api<{ id: number; pushedReservations: PushedReservation[] }>('/api/day-offs', { method: 'POST', body: toDayOffRequest(values), silent: true });
      toast('휴무일을 등록했어요');
      props.onAdded(res?.pushedReservations ?? [], values.date);
      setValues(empty);
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'duplicate' ? '같은 날 같은 대상의 휴무가 이미 있어요' : errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <div className="st-grid">
        <div className="st-field" style={{ flex: '0 1 180px' }}>
          <label className="st-lbl" htmlFor="dayoff-date">
            날짜
          </label>
          <input id="dayoff-date" type="date" className="st-fld num" min={props.today} value={values.date} onChange={(e) => edit({ date: e.target.value })} />
        </div>
        <div className="st-field" style={{ flex: '0 1 200px' }}>
          <label className="st-lbl" htmlFor="dayoff-target">
            대상
          </label>
          <select id="dayoff-target" className="st-fld" value={values.target} onChange={(e) => edit({ target: e.target.value })}>
            <option value="SHOP">매장 전체</option>
            {props.staff.map((s) => (
              <option key={s.id} value={String(s.id)}>
                {s.name}
              </option>
            ))}
          </select>
        </div>
        <div className="st-field">
          <label className="st-lbl" htmlFor="dayoff-reason">
            사유 (선택)
          </label>
          <input id="dayoff-reason" className="st-fld" maxLength={REASON_MAX} placeholder="예: 내부 교육" value={values.reason} onChange={(e) => edit({ reason: e.target.value })} />
        </div>
        <div className="st-field" style={{ flex: '0 0 auto', justifyContent: 'flex-end' }}>
          <button type="button" className="st-btn p" disabled={busy} onClick={() => void add()}>
            {busy ? '등록 중…' : '휴무일 등록'}
          </button>
        </div>
      </div>
      {error ? (
        <p className="st-err" role="alert" style={{ marginTop: 8 }}>
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** 임시 휴무일 한 줄. 삭제는 한 번 더 확인한다 */
function DayOffRow(props: { dayOff: DayOff; staffName: string | null; past: boolean; onDeleted: () => void }) {
  const { dayOff: d } = props;
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function remove() {
    setBusy(true);
    setError('');
    try {
      await api<void>(`/api/day-offs/${d.id}`, { method: 'DELETE', silent: true });
      toast('휴무일을 삭제했어요');
      props.onDeleted();
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  }

  return (
    <div className="st-row">
      <span className="nm num" style={{ width: 120, color: props.past ? '#6B605B' : undefined }}>
        {formatDayOffDate(d.date)}
      </span>
      <span className={props.staffName ? 'st-tag w' : 'st-tag'}>{props.staffName ?? '매장 휴무'}</span>
      <span className="grow st-meta">{d.reason ?? ''}</span>
      {error ? (
        <span className="st-err" role="alert">
          {error}
        </span>
      ) : null}
      {confirm ? (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <span className="st-meta">삭제하면 그날 다시 예약을 받아요.</span>
          <button type="button" className="st-btn" disabled={busy} onClick={() => setConfirm(false)}>
            취소
          </button>
          <button type="button" className="st-btn p" disabled={busy} onClick={() => void remove()}>
            {busy ? '삭제 중…' : '삭제'}
          </button>
        </span>
      ) : (
        <button type="button" className="st-btn" onClick={() => setConfirm(true)}>
          삭제
        </button>
      )}
    </div>
  );
}

/** 스탭 한 명의 정기 휴무 요일 */
function RegularDaysRow(props: { staff: Staff; onSaved: (days: string[], pushed: PushedReservation[]) => void }) {
  const { staff: s } = props;
  const [days, setDays] = useState<string[]>(s.regularDaysOff);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const dirty = !sameDays(days, s.regularDaysOff);

  async function save() {
    setBusy(true);
    setError('');
    try {
      const res = await api<{ pushedReservations: PushedReservation[] }>(`/api/staff/${s.id}`, { method: 'PATCH', body: { regularDaysOff: days }, silent: true });
      toast(`${s.name} 님의 정기 휴무 요일을 저장했어요`);
      props.onSaved(days, res?.pushedReservations ?? []);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="st-row">
      <span className="nm" style={{ width: 96 }}>
        {s.name}
        {s.owner ? <span className="st-meta"> · 원장</span> : null}
      </span>
      <span className="st-seg" role="group" aria-label={`${s.name} 정기 휴무 요일`}>
        {DAYS.map((day, i) => (
          <button key={day} type="button" aria-pressed={days.includes(day)} onClick={() => (setError(''), setDays(toggleDay(days, day)))}>
            {DAY_LABEL[i]}
          </button>
        ))}
      </span>
      <span className="grow st-meta">{days.length === 0 ? '정기 휴무 없음' : `매주 ${days.map((d) => DAY_LABEL[DAYS.indexOf(d as (typeof DAYS)[number])]).join(' · ')} 휴무`}</span>
      {error ? (
        <span className="st-err" role="alert">
          {error}
        </span>
      ) : null}
      {dirty ? (
        <span style={{ display: 'inline-flex', gap: 8 }}>
          <button type="button" className="st-btn" disabled={busy} onClick={() => (setError(''), setDays(s.regularDaysOff))}>
            되돌리기
          </button>
          <button type="button" className="st-btn p" disabled={busy} onClick={() => void save()}>
            {busy ? '저장 중…' : '저장'}
          </button>
        </span>
      ) : null}
    </div>
  );
}
