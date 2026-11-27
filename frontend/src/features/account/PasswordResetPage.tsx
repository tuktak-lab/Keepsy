import { useState, type FormEvent, type ReactNode } from 'react';
import { LocaleCorner } from '@/components/common/LocaleSelect';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { toast } from '@/components/common/Toast';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import type { PasswordResetRequest, PasswordResetConfirmRequest } from './types';
import { ChevronIcon, PasswordInput } from './AuthFields';
import styles from './AuthPage.module.css';
import own from './PasswordResetPage.module.css';
import { t } from '@/lib/i18n';

type Violation = { field: string; reason: string };

// 비밀번호 재설정 (FR-ACC-06). 비로그인 화면.
// 주소에 ?token=이 없으면 재설정 메일 요청, 있으면(메일 링크) 새 비밀번호 설정 (CONTRACTS 4장 POST · PUT /api/password-reset).
// 모양은 매장 로그인 화면(AuthPage)과 같은 유리 카드 + 오른쪽 와인 패널을 그대로 쓴다.
export default function PasswordResetPage() {
  const token = useSearchParams().get('token');
  return token ? <NewPasswordForm token={token} /> : <RequestForm />;
}

// 오류는 전역 토스트 대신 폼 안에 보여 준다 (silent).
// 처음 접속해 CSRF 쿠키가 없을 때의 csrf_invalid는 api.ts가 한 번 다시 보낸다
const send = (method: 'POST' | 'PUT', body: PasswordResetRequest | PasswordResetConfirmRequest) =>
  api<void>('/api/password-reset', { method, body, silent: true });

/** invalid 400의 details에서 그 칸의 사유를 꺼낸다 (CONTRACTS 2장) */
function reasonFor(err: unknown, field: string): string | undefined {
  if (!(err instanceof ApiError) || err.code !== 'invalid' || !Array.isArray(err.details)) return undefined;
  return (err.details as Violation[]).find((d) => d.field === field)?.reason;
}

function messageOf(err: unknown): string {
  return err instanceof ApiError ? err.message : t('common.pin.tryLater');
}

/** 로그인 화면과 같은 틀: 카드 위 로고, 왼쪽 폼, 오른쪽 와인 패널(로그인으로 돌아가기) */
function ResetShell({ children }: { children: ReactNode }) {
  return (
    <main className={styles.page}>
      <LocaleCorner />
      <Link href="/" className={`${styles.logo} ${own.plain}`} aria-label={t('common.logoLabel')}>
        <img src={logoImg.src} alt="Keepsy" />
      </Link>

      <div className={styles.card}>
        <div className={styles.cardBg} aria-hidden="true" />

        <div className={`${styles.hero} ${styles.login}`}>
          <h2>{t('account.reset.remembered')}</h2>
          <p>{t('account.reset.heroText')}</p>
          <Link href="/login" className={`${styles.switch} ${own.plain}`}>
            {t('account.hero.login')}
            <ChevronIcon />
          </Link>
        </div>

        {children}
      </div>
    </main>
  );
}

function RequestForm() {
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState('');
  const [error, setError] = useState('');
  const [sent, setSent] = useState(false);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending) return;
    setError('');
    setEmailError('');
    setPending(true);
    try {
      await send('POST', { email: email.trim() });
      setSent(true);
    } catch (err) {
      const reason = reasonFor(err, 'email');
      if (reason) setEmailError(reason);
      else setError(messageOf(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <ResetShell>
      <form className={`${styles.form} ${styles.login}`} onSubmit={onSubmit} aria-labelledby="reset-title">
        <h1 id="reset-title">{t('account.reset.title')}</h1>

        {sent ? (
          <>
            {/* 가입된 이메일인지 드러내지 않도록 결과와 상관없이 같은 안내를 한다 (CONTRACTS 4장, F49) */}
            <p className={styles.sub} role="status">
              {t('account.reset.sentPre')}<strong>{email.trim()}</strong>{t('account.reset.sentPost')}
              <br />
              {t('account.reset.linkValid')}
            </p>
            <Link href={`/virtual-mailbox?email=${encodeURIComponent(email.trim())}`} className={`${styles.submit} ${own.plain}`}>
              {t('account.reset.openMailbox')}
              <ChevronIcon />
            </Link>
            <p className={own.note}>{t('account.reset.demoNote')}</p>
          </>
        ) : (
          <>
            <p className={styles.sub}>{t('account.reset.sub')}</p>

            <label className={styles.field}>
              <span className={styles.label}>{t('account.reset.emailLabel')}</span>
              <input
                className={`${styles.input} ${emailError ? styles.invalid : ''}`}
                type="email"
                name="email"
                autoComplete="username"
                placeholder="shop@example.com"
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value);
                  setEmailError('');
                }}
                aria-invalid={Boolean(emailError)}
                required
                autoFocus
              />
              {emailError && <span className={styles.fieldError}>{emailError}</span>}
            </label>

            {error && (
              <p className={styles.error} role="alert">
                {error}
              </p>
            )}

            <button className={styles.submit} type="submit" disabled={pending}>
              {pending ? t('account.reset.sending') : t('account.reset.sendBtn')}
              {!pending && <ChevronIcon />}
            </button>
          </>
        )}

        {/* 모바일에서만 보인다 (데스크톱은 옆 패널 버튼을 쓴다) */}
        <p className={styles.formSwitch}>
          {t('account.reset.remembered')}
          <Link href="/login" className={own.switchLink}>
            {t('account.hero.login')}
          </Link>
        </p>
      </form>
    </ResetShell>
  );
}

function NewPasswordForm({ token }: { token: string }) {
  const router = useRouter();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [passwordError, setPasswordError] = useState('');
  const [confirmError, setConfirmError] = useState('');
  const [error, setError] = useState('');
  const [tokenInvalid, setTokenInvalid] = useState(false);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending) return;
    setError('');
    setPasswordError('');
    setConfirmError('');
    if (password !== confirm) {
      setConfirmError(t('account.password.mismatch'));
      return;
    }
    setPending(true);
    try {
      await send('PUT', { token, newPassword: password });
      toast(t('account.reset.done'));
      router.replace('/login');
    } catch (err) {
      const tokenReason = reasonFor(err, 'token');
      const passwordReason = reasonFor(err, 'newPassword');
      if (tokenReason) {
        // 없는 · 만료된 · 이미 쓴 토큰 (구분하지 않음). 다시 요청하도록 안내한다
        setTokenInvalid(true);
        setError(tokenReason);
      } else if (passwordReason) {
        setPasswordError(passwordReason);
      } else {
        setError(messageOf(err));
      }
      setPending(false);
    }
  }

  return (
    <ResetShell>
      <form className={`${styles.form} ${styles.login}`} onSubmit={onSubmit} aria-labelledby="reset-title">
        <h1 id="reset-title">{t('account.reset.newTitle')}</h1>
        <p className={styles.sub}>{t('account.reset.newSub')}</p>

        <div className={styles.field}>
          <label htmlFor="reset-password" className={styles.label}>
            {t('account.reset.newLabel')}
          </label>
          <PasswordInput
            id="reset-password"
            name="newPassword"
            autoComplete="new-password"
            placeholder={t('account.reset.newPlaceholder')}
            className={passwordError ? styles.invalid : ''}
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
              setPasswordError('');
            }}
            aria-invalid={Boolean(passwordError)}
            required
            autoFocus
          />
          {passwordError && <span className={styles.fieldError}>{passwordError}</span>}
        </div>

        <div className={styles.field}>
          <label htmlFor="reset-confirm" className={styles.label}>
            {t('account.reset.newConfirm')}
          </label>
          <PasswordInput
            id="reset-confirm"
            name="confirmPassword"
            autoComplete="new-password"
            placeholder={t('account.password.again')}
            className={confirmError ? styles.invalid : ''}
            value={confirm}
            onChange={(e) => {
              setConfirm(e.target.value);
              setConfirmError('');
            }}
            aria-invalid={Boolean(confirmError)}
            required
          />
          {confirmError && <span className={styles.fieldError}>{confirmError}</span>}
        </div>

        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}

        {tokenInvalid ? (
          <Link href="/password-reset" className={`${styles.submit} ${own.plain}`}>
            {t('account.reset.again')}
            <ChevronIcon />
          </Link>
        ) : (
          <button className={styles.submit} type="submit" disabled={pending}>
            {pending ? t('account.reset.changing') : t('account.reset.changeBtn')}
            {!pending && <ChevronIcon />}
          </button>
        )}

        {/* 모바일에서만 보인다 (데스크톱은 옆 패널 버튼을 쓴다) */}
        <p className={styles.formSwitch}>
          {t('account.reset.remembered')}
          <Link href="/login" className={own.switchLink}>
            {t('account.hero.login')}
          </Link>
        </p>
      </form>
    </ResetShell>
  );
}
