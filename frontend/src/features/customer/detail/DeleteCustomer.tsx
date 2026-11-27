import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from '@/components/common/Toast';
import { api } from '@/lib/api';
import { AreaError, errorText } from './common';
import { t } from '@/lib/i18n';

// 고객 삭제(파기) (FR-CUS-10, C12). 원장 PIN(OWNER_PIN). 삭제 전 확인에 자동 취소될 확정 예약 수를 보여 준 뒤 부른다.

export function DeleteCustomer(props: { customerId: number; name: string }) {
  const router = useRouter();
  const [preview, setPreview] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function open() {
    setBusy(true);
    setError('');
    try {
      const r = await api<{ cancelReservationCount: number }>(`/api/customers/${props.customerId}/deletion-preview`, { silent: true });
      setPreview(r.cancelReservationCount);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    setError('');
    try {
      await api(`/api/customers/${props.customerId}`, { method: 'DELETE', silent: true });
      toast(t('customer.del.toast', { name: props.name }), 'warn');
      router.replace('/customers');
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  }

  if (preview === null) {
    return (
      <span className="col" style={{ alignItems: 'flex-end', gap: 6 }}>
        <button type="button" className="btn" onClick={open} disabled={busy}>
          {t('customer.del.btn')}
        </button>
        <AreaError text={error} />
      </span>
    );
  }

  return (
    <div className="note col" role="alertdialog" aria-label={t('customer.del.aria')} style={{ gap: 10, maxWidth: 520 }}>
      <strong>{t('customer.del.title', { name: props.name })}</strong>
      <span>
        {t('customer.del.body')}
        {preview > 0 ? t('customer.del.cancelBookings', { n: preview }) : t('customer.del.noBookings')}
      </span>
      <AreaError text={error} />
      <span className="row" style={{ gap: 8 }}>
        <button type="button" className="btn btn-p" style={{ background: '#8A2F3F', borderColor: '#8A2F3F' }} onClick={remove} disabled={busy}>
          {busy ? t('customer.deleting') : t('customer.delete')}
        </button>
        <button type="button" className="btn" onClick={() => setPreview(null)} disabled={busy}>
          {t('customer.retention.keep')}
        </button>
      </span>
    </div>
  );
}
