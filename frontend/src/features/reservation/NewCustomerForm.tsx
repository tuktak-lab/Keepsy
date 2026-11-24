import { useState } from 'react';
import { ApiError, api } from '@/lib/api';
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
        if (err.code === 'duplicate') setError('이미 등록된 휴대폰 번호예요. 위에서 검색해 선택해 주세요');
        else setError(err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message);
      } else {
        setError('잠시 후 다시 시도해 주세요');
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
      <span className="lbl">신규 고객 등록</span>
      <div className="grid2">
        <input className="inp" placeholder="이름" aria-label="이름" maxLength={50} value={name} autoFocus onChange={(e) => setName(e.target.value)} />
        <input
          className="inp"
          placeholder="휴대폰 010-0000-0000"
          aria-label="휴대폰"
          inputMode="tel"
          maxLength={13}
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
        />
      </div>
      <label className="chk">
        <input type="checkbox" checked={privacyAgreed} onChange={(e) => setPrivacyAgreed(e.target.checked)} />
        <span>개인정보 수집 · 이용에 동의를 받았어요 (필수)</span>
      </label>
      <div className="newcust-method">
        <span className="meta">동의 받은 방법</span>
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
          취소
        </button>
        <button type="button" className="btn btn-sm btn-p" disabled={busy} onClick={() => void create()}>
          {busy ? '등록 중…' : '고객 등록'}
        </button>
      </div>
    </div>
  );
}
