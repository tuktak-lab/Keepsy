import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { detailsText } from '@/lib/reservationForm';
import { NAME_MAX, toNewStaffRequest, validateNewStaff, validatePin, validateStaffName, type NewStaffValues } from '@/lib/staff';
import { PushedReservations, type PushedReservation } from './PushedReservations';
import { t } from '@/lib/i18n';

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
        ? t('settings.staff.dupPin')
        : err.message
    : t('common.pin.tryLater');

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
      <h2 data-guide="settings-staff">{t('settings.staff.title')}</h2>
      <p className="st-meta">
        {t('settings.staff.intro')}
      </p>

      {blocked ? <PushedReservations title={t('settings.staff.remaining', { name: blocked.name })} items={blocked.items} onDismiss={() => setBlocked(null)} /> : null}

      {failed ? (
        <p className="st-meta">
          {t('settings.loadFailedDot')}{' '}
          <button type="button" className="st-btn" onClick={refresh}>
            {t('common.retry')}
          </button>
        </p>
      ) : !staff ? (
        <p className="st-meta">{t('common.loading')}</p>
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
        setError(t('settings.staff.blocked'));
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
      toast(t('settings.staff.renamed'));
      setMode('view');
    });

  const toggleLocked = (allowed: boolean) =>
    run(async () => {
      await api(`/api/staff/${s.id}`, { method: 'PATCH', body: { lockedFeatureAllowed: allowed }, silent: true });
      props.onChanged({ lockedFeatureAllowed: allowed });
      toast(allowed ? t('settings.staff.allowedOn', { name: s.name }) : t('settings.staff.allowedOff', { name: s.name }));
    });

  const savePin = () =>
    run(async () => {
      await api(`/api/staff/${s.id}/pin`, { method: 'PUT', body: { pin }, silent: true });
      toast(t('settings.staff.pinChanged', { name: s.name }));
      setMode('view');
    });

  const deactivate = () =>
    run(async () => {
      await api(`/api/staff/${s.id}/deactivate`, { method: 'POST', silent: true });
      toast(t('settings.staff.deactivated', { name: s.name }));
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
          aria-label={t('settings.staff.newNameAria', { name: s.name })}
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
          {s.name} {s.owner ? <span className="st-tag">{t('settings.staff.owner')}</span> : null} {active ? null : <span className="st-tag w">{t('settings.staff.inactive')}</span>}
        </span>
      )}

      {mode === 'view' && active ? (
        <label className="st-chk" title={s.owner ? t('settings.staff.ownerAlways') : undefined}>
          <input type="checkbox" checked={s.lockedFeatureAllowed} disabled={s.owner || busy} onChange={(e) => void toggleLocked(e.target.checked)} />
          {t('settings.staff.allowLocked')}
        </label>
      ) : null}

      {mode === 'pin' ? (
        <input
          className="st-time num"
          type="password"
          inputMode="numeric"
          autoComplete="new-password"
          maxLength={6}
          placeholder={t('settings.staff.newPin')}
          aria-label={t('settings.staff.newPinAria', { name: s.name })}
          value={pin}
          autoFocus
          onChange={(e) => {
            setError('');
            setPin(e.target.value.replace(/\D/g, ''));
          }}
          onKeyDown={(e) => e.key === 'Enter' && void onPinSave()}
        />
      ) : null}

      {mode === 'deactivate' && active ? <span className="st-meta">{t('settings.staff.deactivateNote')}</span> : null}

      <span style={{ display: 'inline-flex', gap: 8 }}>
        {mode === 'view' && active ? (
          <>
            <button type="button" className="st-btn" onClick={() => open('name')}>
              {t('settings.staff.rename')}
            </button>
            {s.owner ? null : (
              <>
                <button type="button" className="st-btn" onClick={() => open('pin')}>
                  {t('settings.staff.resetPin')}
                </button>
                <button type="button" className="st-btn" onClick={() => open('deactivate')}>
                  {t('settings.staff.deactivate')}
                </button>
              </>
            )}
          </>
        ) : null}
        {mode !== 'view' && active ? (
          <>
            <button type="button" className="st-btn" disabled={busy} onClick={() => open('view')}>
              {t('common.cancel')}
            </button>
            <button type="button" className="st-btn p" disabled={busy} onClick={() => void (mode === 'name' ? onNameSave() : mode === 'pin' ? onPinSave() : deactivate())}>
              {busy ? t('common.saving') : mode === 'deactivate' ? t('settings.staff.deactivate') : t('customer.save')}
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
  // 입력 칸은 [스탭 등록]을 눌러야 펼친다. 등록하거나 취소하면 다시 접는다
  const [open, setOpen] = useState(false);
  const close = () => {
    setOpen(false);
    setValues(empty);
    setError('');
  };
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
      toast(t('settings.staff.addedToast', { name: values.name.trim() }));
      close();
      props.onAdded();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <div>
        <button type="button" className="st-btn p" onClick={() => setOpen(true)}>
          {t('settings.staff.addBtn')}
        </button>
      </div>
    );
  }

  return (
    <div>
      <div className="st-lbl">{t('settings.staff.addTitle')}</div>
      <div className="st-grid" style={{ marginTop: 10, alignItems: 'flex-end' }}>
        <div className="st-field">
          <label className="st-lbl" htmlFor="staff-new-name">
            {t('settings.staff.name')}
          </label>
          <input id="staff-new-name" className="st-fld" maxLength={NAME_MAX} value={values.name} onChange={(e) => edit({ name: e.target.value })} autoFocus />
        </div>
        <div className="st-field" style={{ flex: '0 1 180px' }}>
          <label className="st-lbl" htmlFor="staff-new-pin">
            {t('settings.staff.pinLabel')}
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
            {t('settings.staff.allowLocked')}
          </label>
        </div>
        <div className="st-field" style={{ flex: '0 0 auto' }}>
          <button type="button" className="st-btn p" disabled={busy} onClick={() => void add()}>
            {busy ? t('settings.dayoff.registering') : t('settings.staff.register')}
          </button>
        </div>
        <div className="st-field" style={{ flex: '0 0 auto' }}>
          <button type="button" className="st-btn" disabled={busy} onClick={close}>
            {t('common.cancel')}
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
