import { useEffect, useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { api, ApiError, apiFirst, type Page } from '@/lib/api';
import { dotDate, seoulToday } from '@/lib/customerDetail';
import { BASE_KIND_LABEL, elapsedText, type RetentionBaseKind } from '@/lib/retention';
import styles from './RetentionPage.module.css';
import { t } from '@/lib/i18n';

// 파기 대상 목록 (FR-CUS-12, D3 · B6 · C12). GET /api/customers/retention-expired.
// 원장 PIN(OWNER_PIN)은 서버가 검사하고, owner_pin_required는 api.ts가 원장 PIN 창을 띄워 다시 부른다 (NFR-04).
// 자동으로 지우지 않는다. 원장이 한 명씩 확인한 뒤 고객 삭제(FR-CUS-10)와 같은 API로 지운다.

type Row = { customerId: number; name: string; phone: string; baseDate: string; baseKind: RetentionBaseKind };

const SIZE = 20;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : t('common.pin.tryLater'));

export default function RetentionPage() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  // 삭제 확인 중인 고객과 자동 취소될 확정 예약 수
  const [confirm, setConfirm] = useState<{ row: Row; cancel: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [rowError, setRowError] = useState('');
  const today = seoulToday();

  async function load(count: number) {
    setLoading(true);
    try {
      const page = await apiFirst<Row>('/api/customers/retention-expired', count);
      setRows(page.items);
      setTotal(page.totalElements);
      setError('');
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    api<Page<Row>>(`/api/customers/retention-expired?page=0&size=${SIZE}`, { silent: true })
      .then((page) => {
        setRows(page.items);
        setTotal(page.totalElements);
      })
      .catch((err) => setError(errorText(err)));
  }, []);

  async function ask(row: Row) {
    setBusy(true);
    setRowError('');
    try {
      const r = await api<{ cancelReservationCount: number }>(`/api/customers/${row.customerId}/deletion-preview`, { silent: true });
      setConfirm({ row, cancel: r.cancelReservationCount });
    } catch (err) {
      // 확인을 못 받으면 사유만 보이고 삭제 버튼은 두지 않는다
      setConfirm({ row, cancel: -1 });
      setRowError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  function close() {
    if (busy) return;
    setConfirm(null);
    setRowError('');
  }

  async function remove(row: Row) {
    setBusy(true);
    setRowError('');
    try {
      await api(`/api/customers/${row.customerId}`, { method: 'DELETE', silent: true });
      toast(t('customer.retention.toast', { name: row.name }), 'warn');
      setConfirm(null);
      await load(Math.max(rows?.length ?? SIZE, SIZE));
    } catch (err) {
      setRowError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className={styles.wrap}>
        <div className={styles.head}>
          <div>
            <h1 className={styles.title}>{t('customer.retention.title')}</h1>
            <p className={styles.sub}>
              {t('customer.retention.note1')}
              <br />
              {t('customer.retention.note2')}
            </p>
          </div>
          <div className={styles.headActions}>
            <AppLink href="/settings#st-privacy" className={styles.secondary}>
              {t('customer.retention.setting')}
            </AppLink>
            <AppLink href="/customers" className={styles.secondary}>
              {t('customer.toList')}
            </AppLink>
          </div>
        </div>

        <section className={styles.card} aria-label={t('customer.retention.listLabel')}>
          <div className={styles.cardHead}>
            <h2 className={styles.cardTitle}>
              {t('customer.retention.targets')} <span className="num">{total}</span>{t('customer.retention.targetsUnit')}
            </h2>
            <span className={styles.meta}>{t('customer.retention.sortNote')}</span>
          </div>

          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          {rows === null && !error && <p className={styles.empty}>{t('common.loading')}</p>}
          {rows?.length === 0 && <p className={styles.empty}>{t('customer.retention.empty')}</p>}

          {rows && rows.length > 0 && (
            <div className={styles.table} role="table">
              <div className={styles.headRow} role="row">
                <span role="columnheader">{t('common.customer')}</span>
                <span role="columnheader">{t('customer.retention.colBase')}</span>
                <span role="columnheader">{t('customer.retention.colElapsed')}</span>
                <span role="columnheader" className={styles.right}>
                  {t('customer.retention.colAction')}
                </span>
              </div>
              {rows.map((r) => (
                <div key={r.customerId} className={styles.row} role="row">
                  <span role="cell" className={styles.who}>
                    <AppLink href={`/customers/detail?id=${r.customerId}`}>
                      <strong>{r.name}</strong>
                    </AppLink>
                    <span className={`${styles.meta} num`}>{r.phone}</span>
                  </span>
                  <span role="cell" className={styles.base}>
                    <span className="num">{dotDate(r.baseDate)}</span>
                    <span className={styles.kind}>{BASE_KIND_LABEL[r.baseKind] ?? r.baseKind}</span>
                  </span>
                  <span role="cell" className="num">
                    {elapsedText(r.baseDate, today)}
                  </span>
                  <span role="cell" className={styles.right}>
                    <button type="button" className={styles.deleteButton} disabled={busy} onClick={() => ask(r)}>
                      {t('customer.delete')}
                    </button>
                  </span>

                </div>
              ))}
            </div>
          )}

          {rows && rows.length < total && (
            <button type="button" className={styles.more} disabled={loading} onClick={() => load(rows.length + SIZE)}>
              {t('customer.moreBtn')}
            </button>
          )}
        </section>
      </div>

      {/* 삭제 확인 창. 바깥을 누르거나 Esc로 닫는다 (삭제 중에는 닫지 않는다) */}
      {confirm && (
        <div
          className={styles.overlay}
          onClick={(e) => e.target === e.currentTarget && close()}
          onKeyDown={(e) => e.key === 'Escape' && close()}
        >
          <div className={styles.modal} role="alertdialog" aria-modal="true" aria-labelledby="retention-confirm-title">
            <h2 id="retention-confirm-title" className={styles.modalTitle}>
              {t('customer.retention.confirmTitle', { name: confirm.row.name })}
            </h2>
            {confirm.cancel >= 0 && (
              <p className={styles.modalText}>
                {t('customer.retention.confirmBody1')}
                <br />
                {t('customer.retention.confirmBody2')}
                {confirm.cancel > 0 && (
                  <>
                    <br />
                    <b>{t('customer.retention.cancelNote', { n: confirm.cancel })}</b>
                  </>
                )}
              </p>
            )}
            {rowError && (
              <p className={styles.error} role="alert">
                {rowError}
              </p>
            )}
            <div className={styles.confirmActions}>
              <button type="button" className={styles.secondary} disabled={busy} onClick={close} autoFocus>
                {confirm.cancel >= 0 ? t('customer.retention.keep') : t('common.close')}
              </button>
              {confirm.cancel >= 0 && (
                <button type="button" className={styles.danger} disabled={busy} onClick={() => remove(confirm.row)}>
                  {busy ? t('customer.deleting') : t('customer.delete')}
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
