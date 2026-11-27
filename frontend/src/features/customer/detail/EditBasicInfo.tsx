import { useState, type FormEvent } from 'react';
import { toast } from '@/components/common/Toast';
import { api, ApiError } from '@/lib/api';
import { toCustomerEditRequest, validateCustomerEdit, type CustomerEditValues } from '@/lib/customerEdit';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';
import { CHANNELS, GENDERS } from '@/lib/newCustomer';
import type { AcquisitionChannel, CustomerDetail, Gender } from '../types';
import { AreaError, errorText } from './common';

// 고객 기본 정보 수정 (명세서 2장 「고객 기본 정보 수정」, CUSTOMER_EDIT · 기본 PIN, 결정 F78).
// 전체 번호는 조회하지 않는다: 번호 칸은 비워 두고, 바꿀 때만 새 번호를 적는다. PIN이 필요하면 api()가 PIN 입력 창을 띄운다.

export function EditBasicInfo(props: { customerId: number; detail: CustomerDetail; onSaved: () => void }) {
  const [values, setValues] = useState<CustomerEditValues | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const open = () => {
    const d = props.detail;
    setError('');
    setValues({ name: d.name, phone: '', gender: d.gender ?? '', birthYear: d.birthYear ? String(d.birthYear) : '', channel: d.acquisitionChannel ?? '' });
  };
  const close = () => !busy && setValues(null);
  const set = (patch: Partial<CustomerEditValues>) => setValues((v) => (v ? { ...v, ...patch } : v));

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!values || busy) return;
    const invalid = validateCustomerEdit(values, new Date().getFullYear());
    if (invalid) return setError(invalid);
    setBusy(true);
    setError('');
    try {
      await api(`/api/customers/${props.customerId}`, { method: 'PATCH', body: toCustomerEditRequest(values), silent: true });
      toast(t('customer.edit.saved'));
      setValues(null);
      props.onSaved();
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'duplicate' ? t('customer.edit.duplicate') : errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button type="button" className="btn" onClick={open}>
        {t('customer.edit.open')}
      </button>
      {values && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="edit-basic-title"
          onKeyDown={(e) => e.key === 'Escape' && close()}
          onClick={(e) => e.target === e.currentTarget && close()}
          style={{ position: 'fixed', inset: 0, zIndex: 900, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24, background: 'rgba(34,29,27,.32)' }}
        >
          <form className="card col" onSubmit={save} style={{ width: 'min(480px, 100%)', maxHeight: '86vh', overflowY: 'auto', gap: 14, padding: 24 }}>
            <h2 id="edit-basic-title" className="h2" style={{ margin: 0 }}>
              {t('customer.edit.title')}
            </h2>
            <label className="col" style={{ gap: 6 }}>
              <span className="lbl">{t('customer.new.name')}</span>
              <input className="fld" value={values.name} maxLength={50} autoFocus onChange={(e) => set({ name: e.target.value })} />
            </label>
            <label className="col" style={{ gap: 6 }}>
              <span className="lbl">{t('customer.edit.phoneNew')}</span>
              <input className="fld num" inputMode="tel" placeholder="010-0000-0000" value={values.phone} onChange={(e) => set({ phone: e.target.value })} />
              <span className="meta num">{t('customer.edit.phoneHint', { masked: props.detail.phone })}</span>
            </label>
            <label className="col" style={{ gap: 6 }}>
              <span className="lbl">{t('customer.new.gender')}</span>
              <select className="sel" value={values.gender} onChange={(e) => set({ gender: e.target.value as Gender | '' })}>
                {([['', 'customer.new.notSelected'], ...GENDERS] as [Gender | '', MessageKey][]).map(([v, label]) => (
                  <option key={v} value={v}>
                    {t(label)}
                  </option>
                ))}
              </select>
            </label>
            <label className="col" style={{ gap: 6 }}>
              <span className="lbl">{t('customer.new.birthYear')}</span>
              <input className="fld num" inputMode="numeric" maxLength={4} placeholder={t('customer.new.birthPlaceholder')} value={values.birthYear} onChange={(e) => set({ birthYear: e.target.value })} />
            </label>
            <label className="col" style={{ gap: 6 }}>
              <span className="lbl">{t('customer.new.channelLabel')}</span>
              <select className="sel" value={values.channel} onChange={(e) => set({ channel: e.target.value as AcquisitionChannel | '' })}>
                {([['', 'customer.new.notSelected'], ...CHANNELS] as [AcquisitionChannel | '', MessageKey][]).map(([v, label]) => (
                  <option key={v} value={v}>
                    {t(label)}
                  </option>
                ))}
              </select>
            </label>
            <AreaError text={error} />
            <span className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
              <button type="button" className="btn" onClick={close} disabled={busy}>
                {t('common.close')}
              </button>
              <button type="submit" className="btn btn-p" disabled={busy}>
                {busy ? t('common.saving') : t('customer.edit.save')}
              </button>
            </span>
          </form>
        </div>
      )}
    </>
  );
}
