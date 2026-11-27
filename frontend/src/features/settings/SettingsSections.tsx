import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { detailsText } from '@/lib/reservationForm';
import { t } from '@/lib/i18n';
import {
  SETTING_RULES,
  settingsToValues,
  toSettingsRequest,
  validateSettings,
  type SettingKey,
  type SettingsValues,
} from '@/lib/settings';

// 예측 상태 기준 (FR-SET-03) · 발송 설정 (FR-SET-04) · 보관 기간 (FR-SET-06). 세 구역이 한 `PUT /api/settings`를 쓰므로 저장 버튼은 하나다.
// GET · PUT /api/settings (docs CONTRACTS 6장 「매장 설정 (SET)」). 하나라도 틀리면 서버는 아무것도 바꾸지 않는다 (F40).

type SettingsResponse = Record<SettingKey, number>;

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
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

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

  const dirty = JSON.stringify(values) !== JSON.stringify(saved);
  const change = (key: SettingKey, value: string) => {
    setError('');
    setValues({ ...values, [key]: value });
  };

  async function save() {
    if (!values) return;
    const problem = validateSettings(values);
    if (problem) return setError(problem);
    setBusy(true);
    setError('');
    try {
      await api('/api/settings', { method: 'PUT', body: toSettingsRequest(values), silent: true });
      setSaved(values);
      toast(t('settings.prefs.saved'));
    } catch (err) {
      setError(err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : t('common.pin.tryLater'));
    } finally {
      setBusy(false);
    }
  }

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
      </section>

      {error ? (
        <p className="st-err" role="alert">
          {error}
        </p>
      ) : null}
      <div className="st-savebar">
        <span className="st-meta">{dirty ? t('settings.unsaved') : t('settings.noChange')}</span>
        <button type="button" className="st-btn p" disabled={!dirty || busy} onClick={() => void save()}>
          {busy ? t('common.saving') : t('settings.prefs.saveBtn')}
        </button>
      </div>
    </>
  );
}
