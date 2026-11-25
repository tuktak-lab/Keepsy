import { useEffect, useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { ApiError, api, type Page } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import { normalizeEmail, splitBody } from '@/lib/mailbox';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import styles from '../campaign/VirtualPhonePage.module.css';

// 가상 메일함 (FR-ACC-06, F49 · F59). 비로그인 화면. GET /api/virtual-mailbox?email= (CONTRACTS 4장).
// 재설정 메일은 실제로 오가지 않고 여기에만 쌓인다. 가상 휴대폰 화면과 같은 구성: 왼쪽 입력 카드, 오른쪽 받은 메일.

type Mail = { id: number; subject: string; body: string; sentAt: string };
type Result = { email: string; items: Mail[]; total: number; error?: string };

const SIZE = 20;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');

export default function VirtualMailboxPage() {
  const fromUrl = useSearchParams().get('email') ?? '';
  const [input, setInput] = useState(fromUrl);
  const [inputError, setInputError] = useState('');
  // 열려는 주소. 주소창의 ?email=이 올바르면 처음부터 연다
  const [target, setTarget] = useState<string | null>(() => normalizeEmail(fromUrl));
  const [result, setResult] = useState<Result | null>(null);
  const [moreBusy, setMoreBusy] = useState(false);

  useEffect(() => {
    if (!target) return;
    let cancelled = false;
    api<Page<Mail>>(`/api/virtual-mailbox?email=${encodeURIComponent(target)}&page=0&size=${SIZE}`, { silent: true })
      .then((p) => !cancelled && setResult({ email: target, items: p.items, total: p.totalElements }))
      .catch((err) => !cancelled && setResult({ email: target, items: [], total: 0, error: errorText(err) }));
    return () => {
      cancelled = true;
    };
  }, [target]);

  function open(e: FormEvent) {
    e.preventDefault();
    const email = normalizeEmail(input);
    if (!email) return setInputError('이메일 형식이 올바르지 않아요 (예: shop@example.com)');
    setInputError('');
    setTarget(email);
  }

  const cur = result && result.email === target ? result : null;
  const loading = target !== null && !cur;

  async function loadMore() {
    if (!cur) return;
    setMoreBusy(true);
    try {
      const p = await api<Page<Mail>>(`/api/virtual-mailbox?email=${encodeURIComponent(cur.email)}&page=${Math.ceil(cur.items.length / SIZE)}&size=${SIZE}`, { silent: true });
      setResult({ ...cur, items: [...cur.items, ...p.items], total: p.totalElements });
    } catch (err) {
      setResult({ ...cur, error: errorText(err) });
    } finally {
      setMoreBusy(false);
    }
  }

  return (
    <main style={{ maxWidth: 1040, margin: '0 auto', padding: '40px 24px 56px', fontFamily: "'Noto Sans KR', sans-serif" }}>
      <div className={styles.head}>
        <div>
          <img src={logoImg.src} alt="Keepsy" style={{ height: 28, marginBottom: 12 }} />
          <h1 className={styles.title}>가상 메일함</h1>
          <p className={styles.sub}>시연용이에요. 실제 메일은 오가지 않고, 재설정 메일은 여기에만 쌓여요.</p>
        </div>
        <Link href="/password-reset" className={styles.secondary}>
          비밀번호 재설정으로
        </Link>
      </div>

      <div className={styles.layout} style={{ marginTop: 18 }}>
        <section className={styles.card} aria-label="메일함 열기">
          <h2 className={styles.cardTitle}>메일함 열기</h2>
          <form className={styles.field} onSubmit={open} noValidate>
            <label className={styles.label} htmlFor="vm-email">
              받는 이메일
            </label>
            <div className={styles.inputRow}>
              <input
                id="vm-email"
                className={styles.input}
                type="email"
                inputMode="email"
                autoComplete="off"
                placeholder="shop@example.com"
                value={input}
                aria-invalid={inputError ? 'true' : undefined}
                aria-describedby={inputError ? 'vm-email-error' : undefined}
                onChange={(e) => setInput(e.target.value)}
              />
              <button type="submit" className={styles.primary} disabled={loading}>
                열기
              </button>
            </div>
            {inputError && (
              <span id="vm-email-error" className={styles.fieldError}>
                {inputError}
              </span>
            )}
          </form>
          <p className={styles.hint}>
            비밀번호 재설정을 요청한 매장 계정 이메일을 넣으면 받은 메일이 최신순으로 보여요.
            <br />
            메일 안의 재설정 링크는 30분 동안만 쓸 수 있어요.
          </p>
        </section>

        <section className={styles.card} aria-label="받은 메일">
          <h2 className={styles.cardTitle}>
            받은 메일{' '}
            {cur && !cur.error ? <span className="num" style={{ fontWeight: 400, color: '#6B605B' }}>{cur.total}건 · {cur.email}</span> : null}
          </h2>
          {!target ? (
            <p className={styles.empty}>왼쪽에서 이메일을 입력하면 받은 메일이 보여요.</p>
          ) : loading ? (
            <p className={styles.empty}>불러오는 중…</p>
          ) : cur?.error ? (
            <p className={styles.error} role="alert">
              {cur.error}
            </p>
          ) : cur && cur.items.length === 0 ? (
            <p className={styles.empty}>받은 메일이 없어요. 비밀번호 재설정을 먼저 요청해 주세요.</p>
          ) : (
            cur?.items.map((m) => (
              <article key={m.id} style={{ padding: '14px 16px', borderRadius: 14, background: 'rgba(255,255,255,.6)', border: '1px solid #EFE8E2' }}>
                <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700 }}>{m.subject}</h3>
                <time className="num" dateTime={m.sentAt} style={{ fontSize: 12.5, color: '#6B605B' }}>
                  {formatDateTime(m.sentAt)}
                </time>
                {/* 본문은 글자로만 그리고(React가 이스케이프), 재설정 링크 형식만 링크로 만든다 (NFR-08) */}
                <p style={{ margin: '10px 0 0', whiteSpace: 'pre-line', wordBreak: 'break-all', fontSize: 14, lineHeight: 1.7 }}>
                  {splitBody(m.body).map((part, i) =>
                    part.href ? (
                      <Link key={i} href={part.href} style={{ color: '#7A4450', fontWeight: 600, textDecoration: 'underline' }}>
                        {part.text}
                      </Link>
                    ) : (
                      part.text
                    ),
                  )}
                </p>
              </article>
            ))
          )}
          {cur && !cur.error && cur.items.length < cur.total ? (
            <button type="button" className={styles.more} disabled={moreBusy} onClick={() => void loadMore()}>
              {moreBusy ? '불러오는 중…' : '더 보기'}
            </button>
          ) : null}
        </section>
      </div>
    </main>
  );
}
