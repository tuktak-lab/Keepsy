import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { detailsText } from '@/lib/reservationForm';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';
import {
  SETTING_RULES,
  settingsToValues,
  toSettingsRequest,
  validateSettings,
  type SettingKey,
  type SettingsValues,
} from '@/lib/settings';

// 예측 상태 기준 (FR-SET-03) · 발송 설정 (FR-SET-04) · 보관 기간 (FR-SET-06). 세 구역이 한 `PUT /api/settings`를 쓰지만 저장 버튼은 구역마다 둔다.
// 구역 저장은 그 구역에서 바꾼 값만 싣고, 다른 구역은 저장돼 있던 값을 그대로 보낸다 (다른 구역에서 바꾸던 값은 화면에 남는다).
// GET · PUT /api/settings (docs CONTRACTS 6장 「매장 설정 (SET)」). 하나라도 틀리면 서버는 아무것도 바꾸지 않는다 (F40).

type SettingsResponse = Record<SettingKey, number>;
type Part = 'status' | 'send' | 'privacy';

const PART_KEYS: Record<Part, SettingKey[]> = {
  status: ['churnSignalCapDays'],
  send: ['attributionDays', 'dailySendLimit', 'resendBlockDays', 'virtualFailurePercent'],
  privacy: ['retentionYears'],
};
const PART_SAVE: Record<Part, MessageKey> = {
  status: 'settings.prefs.saveStatus',
  send: 'settings.prefs.saveSend',
  privacy: 'settings.prefs.savePrivacy',
};

/** 숫자 입력 칸 하나. 범위 안내를 라벨 옆에 보인다 */
function NumberField(props: { id: SettingKey; values: SettingsValues; onChange: (key: SettingKey, value: string) => void; flex?: string }) {
  const { label, min, max } = SETTING_RULES[props.id];
  return (
    <div className="st-field" style={{ flex: props.flex ?? '1 1 220px' }}>
      <label className="st-lbl" htmlFor={`set-${props.id}`}>
        {label}
      </label>
      <input
        id={`set-${props.id}`}
        className="st-fld num"
        inputMode="numeric"
        value={props.values[props.id]}
        onChange={(e) => props.onChange(props.id, e.target.value)}
      />
      <span className="st-meta num">
        {min.toLocaleString('ko-KR')}~{max.toLocaleString('ko-KR')}
      </span>
    </div>
  );
}

export function SettingsSections() {
  const [saved, setSaved] = useState<SettingsValues | null>(null);
  const [values, setValues] = useState<SettingsValues | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState<{ part: Part; text: string } | null>(null);
  const [busy, setBusy] = useState<Part | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<SettingsResponse>('/api/settings', { silent: true })
      .then((s) => {
        if (cancelled) return;
        const v = settingsToValues(s);
        setSaved(v);
        setValues(v);
      })
      .catch(() => !cancelled && setLoadFailed(true));
    return () => {
      cancelled = true;
    };
  }, [reload]);

  if (loadFailed && !values) {
    return (
      <section className="st-card">
        <h2>{t('settings.prefs.title')}</h2>
        <p className="st-meta">
          {t('settings.loadFailedDot')}{' '}
          <button
            type="button"
            className="st-btn"
            onClick={() => {
              setLoadFailed(false);
              setReload((n) => n + 1);
            }}
          >
            {t('common.retry')}
          </button>
        </p>
      </section>
    );
  }
  if (!values || !saved) {
    return (
      <section className="st-card">
        <h2>{t('settings.prefs.title')}</h2>
        <p className="st-meta">{t('common.loading')}</p>
      </section>
    );
  }

  const dirtyOf = (part: Part) => PART_KEYS[part].some((k) => values[k] !== saved[k]);
  const change = (key: SettingKey, value: string) => {
    setError(null);
    setValues({ ...values, [key]: value });
  };

  async function save(part: Part) {
    if (!values || !saved) return;
    const next = { ...saved };
    for (const k of PART_KEYS[part]) next[k] = values[k];
    const problem = validateSettings(next);
    if (problem) return setError({ part, text: problem });
    setBusy(part);
    setError(null);
    try {
      await api('/api/settings', { method: 'PUT', body: toSettingsRequest(next), silent: true });
      setSaved(next);
      toast(t('settings.prefs.saved'));
    } catch (err) {
      setError({ part, text: err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : t('common.pin.tryLater') });
    } finally {
      setBusy(null);
    }
  }

  const saveBar = (part: Part) => (
    <>
      {error?.part === part ? (
        <p className="st-err" role="alert">
          {error.text}
        </p>
      ) : null}
      <div className="st-savebar">
        <span className="st-meta">{dirtyOf(part) ? t('settings.unsaved') : t('settings.noChange')}</span>
        <button type="button" className="st-btn p" disabled={!dirtyOf(part) || busy !== null} onClick={() => void save(part)}>
          {busy === part ? t('common.saving') : t(PART_SAVE[part])}
        </button>
      </div>
    </>
  );

  const years = Number(values.retentionYears);
  const yearsValid = Number.isInteger(years) && years >= 1 && years <= 5;

  return (
    <>
      <section id="st-status" className="st-card">
        <h2>{t('settings.prefs.statusTitle')}</h2>
        <div className="st-grid" style={{ alignItems: 'flex-end' }}>
          <NumberField id="churnSignalCapDays" values={values} onChange={change} flex="0 0 220px" />
          <p className="st-meta" style={{ flex: '1 1 260px' }}>
            {t('settings.prefs.churnNote')}
            {t('settings.prefs.defaultNote')}
          </p>
        </div>
        <dl>
          <div className="st-def">
            <dt className="st-lbl">
              <span className="st-tag">{t('dashboard.seg.scheduled')}</span>
            </dt>
            <dd>{t('settings.prefs.scheduledDesc')}</dd>
          </div>
          <div className="st-def">
            <dt className="st-lbl">
              <span className="st-tag">{t('prediction.status.due')}</span>
            </dt>
            <dd>{t('settings.prefs.dueDesc')}</dd>
          </div>
          <div className="st-def">
            <dt className="st-lbl">
              <span className="st-tag w">{t('prediction.status.churn')}</span>
            </dt>
            <dd>{t('settings.prefs.churnDesc')}</dd>
          </div>
          <div className="st-def">
            <dt className="st-lbl">
              <span className="st-tag w">{t('prediction.status.long')}</span>
            </dt>
            <dd>{t('settings.prefs.longDesc')}</dd>
          </div>
        </dl>
        {saveBar('status')}
      </section>

      <section id="st-send" className="st-card">
        <h2>{t('settings.prefs.sendTitle')}</h2>
        <div className="st-grid">
          <NumberField id="attributionDays" values={values} onChange={change} />
          <NumberField id="dailySendLimit" values={values} onChange={change} />
          <NumberField id="resendBlockDays" values={values} onChange={change} />
          <NumberField id="virtualFailurePercent" values={values} onChange={change} />
        </div>
        <p className="st-meta">
          {t('settings.prefs.sendNote')}
        </p>
        <div className="st-note">{t('settings.prefs.sendAuto')}</div>
        {saveBar('send')}
      </section>

      <section id="st-privacy" className="st-card">
        <h2>{t('settings.prefs.privacyTitle')}</h2>
        <div className="st-grid" style={{ alignItems: 'flex-end' }}>
          <div className="st-field" style={{ flex: '0 0 240px' }}>
            <label className="st-lbl" htmlFor="set-retentionYears">
              {t('settings.prefs.sinceLastVisit')}
            </label>
            <select id="set-retentionYears" className="st-fld" value={values.retentionYears} onChange={(e) => change('retentionYears', e.target.value)}>
              {[1, 2, 3, 4, 5].map((y) => (
                <option key={y} value={String(y)}>
                  {t('settings.prefs.yearOption', { y })}{y === 3 ? t('settings.prefs.yearDefault') : ''}
                </option>
              ))}
            </select>
          </div>
          <p className="st-meta" style={{ flex: '1 1 260px' }}>
            {yearsValid ? t('settings.prefs.yearsHint', { years }) : t('settings.prefs.yearsPick')}
          </p>
        </div>
        <p className="st-meta">{t('settings.prefs.privacyNote')}</p>
        {saveBar('privacy')}
      </section>
    </>
  );
}
