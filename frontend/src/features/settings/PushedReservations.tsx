import { AppLink } from '@/components/common/AppLink';
import { formatDateTime } from '@/lib/format';

// 영업시간 · 휴무 · 스탭 설정을 바꿔 영업시간 밖으로 밀린 확정 예약 목록 (FR-SET-01 · 07, F12).
// 예약은 자동으로 취소하지 않는다. 스탭이 예약 상세에서 옮기거나 취소하게 한다.
// PUT /api/shop · POST /api/day-offs · PATCH /api/staff/{id} 응답의 `pushedReservations`와 같은 모양이다.

export type PushedReservation = { id: number; startAt: string; customerName: string | null };

/** `title`: 무엇 때문에 밀렸는지 (기본 「영업시간 밖으로 밀린」, 휴무일이면 「휴무일에 걸린」) */
export function PushedReservations(props: { items: PushedReservation[]; onDismiss?: () => void; title?: string }) {
  if (props.items.length === 0) return null;
  return (
    <div className="st-note st-pushed" role="status">
      <b>
        {props.title ?? '영업시간 밖으로 밀린'} 확정 예약이 {props.items.length}건 있어요.
      </b>{' '}
      자동으로 취소하지 않아요. 예약을 열어 시간을 옮기거나 취소해 주세요.
      <ul>
        {props.items.map((r) => (
          <li key={r.id}>
            <AppLink href={`/reservations/detail?id=${r.id}`} className="num">
              {formatDateTime(r.startAt)}
            </AppLink>{' '}
            · {r.customerName ?? '고객 없는 예약'}
          </li>
        ))}
      </ul>
      {props.onDismiss ? (
        <button type="button" className="st-btn" onClick={props.onDismiss}>
          닫기
        </button>
      ) : null}
    </div>
  );
}
