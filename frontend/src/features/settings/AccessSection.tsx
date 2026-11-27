import { useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { detailsText } from '@/lib/reservationForm';
import { refreshSession, useSession, type Access } from '@/lib/session';
import { ACCESS_LABEL, FEATURES, changedFeatures, validateOwnerPin, type OwnerPinValues } from '@/lib/staff';

// 기능 잠금 · 원장 PIN 재설정 (FR-SET-05, FR-ACC-05 · 09). docs CONTRACTS 4장 ACC
// - 기능별 공용/PIN: 지금 값은 GET /api/session의 features, 바꾸기는 PUT /api/feature-locks (OWNER_PIN). 바꾼 키만 보낸다 (F38)
//   고정 항목(대시보드 · 동의 · 원장 PIN 기능)은 바꿀 수 없다 (E8). 바꾼 값은 다음 요청부터 바로 서버 권한 검사에 쓰인다
// - 원장 PIN 재설정: PUT /api/owner-pin. 매장 계정 비밀번호로 본인 확인 (F11 · F14). 비밀번호 실패는 로그인 실패 횟수에 들어간다

const errorText = (err: unknown): string =>
  err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : '잠시 후 다시 시도해 주세요';

export function FeatureLockSection() {
  const session = useSession();
  const saved = session?.features ?? null;
  const [edited, setEdited] = useState<Record<string, Access> | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const values = edited ?? saved;
  const changes = saved && values ? changedFeatures(saved, values) : {};
  const dirty = Object.keys(changes).length > 0;

  async function save() {
    setBusy(true);
    setError('');
    try {
      await api('/api/feature-locks', { method: 'PUT', body: { features: changes }, silent: true });
      await refreshSession();
      setEdited(null);
      toast('기능별 공용 / PIN 설정을 저장했어요');
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section id="st-lock" className="st-card">
      <h2 data-guide="settings-lock">기능별 공용 / PIN</h2>
      <p className="st-meta">「공용」은 근무 중인 스탭 누구나 쓰고, 「PIN」은 잠긴 기능 사용이 허용된 스탭이 PIN을 넣어야 열려요. 고정 항목은 바꿀 수 없어요. 저장하면 바로 적용돼요.</p>
      {!values ? (
        <p className="st-meta">불러오는 중…</p>
      ) : (
        <div>
          {FEATURES.map((f) => {
            const v = values[f.key];
            return (
              <div className="st-def" key={f.key} style={{ gridTemplateColumns: '1fr auto', alignItems: 'center' }}>
                <span>{f.label}</span>
                {f.fixed ? (
                  <span className="st-tag w" title="고정 항목은 바꿀 수 없어요">
                    {v ? ACCESS_LABEL[v] : '-'} · 고정
                  </span>
                ) : (
                  <span className="st-seg" role="group" aria-label={`${f.label} 공용 또는 PIN`}>
                    {(['PUBLIC', 'PIN'] as const).map((a) => (
                      <button
                        key={a}
                        type="button"
                        aria-pressed={v === a}
                        onClick={() => {
                          setError('');
                          setEdited({ ...values, [f.key]: a });
                        }}
                      >
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
      <div className="st-savebar">
        <span className="st-meta">{dirty ? `바꾼 항목 ${Object.keys(changes).length}개` : '변경 사항 없음'}</span>
        {dirty ? (
          <button type="button" className="st-btn" disabled={busy} onClick={() => setEdited(null)}>
            되돌리기
          </button>
        ) : null}
        <button type="button" className="st-btn p" disabled={!dirty || busy} onClick={() => void save()}>
          {busy ? '저장 중…' : '공용 / PIN 저장'}
        </button>
      </div>
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
      toast('원장 PIN을 바꿨어요. 다음 PIN 입력부터 새 PIN을 써요');
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'duplicate' ? '이 매장의 다른 스탭이 이미 쓰는 PIN이에요. 다른 PIN을 정해 주세요' : errorText(err));
      setValues({ ...values, password: '' });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section id="st-owner-pin" className="st-card">
      <h2>원장 PIN 재설정</h2>
      <p className="st-meta">원장 PIN을 잊었을 때 매장 계정 비밀번호로 본인을 확인하고 새 PIN을 정해요. 비밀번호를 5번 연속 틀리면 15분 동안 잠겨요.</p>
      {!expanded ? (
        <div>
          <button type="button" className="st-btn" onClick={() => setExpanded(true)}>
            원장 PIN을 잊었어요
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
                매장 계정 비밀번호
              </label>
              <input id="owner-password" className="st-fld" type="password" autoComplete="current-password" value={values.password} onChange={(e) => edit({ password: e.target.value })} />
            </div>
            <div className="st-field" style={{ flex: '0 1 180px' }}>
              <label className="st-lbl" htmlFor="owner-pin">
                새 원장 PIN
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
                새 PIN 확인
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
                취소
              </button>
              <button type="submit" className="st-btn p" disabled={busy}>
                {busy ? '확인 중…' : '원장 PIN 바꾸기'}
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
