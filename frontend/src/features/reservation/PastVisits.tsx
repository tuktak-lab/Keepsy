import { AppLink } from '@/components/common/AppLink';
import { useLoad } from '@/hooks/useLoad';
import { api, type Page } from '@/lib/api';
import { CATEGORY_LABEL, dotDate } from '@/lib/customerDetail';
import type { HistoryVisit } from '../customer/types';

const SIZE = 5;

// 예약 상세 아래 「지난 방문」 (#445). 시술 히스토리북 API(FR-CUS-07, CUSTOMER_VIEW)의 최근 5건만 보여 준다.
// 결제 금액은 이 API에 없다. PIN이 필요하면 api()가 PIN 창을 띄우고, 닫으면 카드 안에 안내만 둔다.
export function PastVisits({ customerId }: { customerId: number }) {
  const { data, error } = useLoad(`past-visits-${customerId}`, () =>
    api<Page<HistoryVisit>>(`/api/customers/${customerId}/history?page=0&size=${SIZE}`, { silent: true }),
  );

  return (
    <section className="card detail-card past-visits" aria-labelledby="past-visits-title">
      <div className="pv-head">
        <h2 id="past-visits-title">지난 방문</h2>
        {data && data.totalElements > 0 ? <span className="meta num">총 {data.totalElements}회</span> : null}
      </div>

      {error ? (
        <p className="meta">지난 방문을 불러오지 못했어요. 고객 정보 보기 권한(PIN)이 필요할 수 있어요.</p>
      ) : !data ? (
        <p className="meta">불러오는 중…</p>
      ) : data.items.length === 0 ? (
        <p className="meta">아직 방문 기록이 없어요.</p>
      ) : (
        <ul className="pv-list">
          {data.items.map((v) => (
            <li key={v.visitId}>
              <strong className="num">{dotDate(v.visitDate)}</strong>
              <div>
                <p>
                  {v.services.map((s, i) => (
                    <span key={i}>
                      {i > 0 ? ', ' : ''}
                      {s.menuName ?? CATEGORY_LABEL[s.category]}
                      {s.staff ? <span className="meta"> · {s.staff.name}</span> : null}
                    </span>
                  ))}
                </p>
                {v.notes[0] ? <p className="meta pv-note">{v.notes[0].content}</p> : null}
              </div>
            </li>
          ))}
        </ul>
      )}

      <p className="pv-foot">
        <AppLink href={`/customers/detail?id=${customerId}`}>전체 이력 보기 ›</AppLink>
      </p>
    </section>
  );
}
