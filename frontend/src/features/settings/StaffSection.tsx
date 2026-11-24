import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { detailsText } from '@/lib/reservationForm';
import { NAME_MAX, toNewStaffRequest, validateNewStaff, validatePin, validateStaffName, type NewStaffValues } from '@/lib/staff';
import { PushedReservations, type PushedReservation } from './PushedReservations';

// 스탭 · PIN (FR-SET-05, FR-ACC-03 · 04). 모두 원장 PIN 기능이다 (OWNER_PIN, api.ts가 PIN 창을 띄움). docs CONTRACTS 4장 ACC
// - 목록: GET /api/staff?includeInactive=true (비활성 포함, 활성 먼저)
// - 등록 POST /api/staff · 이름 · 잠긴 기능 허용 PATCH /api/staff/{staffId} · PIN 재설정 PUT /api/staff/{staffId}/pin
// - 비활성화 POST /api/staff/{staffId}/deactivate: 남은 확정 예약이 있으면 conflict 409와 예약 목록. 옮기거나 마감 · 취소한 뒤 다시 한다
// 원장 스탭은 비활성화 · PIN 재설정(여기서) · 잠긴 기능 끄기를 할 수 없다. 원장 PIN은 「원장 PIN 재설정」(PUT /api/owner-pin)으로만 바꾼다.

type Staff = { id: number; name: string; owner: boolean; lockedFeatureAllowed: boolean; regularDaysOff: string[]; status: 'ACTIVE' | 'INACTIVE' };

const errorText = (err: unknown): string =>
  err instanceof ApiError
    ? err.code === 'invalid'
      ? (detailsText(err.details) ?? err.message)
      : err.code === 'duplicate'
        ? '이 매장의 다른 스탭이 이미 쓰는 PIN이에요. 다른 PIN을 정해 주세요'
        : err.message
    : '잠시 후 다시 시도해 주세요';

export function StaffSection() {
  const [staff, setStaff] = useState<Staff[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [reload, setReload] = useState(0);
  const [blocked, setBlocked] = useState<{ name: string; items: PushedReservation[] } | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<Staff[]>('/api/staff?includeInactive=true', { silent: true })
      .then((list) => !cancelled && setStaff(list))
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [reload]);

  const refresh = () => {
    setFailed(false);
    setReload((n) => n + 1);
  };
  const patchLocal = (id: number, patch: Partial<Staff>) => setStaff((list) => (list ?? []).map((s) => (s.id === id ? { ...s, ...patch } : s)));

  return (
    <section id="st-staff" className="st-card">
      <h2>스탭 · PIN</h2>
      <p className="st-meta">
        스탭마다 PIN으로 근무를 시작해요. 「잠긴 기능 사용 허용」을 켠 스탭만 PIN으로 잠긴 기능을 열 수 있어요. 비활성 스탭은 근무 시작 · PIN 사용을 할 수 없고, 작성한 기록 · 메모는
        그대로 남아요.
      </p>

      {blocked ? <PushedReservations title={`${blocked.name} 님에게 남은`} items={blocked.items} onDismiss={() => setBlocked(null)} /> : null}

      {failed ? (
        <p className="st-meta">
          불러오지 못했어요.{' '}
          <button type="button" className="st-btn" onClick={refresh}>
            다시 시도
          </button>
        </p>
      ) : !staff ? (
        <p className="st-meta">불러오는 중…</p>
      ) : (
        <div>
          {staff.map((s) => (
            <StaffRow
              key={s.id}
              staff={s}
              onChanged={(patch) => patchLocal(s.id, patch)}
              onBlocked={(items) => setBlocked({ name: s.name, items })}
              onDeactivated={() => {
                setBlocked(null);
                refresh();
              }}
            />
          ))}
        </div>
      )}

      <NewStaffForm onAdded={refresh} />
    </section>
  );
}

/** 스탭 한 줄: 이름 · 잠긴 기능 허용 · PIN 재설정 · 비활성화 */
function StaffRow(props: { staff: Staff; onChanged: (patch: Partial<Staff>) => void; onBlocked: (items: PushedReservation[]) => void; onDeactivated: () => void }) {
  const { staff: s } = props;
  const active = s.status === 'ACTIVE';
  const [mode, setMode] = useState<'view' | 'name' | 'pin' | 'deactivate'>('view');
  const [name, setName] = useState(s.name);
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const open = (m: typeof mode) => {
    setError('');
    setName(s.name);
    setPin('');
    setMode(m);
  };

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (err) {
      if (err instanceof ApiError && err.code === 'conflict' && Array.isArray(err.details)) {
        props.onBlocked(err.details as PushedReservation[]);
        setError('남은 확정 예약이 있어 비활성화하지 않았어요. 위 목록의 예약을 옮기거나 마감 · 취소해 주세요');
        setMode('view');
      } else setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  const saveName = () =>
    run(async () => {
      await api(`/api/staff/${s.id}`, { method: 'PATCH', body: { name: name.trim() }, silent: true });
      props.onChanged({ name: name.trim() });
      toast('이름을 바꿨어요');
      setMode('view');
    });

  const toggleLocked = (allowed: boolean) =>
    run(async () => {
      await api(`/api/staff/${s.id}`, { method: 'PATCH', body: { lockedFeatureAllowed: allowed }, silent: true });
      props.onChanged({ lockedFeatureAllowed: allowed });
      toast(allowed ? `${s.name} 님이 잠긴 기능을 열 수 있어요` : `${s.name} 님의 잠긴 기능 사용을 껐어요`);
    });

  const savePin = () =>
    run(async () => {
      await api(`/api/staff/${s.id}/pin`, { method: 'PUT', body: { pin }, silent: true });
      toast(`${s.name} 님의 PIN을 바꿨어요`);
      setMode('view');
    });

  const deactivate = () =>
    run(async () => {
      await api(`/api/staff/${s.id}/deactivate`, { method: 'POST', silent: true });
      toast(`${s.name} 님을 비활성화했어요`);
      setMode('view');
      props.onDeactivated();
    });

  // 화면에서 먼저 검사하고, 통과하면 저장한다 (서버도 다시 검사한다)
  const onNameSave = async () => {
    const problem = validateStaffName(name);
    if (problem) return setError(problem);
    await saveName();
  };
  const onPinSave = async () => {
    const problem = validatePin(pin);
    if (problem) return setError(problem);
    await savePin();
  };

  return (
    <div className="st-row" style={active ? undefined : { opacity: 0.6 }}>
      {mode === 'name' ? (
        <input
          className="st-fld grow"
          aria-label={`${s.name} 새 이름`}
          maxLength={NAME_MAX}
          value={name}
          autoFocus
          onChange={(e) => {
            setError('');
            setName(e.target.value);
          }}
          onKeyDown={(e) => e.key === 'Enter' && void onNameSave()}
        />
      ) : (
        <span className="nm grow">
          {s.name} {s.owner ? <span className="st-tag">원장</span> : null} {active ? null : <span className="st-tag w">비활성</span>}
        </span>
      )}

      {mode === 'view' && active ? (
        <label className="st-chk" title={s.owner ? '원장은 항상 잠긴 기능을 열 수 있어요' : undefined}>
          <input type="checkbox" checked={s.lockedFeatureAllowed} disabled={s.owner || busy} onChange={(e) => void toggleLocked(e.target.checked)} />
          잠긴 기능 사용 허용
        </label>
      ) : null}

      {mode === 'pin' ? (
        <input
          className="st-time num"
          type="password"
          inputMode="numeric"
          autoComplete="new-password"
          maxLength={6}
          placeholder="새 PIN"
          aria-label={`${s.name} 새 PIN`}
          value={pin}
          autoFocus
          onChange={(e) => {
            setError('');
            setPin(e.target.value.replace(/\D/g, ''));
          }}
          onKeyDown={(e) => e.key === 'Enter' && void onPinSave()}
        />
      ) : null}

      {mode === 'deactivate' && active ? <span className="st-meta">비활성화하면 근무 시작 · PIN 사용을 할 수 없어요. 다시 켜는 기능은 없어요.</span> : null}

      <span style={{ display: 'inline-flex', gap: 8 }}>
        {mode === 'view' && active ? (
          <>
            <button type="button" className="st-btn" onClick={() => open('name')}>
              이름 수정
            </button>
            {s.owner ? null : (
              <>
                <button type="button" className="st-btn" onClick={() => open('pin')}>
                  PIN 재설정
                </button>
                <button type="button" className="st-btn" onClick={() => open('deactivate')}>
                  비활성화
                </button>
              </>
            )}
          </>
        ) : null}
        {mode !== 'view' && active ? (
          <>
            <button type="button" className="st-btn" disabled={busy} onClick={() => open('view')}>
              취소
            </button>
            <button type="button" className="st-btn p" disabled={busy} onClick={() => void (mode === 'name' ? onNameSave() : mode === 'pin' ? onPinSave() : deactivate())}>
              {busy ? '저장 중…' : mode === 'deactivate' ? '비활성화' : '저장'}
            </button>
          </>
        ) : null}
      </span>

      {/* 오류는 버튼 줄 아래에 한 줄로 */}
      {error ? (
        <span className="st-err" role="alert" style={{ flexBasis: '100%' }}>
          {error}
        </span>
      ) : null}
    </div>
  );
}

/** 스탭 등록 (FR-ACC-03). 개인 계정 · 초대 링크 없이 이름과 PIN만 */
function NewStaffForm(props: { onAdded: () => void }) {
  const empty: NewStaffValues = { name: '', pin: '', lockedFeatureAllowed: false };
  const [values, setValues] = useState<NewStaffValues>(empty);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const edit = (patch: Partial<NewStaffValues>) => {
    setError('');
    setValues({ ...values, ...patch });
  };

  async function add() {
    const problem = validateNewStaff(values);
    if (problem) return setError(problem);
    setBusy(true);
    setError('');
    try {
      await api('/api/staff', { method: 'POST', body: toNewStaffRequest(values), silent: true });
      toast(`${values.name.trim()} 님을 등록했어요`);
      setValues(empty);
      props.onAdded();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <div className="st-lbl">스탭 등록</div>
      <div className="st-grid" style={{ marginTop: 10, alignItems: 'flex-end' }}>
        <div className="st-field">
          <label className="st-lbl" htmlFor="staff-new-name">
            이름
          </label>
          <input id="staff-new-name" className="st-fld" maxLength={NAME_MAX} value={values.name} onChange={(e) => edit({ name: e.target.value })} />
        </div>
        <div className="st-field" style={{ flex: '0 1 180px' }}>
          <label className="st-lbl" htmlFor="staff-new-pin">
            PIN (숫자 4~6자리)
          </label>
          <input
            id="staff-new-pin"
            className="st-fld num"
            type="password"
            inputMode="numeric"
            autoComplete="new-password"
            maxLength={6}
            value={values.pin}
            onChange={(e) => edit({ pin: e.target.value.replace(/\D/g, '') })}
          />
        </div>
        <div className="st-field" style={{ flex: '0 0 auto', justifyContent: 'center', minHeight: 44 }}>
          <label className="st-chk">
            <input type="checkbox" checked={values.lockedFeatureAllowed} onChange={(e) => edit({ lockedFeatureAllowed: e.target.checked })} />
            잠긴 기능 사용 허용
          </label>
        </div>
        <div className="st-field" style={{ flex: '0 0 auto' }}>
          <button type="button" className="st-btn p" disabled={busy} onClick={() => void add()}>
            {busy ? '등록 중…' : '스탭 등록'}
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
