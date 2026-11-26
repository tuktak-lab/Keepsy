import { useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { ChevronIcon, PasswordInput } from './AuthFields';
import type { LoginRequest, LoginResponse } from './types';
import styles from './AuthPage.module.css';

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
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
      setPassword('');
      setPending(false);
    }
  }

  return (
    <form className={`${styles.form} ${styles.login}`} onSubmit={onSubmit} aria-labelledby="login-title">
      <h1 id="login-title">매장 로그인</h1>
      <p className={styles.sub}>매장에서 함께 쓰는 계정으로 로그인하세요.</p>

      <label className={styles.field}>
        <span className={styles.label}>이메일</span>
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
            비밀번호
          </label>
          <Link href="/password-reset" className={styles.textLink}>
            비밀번호를 잊으셨나요?
          </Link>
        </div>
        <PasswordInput
          id="login-password"
          name="password"
          autoComplete="current-password"
          placeholder="비밀번호를 입력해주세요"
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
        {pending ? '로그인 중…' : '로그인'}
        {!pending && <ChevronIcon />}
      </button>

      {/* 모바일에서만 보인다 (데스크톱은 옆 패널 버튼을 쓴다) */}
      <p className={styles.formSwitch}>
        처음이신가요?
        <button type="button" onClick={props.onSwitch}>
          매장 가입
        </button>
      </p>
    </form>
  );
}
