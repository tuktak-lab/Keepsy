import { useRef, useState } from 'react';
import { useLoad } from '@/hooks/useLoad';
import { toast } from '@/components/common/Toast';
import { api, type Page } from '@/lib/api';
import { CATEGORY_LABEL, dotDate } from '@/lib/customerDetail';
import { formatDateTime } from '@/lib/format';
import { deleteVisitConfirm, visitRows } from '@/lib/visitEdit';
import type { PaymentRecord } from '@/lib/closing';
import { PaymentList } from '../../reservation/PaymentList';
import type { HistoryVisit } from '../types';
import { AreaError, errorText } from './common';
import { t } from '@/lib/i18n';
import { VisitEditor } from './VisitEditor';

// 시술 히스토리북 (FR-CUS-07 · 08, C6, A13). 방문별 시술 · 담당 · 사진 · 메모를 최신순으로, 사진만 모아 보는 갤러리 보기.
// 사진은 사진 동의 고객만 올린다 (서버가 consent_required). JPG · PNG, 장당 10MB, 방문당 10장은 서버가 다시 검사한다.
// 방문 수정 · 삭제(FR-VIS-03, CUSTOMER_EDIT)와 결제 이력 · 정정(FR-ANL-04 · FR-PAY-02, SALES_ANALYTICS · PAYMENT_EDIT)도 여기서 한다 (F76).
// 결제 이력은 매출 정보라 「결제 이력 보기」를 누를 때만 불러온다 (잠겨 있으면 그때 PIN).

const PAGE = 10;
const MAX_BYTES = 10 * 1024 * 1024;

/**
 * 공용 utils/motion.ts는 src가 바뀐 이미지에 kp-img-wait(투명)를 붙이고 다음 프레임에 load 감시를 단다.
 * 로컬처럼 한 프레임 안에 다 받으면 load를 놓쳐 투명으로 남으므로, 사진은 직접 load에서 뗀다.
 */
const shown = (e: React.SyntheticEvent<HTMLImageElement>) => e.currentTarget.classList.remove('kp-img-wait');

export function HistoryTab(props: { customerId: number; photoAgreed: boolean }) {
  const [actionError, setActionError] = useState('');
  const [gallery, setGallery] = useState(false);
  const [uploading, setUploading] = useState<number | null>(null);
  const [editing, setEditing] = useState<number | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [payments, setPayments] = useState<PaymentRecord[] | null>(null);
  const [paymentsError, setPaymentsError] = useState('');
  // 지금까지 펼친 페이지 수. 서명 URL이 5분 뒤 만료되므로 더 보기 · 올린 뒤에는 처음부터 그만큼 다시 받는다
  const pages = useRef(1);

  const history = useLoad(String(props.customerId), () =>
    api<Page<HistoryVisit>>(`/api/customers/${props.customerId}/history?page=0&size=${pages.current * PAGE}`, { silent: true }),
  );
  const visits = history.data?.items ?? [];
  const total = history.data?.totalElements ?? 0;
  const loaded = history.data !== null || history.error !== null;
  const error = actionError || (history.error ? errorText(history.error) : '');

  function load(nextPages: number) {
    pages.current = nextPages;
    return history.reload();
  }

  async function upload(visitId: number, files: FileList | null) {
    if (!files?.length) return;
    const list = [...files];
    const wrong = list.find((f) => !/\.(jpe?g|png)$/i.test(f.name) || f.size > MAX_BYTES);
    if (wrong) return setActionError(t('customer.history.wrongFile', { name: wrong.name }));
    setUploading(visitId);
    setActionError('');
    let done = 0;
    try {
      for (const f of list) {
        const form = new FormData();
        form.append('file', f);
        await api(`/api/visits/${visitId}/photos`, { method: 'POST', body: form, silent: true });
        done++;
      }
      toast(t('customer.history.uploaded', { n: done }));
    } catch (err) {
      setActionError(`${done ? t('customer.history.uploadedThen', { n: done }) : ''}${errorText(err)}`);
    } finally {
      setUploading(null);
      await load(Math.ceil(visits.length / PAGE) || 1);
    }
  }

  async function loadPayments() {
    setPaymentsError('');
    try {
      const page = await api<Page<PaymentRecord>>(`/api/customers/${props.customerId}/payments?size=100`, { silent: true });
      setPayments(page.items);
    } catch (err) {
      setPaymentsError(errorText(err));
    }
  }

  async function removeVisit(v: HistoryVisit) {
    if (!window.confirm(deleteVisitConfirm(v.imported))) return;
    setDeleting(v.visitId);
    setActionError('');
    try {
      await api(`/api/visits/${v.visitId}`, { method: 'DELETE', silent: true });
      toast(t('visit.delete.done'));
      await load(Math.ceil(visits.length / PAGE) || 1);
      if (payments !== null) await loadPayments();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setDeleting(null);
    }
  }

  const photos = visits.flatMap((v) => v.photos.map((p) => ({ ...p, visitDate: v.visitDate })));

  return (
    <>
    <section className="card col">
      <div className="row between" style={{ paddingBottom: 8 }}>
        <h2 className="h2">{t('customer.history.title', { n: total })}</h2>
        <div className="tabs" role="tablist" aria-label={t('customer.history.viewLabel')}>
          <button type="button" role="tab" aria-selected={!gallery} onClick={() => setGallery(false)}>
            {t('customer.history.byVisit')}
          </button>
          <button type="button" role="tab" aria-selected={gallery} onClick={() => setGallery(true)}>
            {t('customer.history.gallery')}
          </button>
        </div>
      </div>
      {!props.photoAgreed && <p className="meta">{t('customer.history.noPhotoConsent')}</p>}
      <AreaError text={error} />
      {loaded && visits.length === 0 && !error && <p className="meta">{t('customer.history.noVisits')}</p>}

      {gallery ? (
        photos.length === 0 ? (
          loaded && <p className="meta">{t('customer.history.noPhotos')}</p>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(140px,1fr))', gap: 12 }}>
            {photos.map((p) => (
              <figure key={p.photoId} style={{ margin: 0 }} className="col">
                {/* eslint-disable-next-line @next/next/no-img-element -- 서명 URL을 그대로 쓴다 (CONTRACTS 6장) */}
                <img src={p.url} onLoad={shown} alt={t('customer.history.photoAlt', { date: p.visitDate })} style={{ width: '100%', aspectRatio: '1', objectFit: 'cover', borderRadius: 12 }} />
                <figcaption className="meta num" style={{ paddingTop: 6 }}>
                  {dotDate(p.visitDate)}
                </figcaption>
              </figure>
            ))}
          </div>
        )
      ) : (
        visits.map((v) => (
          <div className="trow" key={v.visitId} style={{ gridTemplateColumns: '96px 1fr' }}>
            <strong className="num">{dotDate(v.visitDate)}</strong>
            <span className="col" style={{ gap: 8 }}>
              <span className="meta">{t('customer.history.visitLine', { n: v.serviceCount })}</span>
              {v.services.map((s, i) => (
                <span key={i}>
                  <strong style={{ fontSize: 14.5 }}>{s.menuName ?? t('customer.history.unknownMenu')}</strong>{' '}
                  <span className="meta">
                    · {CATEGORY_LABEL[s.category]}
                    {s.staff && ` · ${s.staff.name}`}
                  </span>
                </span>
              ))}
              {v.notes.map((n) => (
                <span className="sub" key={n.noteId}>
                  {n.content} <span className="meta">— {n.staff.name}, {formatDateTime(n.createdAt)}</span>
                </span>
              ))}
              {v.photos.length > 0 && (
                <span className="row" style={{ gap: 8 }}>
                  {v.photos.map((p) => (
                    // eslint-disable-next-line @next/next/no-img-element -- 서명 URL을 그대로 쓴다 (CONTRACTS 6장)
                    <img key={p.photoId} src={p.url} onLoad={shown} alt={t('customer.history.photoAlt', { date: v.visitDate })} style={{ width: 88, height: 88, objectFit: 'cover', borderRadius: 10 }} />
                  ))}
                </span>
              )}
              {editing === v.visitId ? (
                <VisitEditor
                  visitId={v.visitId}
                  initial={visitRows(v.services, v.imported) ?? []}
                  onDone={(changed) => {
                    setEditing(null);
                    if (changed) load(Math.ceil(visits.length / PAGE) || 1);
                  }}
                />
              ) : (
                <span className="row" style={{ gap: 12 }}>
                  {visitRows(v.services, v.imported) && (
                    <button type="button" className="lk" disabled={deleting !== null} onClick={() => setEditing(v.visitId)}>
                      {t('visit.edit.open')}
                    </button>
                  )}
                  <button type="button" className="lk" disabled={deleting !== null} onClick={() => removeVisit(v)}>
                    {deleting === v.visitId ? t('visit.delete.busy') : t('visit.delete.open')}
                  </button>
                  {v.imported && <span className="meta">{t('visit.edit.importedNote')}</span>}
                </span>
              )}
              {props.photoAgreed && (
                <label className="lk" style={{ alignSelf: 'flex-start' }}>
                  {uploading === v.visitId ? t('customer.history.uploading') : t('customer.history.upload')}
                  <input
                    type="file"
                    accept="image/jpeg,image/png"
                    multiple
                    hidden
                    disabled={uploading !== null}
                    onChange={(e) => {
                      upload(v.visitId, e.target.files);
                      e.target.value = '';
                    }}
                  />
                </label>
              )}
            </span>
          </div>
        ))
      )}
      {visits.length < total && (
        <button type="button" className="btn" style={{ alignSelf: 'center', marginTop: 12 }} onClick={() => load(Math.ceil(visits.length / PAGE) + 1)}>
          {t('customer.moreBtn')}
        </button>
      )}
    </section>
    <section className="card col">
      <div className="row between" style={{ paddingBottom: 8 }}>
        <h2 className="h2">{t('payment.history.title')}</h2>
        {payments === null && (
          <button type="button" className="lk" onClick={loadPayments}>
            {t('payment.history.show')}
          </button>
        )}
      </div>
      <AreaError text={paymentsError} />
      {payments === null ? (
        <p className="meta">{t('payment.history.note')}</p>
      ) : (
        <PaymentList payments={payments} onChanged={loadPayments} empty={t('payment.history.emptyCustomer')} />
      )}
    </section>
    </>
  );
}
