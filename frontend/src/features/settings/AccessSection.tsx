import { useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { detailsText } from '@/lib/reservationForm';
import { refreshSession, useSession, type Access } from '@/lib/session';
import { ACCESS_LABEL, FEATURES, changedFeatures, validateOwnerPin, type OwnerPinValues } from '@/lib/staff';
import { t } from '@/lib/i18n';

// 기능 잠금 · 원장 PIN 재설정 (FR-SET-05, FR-ACC-05 · 09). docs CONTRACTS 4장 ACC
// - 기능별 공용/PIN: 지금 값은 GET /api/session의 features, 바꾸기는 PUT /api/feature-locks (OWNER_PIN). 바꾼 키만 보낸다 (F38)
//   고정 항목(대시보드 · 동의 · 원장 PIN 기능)은 바꿀 수 없다 (E8). 바꾼 값은 다음 요청부터 바로 서버 권한 검사에 쓰인다
// - 원장 PIN 재설정: PUT /api/owner-pin. 매장 계정 비밀번호로 본인 확인 (F11 · F14). 비밀번호 실패는 로그인 실패 횟수에 들어간다

const errorText = (err: unknown): string =>
  err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : t('common.pin.tryLater');

export function FeatureLockSection() {
  const session = useSession();
  const saved = session?.features ?? null;
  const [pending, setPending] = useState<{ key: string; access: Access } | null>(null); // 저장 중인 한 항목 (화면에는 먼저 보인다)
  const [error, setError] = useState('');

  const values = saved && pending ? { ...saved, [pending.key]: pending.access } : saved;

  // 누르면 그 항목 하나만 바로 저장한다 (따로 저장 버튼 없음)
  async function choose(key: string, label: string, access: 'PUBLIC' | 'PIN') {
    if (!saved || pending || saved[key] === access) return;
    const changes = changedFeatures(saved, { ...saved, [key]: access });
    setPending({ key, access });
    setError('');
    try {
      await api('/api/feature-locks', { method: 'PUT', body: { features: changes }, silent: true });
      await refreshSession();
      toast(t('settings.lock.savedToast', { label, access: ACCESS_LABEL[access] }));
    } catch (err) {
      setError(errorText(err));
    } finally {
      setPending(null);
    }
  }

  return (
    <section id="st-lock" className="st-card">
      <h2 data-guide="settings-lock">{t('settings.lock.title')}</h2>
      <p className="st-meta">{t('settings.lock.note')}</p>
      {!values ? (
        <p className="st-meta">{t('common.loading')}</p>
      ) : (
        <div>
          {FEATURES.map((f) => {
            const v = values[f.key];
            return (
              <div className="st-def" key={f.key} style={{ gridTemplateColumns: '1fr auto', alignItems: 'center' }}>
                <span>{f.label}</span>
                {f.fixed ? (
                  <span className="st-tag w" title={t('settings.lock.fixedTitle')}>
                    {v ? ACCESS_LABEL[v] : '-'} · {t('settings.lock.fixed')}
                  </span>
                ) : (
                  <span className="st-seg" role="group" aria-label={t('settings.lock.groupAria', { label: f.label })}>
                    {(['PUBLIC', 'PIN'] as const).map((a) => (
                      <button key={a} type="button" aria-pressed={v === a} disabled={pending !== null} onClick={() => void choose(f.key, f.label, a)}>
                        {ACCESS_LABEL[a]}
                      </button>
                    ))}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      )}
      {error ? (
        <p className="st-err" role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}

/** 원장 PIN을 잊은 경우에 쓰므로 화면의 원장 PIN 잠금(PinGate) 바깥에 둔다. 처음에는 접혀 있다 */
export function OwnerPinSection() {
  const empty: OwnerPinValues = { password: '', pin: '', pinConfirm: '' };
  const [expanded, setExpanded] = useState(false);
  const [values, setValues] = useState<OwnerPinValues>(empty);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const edit = (patch: Partial<OwnerPinValues>) => {
    setError('');
    setValues({ ...values, ...patch });
  };
  const digits = (s: string) => s.replace(/\D/g, '');
  const close = () => {
    setValues(empty);
    setError('');
    setExpanded(false);
  };

  async function save() {
    const problem = validateOwnerPin(values);
    if (problem) return setError(problem);
    setBusy(true);
    setError('');
    try {
      await api('/api/owner-pin', { method: 'PUT', body: { password: values.password, pin: values.pin }, silent: true });
      close();
      toast(t('settings.owner.pinChanged'));
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'duplicate' ? t('settings.staff.dupPin') : errorText(err));
      setValues({ ...values, password: '' });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section id="st-owner-pin" className="st-card">
      <h2>{t('settings.owner.title')}</h2>
      <p className="st-meta">{t('settings.owner.note')}</p>
      {!expanded ? (
        <div>
          <button type="button" className="st-btn" onClick={() => setExpanded(true)}>
            {t('settings.owner.forgot')}
          </button>
        </div>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          <div className="st-grid" style={{ alignItems: 'flex-end' }}>
            <div className="st-field">
              <label className="st-lbl" htmlFor="owner-password">
                {t('settings.owner.password')}
              </label>
              <input id="owner-password" className="st-fld" type="password" autoComplete="current-password" value={values.password} onChange={(e) => edit({ password: e.target.value })} />
            </div>
            <div className="st-field" style={{ flex: '0 1 180px' }}>
              <label className="st-lbl" htmlFor="owner-pin">
                {t('settings.owner.newPin')}
              </label>
              <input
                id="owner-pin"
                className="st-fld num"
                type="password"
                inputMode="numeric"
                autoComplete="new-password"
                maxLength={6}
                value={values.pin}
                onChange={(e) => edit({ pin: digits(e.target.value) })}
              />
            </div>
            <div className="st-field" style={{ flex: '0 1 180px' }}>
              <label className="st-lbl" htmlFor="owner-pin-confirm">
                {t('settings.owner.newPinConfirm')}
              </label>
              <input
                id="owner-pin-confirm"
                className="st-fld num"
                type="password"
                inputMode="numeric"
                autoComplete="new-password"
                maxLength={6}
                value={values.pinConfirm}
                onChange={(e) => edit({ pinConfirm: digits(e.target.value) })}
              />
            </div>
            <div className="st-field" style={{ flex: '0 0 auto', flexDirection: 'row' }}>
              <button type="button" className="st-btn" disabled={busy} onClick={close}>
                {t('common.cancel')}
              </button>
              <button type="submit" className="st-btn p" disabled={busy}>
                {busy ? t('account.shift.checking') : t('settings.owner.changeBtn')}
              </button>
            </div>
          </div>
        </form>
      )}
      {error ? (
        <p className="st-err" role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}
