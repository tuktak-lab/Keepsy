import { useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { ChevronIcon, PasswordInput } from './AuthFields';
import type { LoginRequest, LoginResponse } from './types';
import styles from './AuthPage.module.css';
import { t } from '@/lib/i18n';

// 매장 로그인 (FR-ACC-02). 매장 PC에서 스탭 모두가 쓰는 공용 계정.
// 로그인 직후에는 근무자가 없으므로 근무자 PIN 입력(/shift)으로 보낸다 (CONTRACTS 4장 POST /api/session).
export function LoginForm(props: { autoFocus: boolean; onSwitch: () => void }) {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  // 오류(login_failed · login_locked 등)는 전역 토스트 대신 폼 안에 보여 준다 (silent)
  // 처음 접속해 CSRF 쿠키가 없을 때의 csrf_invalid는 api.ts가 한 번 다시 보낸다
  const login = (body: LoginRequest) => api<LoginResponse>('/api/session', { method: 'POST', body, silent: true });

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending) return;
    setError('');
    setPending(true);
    try {
      await login({ email: email.trim(), password });
      router.replace('/shift');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t('common.pin.tryLater'));
      setPassword('');
      setPending(false);
    }
  }

  return (
    <form className={`${styles.form} ${styles.login}`} onSubmit={onSubmit} aria-labelledby="login-title">
      <h1 id="login-title">{t('account.loginForm.title')}</h1>
      <p className={styles.sub}>{t('account.loginForm.sub')}</p>

      <label className={styles.field}>
        <span className={styles.label}>{t('account.loginForm.email')}</span>
        <input
          className={styles.input}
          type="email"
          name="email"
          autoComplete="username"
          placeholder="shop@example.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
          autoFocus={props.autoFocus}
        />
      </label>

      <div className={styles.field}>
        <div className={styles.fieldRow}>
          <label htmlFor="login-password" className={styles.label}>
            {t('account.password.label')}
          </label>
          <Link href="/password-reset" className={styles.textLink}>
            {t('account.loginForm.forgot')}
          </Link>
        </div>
        <PasswordInput
          id="login-password"
          name="password"
          autoComplete="current-password"
          placeholder={t('account.password.placeholder')}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
      </div>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      <button className={styles.submit} type="submit" disabled={pending}>
        {pending ? t('account.loginForm.pending') : t('account.hero.login')}
        {!pending && <ChevronIcon />}
      </button>

      {/* 모바일에서만 보인다 (데스크톱은 옆 패널 버튼을 쓴다) */}
      <p className={styles.formSwitch}>
        {t('account.loginForm.newHere')}
        <button type="button" onClick={props.onSwitch}>
          {t('account.hero.signup')}
        </button>
      </p>
    </form>
  );
}
