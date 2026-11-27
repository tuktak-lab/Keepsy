import { useState } from 'react';
import { ApiError, api } from '@/lib/api';
import { t } from '@/lib/i18n';
import { CONSENT_METHODS, toNewCustomerRequest, validateNewCustomer, type ConsentMethod } from '@/lib/newCustomer';
import { detailsText } from '@/lib/reservationForm';

// 예약 폼 안에서 신규 고객을 바로 등록한다 (FR-CUS-01, 예약 화면에서 등록 · 개인정보 동의 필수).
// POST /api/customers (docs CONTRACTS 6장 「고객 (CUS)」). 바깥 예약 폼 <form> 안에 놓이므로 <form>을 또 쓰지 않는다.

export function NewCustomerForm(props: {
  initialName: string;
  onCreated: (customer: { id: number; name: string }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(props.initialName);
  const [phone, setPhone] = useState('');
  const [privacyAgreed, setPrivacyAgreed] = useState(false);
  const [method, setMethod] = useState<ConsentMethod>('VERBAL');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function create() {
    const values = { name, phone, privacyAgreed, method };
    const problem = validateNewCustomer(values);
    if (problem) return setError(problem);
    setBusy(true);
    setError('');
    try {
      const res = await api<{ customerId: number }>('/api/customers', { method: 'POST', body: toNewCustomerRequest(values), silent: true });
      props.onCreated({ id: res.customerId, name: name.trim() });
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'duplicate') setError(t('reservation.newCustomer.duplicate'));
        else setError(err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message);
      } else {
        setError(t('common.pin.tryLater'));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      className="newcust"
      onKeyDown={(e) => {
        // 이 칸에서 Enter를 눌러도 바깥 예약 폼이 제출되지 않게, 고객 등록만 한다
        if (e.key === 'Enter' && e.target instanceof HTMLInputElement && e.target.type !== 'checkbox') {
          e.preventDefault();
          if (!busy) void create();
        }
      }}
    >
      <span className="lbl">{t('reservation.newCustomer.title')}</span>
      <div className="grid2">
        <input className="inp" placeholder={t('reservation.newCustomer.name')} aria-label={t('reservation.newCustomer.name')} maxLength={50} value={name} autoFocus onChange={(e) => setName(e.target.value)} />
        <input
          className="inp"
          placeholder={t('reservation.newCustomer.phonePlaceholder')}
          aria-label={t('reservation.newCustomer.phone')}
          inputMode="tel"
          maxLength={13}
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
        />
      </div>
      <label className="chk">
        <input type="checkbox" checked={privacyAgreed} onChange={(e) => setPrivacyAgreed(e.target.checked)} />
        <span>{t('reservation.newCustomer.privacy')}</span>
      </label>
      <div className="newcust-method">
        <span className="meta">{t('reservation.newCustomer.method')}</span>
        <div className="pills">
          {CONSENT_METHODS.map(([value, label]) => (
            <button key={value} type="button" className="pill" aria-pressed={method === value} onClick={() => setMethod(value)}>
              {label}
            </button>
          ))}
        </div>
      </div>
      {error ? (
        <span className="err" role="alert">
          {error}
        </span>
      ) : null}
      <div className="newcust-actions">
        <button type="button" className="btn btn-sm" disabled={busy} onClick={props.onCancel}>
          {t('common.cancel')}
        </button>
        <button type="button" className="btn btn-sm btn-p" disabled={busy} onClick={() => void create()}>
          {busy ? t('reservation.newCustomer.registering') : t('reservation.newCustomer.submit')}
        </button>
      </div>
    </div>
  );
}
