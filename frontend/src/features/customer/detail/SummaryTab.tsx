import { useLoad } from '@/hooks/useLoad';
import { api, type Page } from '@/lib/api';
import {
  CATEGORY_LABEL,
  RESERVATION_STATUS_LABEL,
  STATUS_CHIP,
  dotDate,
  predictionText,
  rhythmPoints,
  seoulToday,
  shortDate,
} from '@/lib/customerDetail';
import { formatDateTime } from '@/lib/format';
import type { CategoryPrediction, CustomerConsents, CustomerDetail, CustomerReservation, HistoryVisit } from '../types';
import { AreaError, errorText } from './common';
import { CautionCard, PreferenceCard } from './ProfileCards';
import { t } from '@/lib/i18n';

// 고객 요약 탭 (FR-CUS-03 · 04 · 05 · 09 · 11, FR-PRD-02). 예측은 INSIGHT, 예약은 RESERVATION 설정을 따른다 (명세서 2장).

const RHYTHM_COLOR = ['#7A4450', '#D9AFA6', '#B88A6A', '#8C9A86', '#9A8FB0'];

export function SummaryTab(props: {
  customerId: number;
  detail: CustomerDetail;
  /** null은 아직 모름 */
  consents: CustomerConsents | null;
  reload: () => Promise<void>;
}) {
  const { customerId, detail } = props;
  // 영역마다 따로 받아, 실패한 영역만 다시 시도한다 (#487)
  const pred = useLoad(String(customerId), () =>
    api<{ predictions: CategoryPrediction[] }>(`/api/customers/${customerId}/predictions`, { silent: true }),
  );
  const resv = useLoad(String(customerId), () =>
    api<Page<CustomerReservation>>(`/api/customers/${customerId}/reservations?size=10`, { silent: true }),
  );
  const history = useLoad(String(customerId), () =>
    api<Page<HistoryVisit>>(`/api/customers/${customerId}/history?size=20`, { silent: true }),
  );
  const predictions = pred.data?.predictions ?? null;
  const reservations = resv.data?.items ?? null;
  const visits = history.data?.items ?? [];

  const s = detail.visitSummary;
  const today = seoulToday();
  const healthAgreed = props.consents ? props.consents.current.some((c) => c.item === 'HEALTH' && c.agreed) : null;
  const points = rhythmPoints(visits);
  // 완료된 예약 옆에 그날 방문 메모(시술 컬러 등)를 보인다. 예약 응답에 방문 ID가 없어 방문일로 짝짓는다
  const notesByDate = new Map(visits.map((v) => [v.visitDate, v.notes.map((n) => n.content)]));
  const colorOf = (c: string) => RHYTHM_COLOR[Object.keys(CATEGORY_LABEL).indexOf(c) % RHYTHM_COLOR.length];

  return (
    <>
      <section className="card col" style={{ gap: '14px' }}>
        <h2 className="h2">{t('customer.summary.title')}</h2>
        <div className="row" style={{ gap: 0 }}>
          <div className="stat">
            <span className="meta">{t('customer.summary.totalVisits')}</span>
            <b>{t('customer.visits', { n: s.visitCount })}</b>
          </div>
          <div className="stat">
            <span className="meta">{t('customer.summary.treatments')}</span>
            <b>{t('customer.summary.treatmentsCount', { n: s.serviceCount })}</b>
          </div>
          <div className="stat">
            <span className="meta">{t('customer.summary.firstVisit')}</span>
            <b>{s.firstVisitDate ? dotDate(s.firstVisitDate) : '—'}</b>
          </div>
          <div className="stat">
            <span className="meta">{t('customer.summary.lastVisit')}</span>
            <b>{s.lastVisitDate ? dotDate(s.lastVisitDate) : '—'}</b>
          </div>
        </div>
        {s.categories.length === 0 && <p className="meta">{t('customer.summary.noVisits')}</p>}
        <div className="col">
          {s.categories.map((c) => (
            <div className="sumrow" key={c.category}>
              <strong>
                {CATEGORY_LABEL[c.category]} <span className="num">{t('customer.visits', { n: c.visitCount })}</span>
              </strong>
              <span>
                {s.menus
                  .filter((m) => m.category === c.category)
                  .map((m) => t('customer.summary.menuCount', { name: m.name ?? t('customer.history.unknownMenu'), n: m.count }))
                  .join(', ')}
              </span>
              <span className="num">
                <span className="meta">{t('customer.summary.avgInterval')}</span>
                {c.averageIntervalDays === null ? '—' : t('customer.summary.days', { n: c.averageIntervalDays })}
              </span>
            </div>
          ))}
        </div>
      </section>

      <section className="col" style={{ gap: '12px' }}>
        <h2 className="h2">{t('customer.summary.predTitle')}</h2>
        <AreaError text={pred.error ? errorText(pred.error) : ''} onRetry={pred.reload} />
        {predictions?.length === 0 && <p className="meta">{t('customer.summary.predEmpty')}</p>}
        <div className="cgrid">
          {predictions?.map((p) => {
            const chip = p.status ? STATUS_CHIP[p.status] : null;
            return (
              <div className="pcard" key={p.category}>
                <div className="row between">
                  <strong>{CATEGORY_LABEL[p.category]}</strong>
                  {chip ? (
                    <span className="chip" style={{ background: chip[1], color: chip[2] }}>
                      <i></i>
                      {t(chip[0])}
                    </span>
                  ) : (
                    <span className="chip" style={{ background: '#EEF2EE', color: '#3F6150' }}>
                      <i></i>{t('customer.summary.hasBooking')}
                    </span>
                  )}
                </div>
                <span style={{ fontWeight: 500 }}>{predictionText(p, today)}</span>
                {p.startDate && p.endDate && !p.endSignal && (
                  <span className="num" style={{ color: '#7A4450', fontSize: '13px' }}>
                    {t('customer.summary.predRange', { from: shortDate(p.startDate), to: shortDate(p.endDate) })}
                  </span>
                )}
                {p.lastServiceDate && <span className="meta">{t('customer.summary.lastTreatment', { date: dotDate(p.lastServiceDate) })}</span>}
              </div>
            );
          })}
        </div>
        <p className="meta">{t('customer.summary.predNote')}</p>
      </section>

      <AreaError text={history.error ? `${t('customer.summary.rhythm')}: ${errorText(history.error)}` : ''} onRetry={history.reload} />

      {points.length > 0 && (
        <section className="card col" style={{ gap: '14px' }}>
          <div className="row between">
            <h2 className="h2">{t('customer.summary.rhythm')}</h2>
            <span className="row meta" style={{ gap: 10 }}>
              {[...new Set(points.flatMap((p) => p.categories))].map((c) => (
                <span className="row" style={{ gap: 4 }} key={c}>
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: colorOf(c) }}></span>
                  {CATEGORY_LABEL[c]}
                </span>
              ))}
            </span>
          </div>
          <div style={{ display: 'flex', overflowX: 'auto', paddingBottom: '4px' }}>
            {points.map((r, i) => (
              <div key={r.date} className="col" style={{ alignItems: 'center', minWidth: '96px', gap: '6px', position: 'relative' }}>
                <span style={{ width: 'calc(100% - 22px)', height: '2px', background: i === 0 ? 'transparent' : '#E3D5CE', position: 'absolute', top: '5px', left: 'calc(-50% + 11px)' }}></span>
                <span style={{ width: '12px', height: '12px', borderRadius: '50%', background: colorOf(r.categories[0]), position: 'relative' }}></span>
                <span className="num" style={{ fontSize: '12.5px', fontWeight: 600 }}>
                  {shortDate(r.date)}
                </span>
                <span className="meta">{r.categories.map((c) => CATEGORY_LABEL[c]).join(' · ')}</span>
              </div>
            ))}
            {predictions
              ?.filter((p) => p.startDate && p.endDate && !p.endSignal)
              .map((p) => (
                <div key={p.category} className="col" style={{ alignItems: 'center', minWidth: '96px', gap: '6px', position: 'relative' }}>
                  <span style={{ width: 'calc(100% - 22px)', borderTop: '2px dashed #7A4450', position: 'absolute', top: '5px', left: 'calc(-50% + 11px)' }}></span>
                  <span style={{ width: '12px', height: '12px', borderRadius: '50%', border: `2.5px solid ${colorOf(p.category)}`, background: '#fff', boxSizing: 'border-box', position: 'relative' }}></span>
                  <span className="num" style={{ fontSize: '12.5px', fontWeight: 600, color: '#7A4450' }}>
                    {shortDate(p.startDate!)}~{shortDate(p.endDate!)}
                  </span>
                  <span className="meta">{t('customer.summary.predOf', { category: CATEGORY_LABEL[p.category] })}</span>
                </div>
              ))}
          </div>
        </section>
      )}

      <div className="grid2">
        <PreferenceCard customerId={customerId} detail={detail} onSaved={props.reload} />
        <CautionCard customerId={customerId} detail={detail} healthAgreed={healthAgreed} onSaved={props.reload} />
      </div>

      <section className="card col" style={{ gap: '4px' }}>
        <h2 className="h2" style={{ paddingBottom: 6 }}>
          {t('customer.summary.bookings')}
        </h2>
        <AreaError text={resv.error ? errorText(resv.error) : ''} onRetry={resv.reload} />
        {reservations?.length === 0 && <p className="meta">{t('customer.summary.noBookings')}</p>}
        {reservations?.map((r) => (
          <div className="trow" key={r.id} style={{ gridTemplateColumns: '150px 1fr auto' }}>
            <span className="num">{formatDateTime(r.startAt)}</span>
            <span className="col">
              <strong>{r.services.map((x) => x.name).join(', ') || t('customer.summary.undecided')}</strong>
              <span className="meta">
                {r.staff.name} · {t('common.minutes', { n: r.minutes })}
              </span>
              {/* 시술 컬러처럼 예약할 때 정한 내용은 예약 메모에 적는다 (CONTRACTS 6장, F66) */}
              {r.memo && (
                <span className="sub" style={{ marginTop: 4 }}>
                  <span className="meta">{t('customer.summary.bookingMemo')}</span>
                  {r.memo}
                </span>
              )}
              {r.status === 'COMPLETED' &&
                notesByDate.get(formatDateTime(r.startAt).slice(0, 10))?.map((n, i) => (
                  <span className="sub" key={i} style={{ marginTop: 4 }}>
                    <span className="meta">{t('customer.summary.visitMemo')}</span>
                    {n}
                  </span>
                ))}
            </span>
            <span className="chip" style={{ background: r.status === 'CONFIRMED' ? '#F9EBE8' : '#F3EEEE', color: r.status === 'NO_SHOW' ? '#8A2F3F' : '#4A3F3B' }}>
              {RESERVATION_STATUS_LABEL[r.status]}
            </span>
          </div>
        ))}
      </section>

    </>
  );
}
