import { useRef, useState, type FormEvent } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { api, ApiError } from '@/lib/api';
import { IMPORT_COLUMNS, fileProblem, templateCsv, type ImportResult } from '@/lib/customerImport';
import styles from './CustomerImportPage.module.css';
import type { MessageKey } from '@/lib/messages/ko';
import { t } from '@/lib/i18n';

// CSV 고객 일괄 등록 (FR-CUS-13, B4 · F4 · F5 · F50). POST /api/customers/import (multipart, CUSTOMER_WRITE).
// 동기 처리라 응답이 올 때까지 처리 중으로 두고 다시 올리지 못하게 한다 (F4). 파일 · 행 검사는 서버가 한다 (NFR-08).

const COLUMN_GUIDE: [string, MessageKey, MessageKey][] = [
  ['이름', 'customer.required', 'customer.import.guideName'],
  ['휴대폰', 'customer.required', 'customer.import.guidePhone'],
  ['성별', 'customer.optional', 'customer.import.guideGender'],
  ['출생연도', 'customer.optional', 'customer.import.guideBirth'],
  ['메모', 'customer.optional', 'customer.import.guideMemo'],
  ['방문일', 'customer.optional', 'customer.import.guideVisit'],
  ['시술카테고리', 'customer.optional', 'customer.import.guideCategory'],
];

const errorText = (err: unknown) => (err instanceof ApiError ? err.message : t('common.pin.tryLater'));

function downloadTemplate() {
  const url = URL.createObjectURL(new Blob([templateCsv()], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = 'keepsy-customers-template.csv';
  a.click();
  URL.revokeObjectURL(url);
}

export default function CustomerImportPage() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<ImportResult | null>(null);

  const problem = file ? fileProblem(file) : null;

  function pick(next: File | null) {
    setFile(next);
    setError('');
    setResult(null);
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!file || problem || !confirmed || busy) return;
    setBusy(true);
    setError('');
    setResult(null);
    const body = new FormData();
    body.append('file', file);
    body.append('consentConfirmed', 'true');
    try {
      const r = await api<ImportResult>('/api/customers/import', { method: 'POST', body, silent: true });
      setResult(r);
      toast(t('customer.import.toast', { n: r.createdCustomers }));
      // 같은 파일을 실수로 다시 올리지 않게 고른 파일을 비운다 (다시 올려도 중복으로 건너뛴다)
      setFile(null);
      if (inputRef.current) inputRef.current.value = '';
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <form className={styles.wrap} onSubmit={onSubmit}>
        <div className={styles.headRow}>
          <div className={styles.head}>
            <h1 className={styles.title}>{t('customer.import.title')}</h1>
            <p className={styles.sub}>
              {t('customer.import.sub')}
            </p>
          </div>
          <AppLink href="/customers" className={styles.secondary}>
            {t('customer.toList')}
          </AppLink>
        </div>

        <section className={styles.card} aria-labelledby="import-template">
          <div className={styles.cardHead}>
            <h2 id="import-template" className={styles.cardTitle}>
              {t('customer.import.step1')}
            </h2>
            <button type="button" className={styles.secondary} onClick={downloadTemplate}>
              {t('customer.import.template')}
            </button>
          </div>
          <p className={styles.hint}>
            {t('customer.import.headerPre')}<code className={styles.code}>{IMPORT_COLUMNS.join(',')}</code>{t('customer.import.headerPost')}
            {t('customer.import.maxRows')}
          </p>
          <div className={styles.table} role="table" aria-label={t('customer.import.columnsLabel')}>
            {COLUMN_GUIDE.map(([col, need, desc]) => (
              <div key={col} className={styles.row} role="row">
                <span role="cell">
                  <b>{col}</b>
                </span>
                <span role="cell" className={need === 'customer.required' ? styles.must : styles.muted}>
                  {t(need)}
                </span>
                <span role="cell" className={styles.muted}>
                  {t(desc)}
                </span>
              </div>
            ))}
          </div>
          <p className={styles.hint}>
            {t('customer.import.defaultConsent')}
          </p>
        </section>

        <section className={styles.card} aria-labelledby="import-upload">
          <h2 id="import-upload" className={styles.cardTitle}>
            {t('customer.import.step2')}
          </h2>
          <input
            ref={inputRef}
            className={styles.file}
            type="file"
            accept=".csv,text/csv"
            disabled={busy}
            onChange={(e) => pick(e.target.files?.[0] ?? null)}
            aria-label={t('customer.import.fileLabel')}
          />
          {problem && (
            <p className={styles.error} role="alert">
              {problem}
            </p>
          )}
          <label className={styles.consent}>
            <input type="checkbox" checked={confirmed} disabled={busy} onChange={(e) => setConfirmed(e.target.checked)} />
            <span>
              <b>{t('customer.import.confirm')}</b>
              <span className={styles.consentDesc}>{t('customer.import.confirmDesc')}</span>
            </span>
          </label>
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          <div className={styles.actions}>
            <span className={styles.hint}>{busy ? t('customer.import.busyHint') : t('customer.import.wholeFile')}</span>
            <button type="submit" className={styles.primary} disabled={!file || Boolean(problem) || !confirmed || busy}>
              {busy ? t('customer.import.processing') : t('customer.import.run')}
            </button>
          </div>
        </section>

        {result && (
          <section className={styles.card} aria-labelledby="import-result" aria-live="polite">
            <h2 id="import-result" className={styles.cardTitle}>
              {t('customer.import.result')}
            </h2>
            <div className={styles.stats}>
              <div className={styles.stat}>
                <b className="num">{t('customer.import.persons', { n: result.createdCustomers })}</b>
                <span>{t('customer.import.newCustomers')}</span>
              </div>
              <div className={styles.stat}>
                <b className="num">{t('customer.import.cases', { n: result.addedVisits })}</b>
                <span>{t('customer.import.addedVisits')}</span>
              </div>
              <div className={styles.stat}>
                <b className="num">{t('customer.import.cases', { n: result.addedNotes })}</b>
                <span>{t('customer.import.addedNotes')}</span>
              </div>
              <div className={styles.stat}>
                <b className="num">{t('customer.import.rows', { n: result.skippedDuplicates })}</b>
                <span>{t('customer.import.skipped')}</span>
              </div>
              <div className={styles.stat}>
                <b className={`num ${result.errorCount ? styles.bad : ''}`}>{t('customer.import.rows', { n: result.errorCount })}</b>
                <span>{t('customer.import.errors')}</span>
              </div>
            </div>
            {result.errors.length > 0 && (
              <>
                <p className={styles.hint}>{t('customer.import.errorHint')}</p>
                <div className={styles.table} role="table" aria-label={t('customer.import.errorRowsLabel')}>
                  {result.errors.map((x) => (
                    <div key={x.row} className={styles.errRow} role="row">
                      <span role="cell" className="num">
                        <b>{t('customer.import.rows', { n: x.row })}</b>
                      </span>
                      <span role="cell" className={styles.muted}>
                        {x.reason}
                      </span>
                    </div>
                  ))}
                </div>
              </>
            )}
          </section>
        )}
      </form>
    </>
  );
}
