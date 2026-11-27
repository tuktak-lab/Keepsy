import { useEffect, useState, type FormEvent } from 'react';
import { LocaleCorner } from '@/components/common/LocaleSelect';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { ApiError, api, type Page } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import { normalizeEmail, splitBody } from '@/lib/mailbox';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import styles from '../campaign/VirtualPhonePage.module.css';
import { t } from '@/lib/i18n';

// 가상 메일함 (FR-ACC-06, F49 · F59). 비로그인 화면. GET /api/virtual-mailbox?email= (CONTRACTS 4장).
// 재설정 메일은 실제로 오가지 않고 여기에만 쌓인다. 가상 휴대폰 화면과 같은 구성: 왼쪽 입력 카드, 오른쪽 받은 메일.

type Mail = { id: number; subject: string; body: string; sentAt: string };
type Result = { email: string; items: Mail[]; total: number; error?: string };

const SIZE = 20;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : t('common.pin.tryLater'));

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
    if (!email) return setInputError(t('account.mail.badEmail'));
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
      <LocaleCorner />
      <div className={styles.head}>
        <div>
          <img src={logoImg.src} alt="Keepsy" style={{ height: 28, marginBottom: 12 }} />
          <h1 className={styles.title}>{t('account.mail.title')}</h1>
          <p className={styles.sub}>{t('account.mail.sub')}</p>
        </div>
        <Link href="/password-reset" className={styles.secondary}>
          {t('account.mail.toReset')}
        </Link>
      </div>

      <div className={styles.layout} style={{ marginTop: 18 }}>
        <section className={styles.card} aria-label={t('account.mail.openLabel')}>
          <h2 className={styles.cardTitle}>{t('account.mail.openLabel')}</h2>
          <form className={styles.field} onSubmit={open} noValidate>
            <label className={styles.label} htmlFor="vm-email">
              {t('account.mail.toEmail')}
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
                {t('account.mail.open')}
              </button>
            </div>
            {inputError && (
              <span id="vm-email-error" className={styles.fieldError}>
                {inputError}
              </span>
            )}
          </form>
          <p className={styles.hint}>
            {t('account.mail.help1')}
            <br />
            {t('account.mail.help2')}
          </p>
        </section>

        <section className={styles.card} aria-label={t('account.mail.inbox')}>
          <h2 className={styles.cardTitle}>
            {t('account.mail.inbox')}{' '}
            {cur && !cur.error ? <span className="num" style={{ fontWeight: 400, color: '#6B605B' }}>{t('account.mail.count', { n: cur.total, email: cur.email })}</span> : null}
          </h2>
          {!target ? (
            <p className={styles.empty}>{t('account.mail.hint')}</p>
          ) : loading ? (
            <p className={styles.empty}>{t('common.loading')}</p>
          ) : cur?.error ? (
            <p className={styles.error} role="alert">
              {cur.error}
            </p>
          ) : cur && cur.items.length === 0 ? (
            <p className={styles.empty}>{t('account.mail.none')}</p>
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
              {moreBusy ? t('common.loading') : t('customer.moreBtn')}
            </button>
          ) : null}
        </section>
      </div>
    </main>
  );
}
