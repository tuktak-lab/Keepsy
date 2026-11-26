import { useRef, useState, type FormEvent } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { api, ApiError } from '@/lib/api';
import { IMPORT_COLUMNS, fileProblem, templateCsv, type ImportResult } from '@/lib/customerImport';
import styles from './CustomerImportPage.module.css';

// CSV 고객 일괄 등록 (FR-CUS-13, B4 · F4 · F5 · F50). POST /api/customers/import (multipart, CUSTOMER_WRITE).
// 동기 처리라 응답이 올 때까지 처리 중으로 두고 다시 올리지 못하게 한다 (F4). 파일 · 행 검사는 서버가 한다 (NFR-08).

const COLUMN_GUIDE: [string, string, string][] = [
  ['이름', '필수', '1~50자'],
  ['휴대폰', '필수', '010-0000-0000 형식. 이미 있는 번호면 새로 만들지 않고 그 고객에 방문 · 메모만 더해요'],
  ['성별', '선택', '여성 · 남성 · 기타'],
  ['출생연도', '선택', '4자리 (예: 1990)'],
  ['메모', '선택', '상담 메모로 저장해요. 같은 내용이 이미 있으면 다시 만들지 않아요'],
  ['방문일', '선택', 'YYYY-MM-DD, 오늘까지. 한 행 = 방문 1건'],
  ['시술카테고리', '선택', '네일 · 속눈썹 · 왁싱 · 반영구 · 기타. 방문일과 함께 적어요'],
];

const errorText = (err: unknown) => (err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');

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
      toast(`가져오기를 마쳤어요 · 신규 ${r.createdCustomers}명`);
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
            <h1 className={styles.title}>CSV 일괄 등록</h1>
            <p className={styles.sub}>
              기존에 쓰던 장부의 고객을 한 번에 옮겨요. 방문일을 함께 넣으면 첫날부터 다음 방문을 예측해요.
            </p>
          </div>
          <AppLink href="/customers" className={styles.secondary}>
            고객 목록으로
          </AppLink>
        </div>

        <section className={styles.card} aria-labelledby="import-template">
          <div className={styles.cardHead}>
            <h2 id="import-template" className={styles.cardTitle}>
              1. 템플릿에 고객 채우기
            </h2>
            <button type="button" className={styles.secondary} onClick={downloadTemplate}>
              템플릿 내려받기
            </button>
          </div>
          <p className={styles.hint}>
            첫 줄은 <code className={styles.code}>{IMPORT_COLUMNS.join(',')}</code> 그대로 두고, 둘째 줄부터 한 행에 고객 한 명(방문 1건)을 적어요.
            데이터는 3,000행까지예요.
          </p>
          <div className={styles.table} role="table" aria-label="열 설명">
            {COLUMN_GUIDE.map(([col, need, desc]) => (
              <div key={col} className={styles.row} role="row">
                <span role="cell">
                  <b>{col}</b>
                </span>
                <span role="cell" className={need === '필수' ? styles.must : styles.muted}>
                  {need}
                </span>
                <span role="cell" className={styles.muted}>
                  {desc}
                </span>
              </div>
            ))}
          </div>
          <p className={styles.hint}>
            가져온 고객은 마케팅 수신 동의가 &quot;미동의&quot;로 등록돼요. 가져온 방문은 예측 · 방문 요약에는 들어가고 매출 · 캠페인 성과에는 넣지 않아요.
          </p>
        </section>

        <section className={styles.card} aria-labelledby="import-upload">
          <h2 id="import-upload" className={styles.cardTitle}>
            2. 파일 올리기
          </h2>
          <input
            ref={inputRef}
            className={styles.file}
            type="file"
            accept=".csv,text/csv"
            disabled={busy}
            onChange={(e) => pick(e.target.files?.[0] ?? null)}
            aria-label="CSV 파일"
          />
          {problem && (
            <p className={styles.error} role="alert">
              {problem}
            </p>
          )}
          <label className={styles.consent}>
            <input type="checkbox" checked={confirmed} disabled={busy} onChange={(e) => setConfirmed(e.target.checked)} />
            <span>
              <b>파일의 고객은 매장이 개인정보 수집·이용 동의를 이미 받았습니다</b>
              <span className={styles.consentDesc}>필수 확인이에요. 동의 이력에 동의 방법 &quot;가져오기&quot;로 남아요.</span>
            </span>
          </label>
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          <div className={styles.actions}>
            <span className={styles.hint}>{busy ? '처리 중이에요. 창을 닫지 말고 기다려 주세요.' : '파일 전체를 한 번에 처리해요.'}</span>
            <button type="submit" className={styles.primary} disabled={!file || Boolean(problem) || !confirmed || busy}>
              {busy ? '처리 중…' : '가져오기'}
            </button>
          </div>
        </section>

        {result && (
          <section className={styles.card} aria-labelledby="import-result" aria-live="polite">
            <h2 id="import-result" className={styles.cardTitle}>
              결과
            </h2>
            <div className={styles.stats}>
              <div className={styles.stat}>
                <b className="num">{result.createdCustomers}명</b>
                <span>신규 고객</span>
              </div>
              <div className={styles.stat}>
                <b className="num">{result.addedVisits}건</b>
                <span>방문 추가</span>
              </div>
              <div className={styles.stat}>
                <b className="num">{result.addedNotes}건</b>
                <span>상담 메모 추가</span>
              </div>
              <div className={styles.stat}>
                <b className="num">{result.skippedDuplicates}행</b>
                <span>중복 건너뜀</span>
              </div>
              <div className={styles.stat}>
                <b className={`num ${result.errorCount ? styles.bad : ''}`}>{result.errorCount}행</b>
                <span>오류</span>
              </div>
            </div>
            {result.errors.length > 0 && (
              <>
                <p className={styles.hint}>오류 행은 아무것도 만들지 않고 건너뛰었어요. 행 번호는 헤더가 1행이에요. 고친 행만 다시 올려 주세요.</p>
                <div className={styles.table} role="table" aria-label="오류 행">
                  {result.errors.map((x) => (
                    <div key={x.row} className={styles.errRow} role="row">
                      <span role="cell" className="num">
                        <b>{x.row}행</b>
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
