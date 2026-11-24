import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { detailsText } from '@/lib/reservationForm';
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
        <h2>예측 · 발송 · 보관 설정</h2>
        <p className="st-meta">
          불러오지 못했어요.{' '}
          <button
            type="button"
            className="st-btn"
            onClick={() => {
              setLoadFailed(false);
              setReload((n) => n + 1);
            }}
          >
            다시 시도
          </button>
        </p>
      </section>
    );
  }
  if (!values || !saved) {
    return (
      <section className="st-card">
        <h2>예측 · 발송 · 보관 설정</h2>
        <p className="st-meta">불러오는 중…</p>
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
      toast('설정값을 저장했어요');
    } catch (err) {
      setError(err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : '잠시 후 다시 시도해 주세요');
    } finally {
      setBusy(false);
    }
  }

  const years = Number(values.retentionYears);
  const yearsValid = Number.isInteger(years) && years >= 1 && years <= 5;

  return (
    <>
      <section id="st-status" className="st-card">
        <h2>예측 상태 기준</h2>
        <div className="st-grid" style={{ alignItems: 'flex-end' }}>
          <NumberField id="churnSignalCapDays" values={values} onChange={change} flex="0 0 220px" />
          <p className="st-meta" style={{ flex: '1 1 260px' }}>
            이탈 신호 기간은 고객의 해당 시술 평균 간격(최근 5개, 방문 3회 이상)을 쓰고, 기록이 부족하면 시술 기본 주기 최대값을 써요. 이 상한을 넘으면 장기 미방문으로 봐요.
            기본 120일이에요.
          </p>
        </div>
        <dl>
          <div className="st-def">
            <dt className="st-lbl">
              <span className="st-tag">예정</span>
            </dt>
            <dd>다음 방문 예측일 전이에요. 아직 챙길 필요가 없어요.</dd>
          </div>
          <div className="st-def">
            <dt className="st-lbl">
              <span className="st-tag">주기 도래</span>
            </dt>
            <dd>예측 시작일~종료일 사이예요. 예약이 없으면 안내를 고려해요.</dd>
          </div>
          <div className="st-def">
            <dt className="st-lbl">
              <span className="st-tag w">이탈 신호</span>
            </dt>
            <dd>예측 종료일이 지나 이탈 신호 기간 안에 있어요.</dd>
          </div>
          <div className="st-def">
            <dt className="st-lbl">
              <span className="st-tag w">장기 미방문</span>
            </dt>
            <dd>이탈 신호 기간까지 지나도록 방문이 없어요.</dd>
          </div>
        </dl>
      </section>

      <section id="st-send" className="st-card">
        <h2>발송 설정</h2>
        <div className="st-grid">
          <NumberField id="attributionDays" values={values} onChange={change} />
          <NumberField id="dailySendLimit" values={values} onChange={change} />
          <NumberField id="resendBlockDays" values={values} onChange={change} />
          <NumberField id="virtualFailurePercent" values={values} onChange={change} />
        </div>
        <p className="st-meta">
          귀속 기간은 캠페인 뒤 이 기간 안의 재방문을 캠페인 성과로 봐요. 재발송 제한 기간이 0이면 제한이 없어요. 가상 발송 실패 비율은 실제 전송이 아니라 발송 결과를 시연하려고 일부 메시지를
          실패로 처리하는 비율이에요.
        </p>
        <div className="st-note">(광고)와 무료 수신 거부 문구는 광고 메시지에 자동으로 들어가요. 야간(21시~다음 날 08시)에는 광고 메시지를 보낼 수 없어요. 발신번호와 수신 거부 번호는 위쪽 매장 정보에서 바꿔요.</div>
      </section>

      <section id="st-privacy" className="st-card">
        <h2>개인정보 보관 기간</h2>
        <div className="st-grid" style={{ alignItems: 'flex-end' }}>
          <div className="st-field" style={{ flex: '0 0 240px' }}>
            <label className="st-lbl" htmlFor="set-retentionYears">
              마지막 방문 후 보관 기간
            </label>
            <select id="set-retentionYears" className="st-fld" value={values.retentionYears} onChange={(e) => change('retentionYears', e.target.value)}>
              {[1, 2, 3, 4, 5].map((y) => (
                <option key={y} value={String(y)}>
                  {y}년{y === 3 ? ' (기본)' : ''}
                </option>
              ))}
            </select>
          </div>
          <p className="st-meta" style={{ flex: '1 1 260px' }}>
            {yearsValid ? `마지막 방문 후 ${years}년이 지난 고객이 파기 대상으로 표시돼요. 바꾸면 고객 등록 때의 동의서 고지 문구도 같이 바뀌어요.` : '보관 기간을 1~5년 중에서 골라 주세요.'}
          </p>
        </div>
        <p className="st-meta">파기 대상은 자동으로 지워지지 않아요. 원장이 목록을 확인한 뒤 삭제를 승인해야 삭제돼요. 고객 정보 접근 기록은 2년간 보관해요.</p>
      </section>

      {error ? (
        <p className="st-err" role="alert">
          {error}
        </p>
      ) : null}
      <div className="st-savebar">
        <span className="st-meta">{dirty ? '저장되지 않은 변경 사항이 있어요' : '변경 사항 없음'}</span>
        <button type="button" className="st-btn p" disabled={!dirty || busy} onClick={() => void save()}>
          {busy ? '저장 중…' : '설정값 저장'}
        </button>
      </div>
    </>
  );
}
