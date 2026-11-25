import { useEffect, useState } from 'react';
import { api, type Page } from '@/lib/api';
import { CATEGORY_LABEL, MESSAGE_KIND_LABEL, RESERVATION_STATUS_LABEL, dotDate } from '@/lib/customerDetail';
import { formatDateTime } from '@/lib/format';
import type { ReceivedMessage } from '../types';
import { AreaError, errorText } from './common';

// 수신 이력 · 캠페인 연결 기록 (FR-CUS-03, FR-ANL-04). CAMPAIGN_VIEW 설정을 따르므로(명세서 2장) 이 탭을 열 때만 부른다.

export function MessagesTab(props: { customerId: number }) {
  const [items, setItems] = useState<ReceivedMessage[] | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api<Page<ReceivedMessage>>(`/api/customers/${props.customerId}/messages?size=50`, { silent: true })
      .then((r) => setItems(r.items))
      .catch((err) => setError(errorText(err)));
  }, [props.customerId]);

  return (
    <section className="card col">
      <h2 className="h2" style={{ paddingBottom: 8 }}>
        수신 이력
      </h2>
      <AreaError text={error} />
      {items?.length === 0 && <p className="meta">받은 메시지가 없어요.</p>}
      {items?.map((m) => (
        <div className="trow" key={m.messageId} style={{ gridTemplateColumns: '150px 1fr' }}>
          <span className="meta num">{formatDateTime(m.receivedAt)}</span>
          <span className="col">
            <strong>
              {m.campaign?.name ?? MESSAGE_KIND_LABEL[m.kind]}{' '}
              <span className="meta" style={{ fontWeight: 400 }}>
                · {MESSAGE_KIND_LABEL[m.kind]}
                {m.category && ` · ${CATEGORY_LABEL[m.category]}`}
              </span>
            </strong>
            {m.reservations.map((r) => (
              <span className="sub" key={`r${r.id}`}>
                예약 {formatDateTime(r.startAt)} · {RESERVATION_STATUS_LABEL[r.status]}
              </span>
            ))}
            {m.visits.map((v) => (
              <span className="sub" key={`v${v.id}`}>
                방문 {dotDate(v.visitDate)}
              </span>
            ))}
          </span>
        </div>
      ))}
    </section>
  );
}
