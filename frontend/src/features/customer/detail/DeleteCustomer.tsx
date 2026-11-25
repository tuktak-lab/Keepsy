import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from '@/components/common/Toast';
import { api } from '@/lib/api';
import { AreaError, errorText } from './common';

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
      toast(`${props.name} 고객 정보를 삭제했어요`, 'warn');
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
          고객 삭제
        </button>
        <AreaError text={error} />
      </span>
    );
  }

  return (
    <div className="note col" role="alertdialog" aria-label="고객 삭제 확인" style={{ gap: 10, maxWidth: 520 }}>
      <strong>{props.name} 고객을 삭제할까요?</strong>
      <span>
        이름 · 휴대폰 · 주의사항 · 취향 · 상담 메모 · 동의 이력 · 사진이 지워지고 되돌릴 수 없어요. 방문 · 결제 기록은 통계용으로 남아요.
        {preview > 0 ? ` 남아 있는 확정 예약 ${preview}건은 자동으로 취소돼요.` : ' 취소될 확정 예약은 없어요.'}
      </span>
      <AreaError text={error} />
      <span className="row" style={{ gap: 8 }}>
        <button type="button" className="btn btn-p" style={{ background: '#8A2F3F', borderColor: '#8A2F3F' }} onClick={remove} disabled={busy}>
          {busy ? '삭제 중…' : '삭제'}
        </button>
        <button type="button" className="btn" onClick={() => setPreview(null)} disabled={busy}>
          그대로 두기
        </button>
      </span>
    </div>
  );
}
