import { useEffect, useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, ApiError, configureApi } from '@/lib/api';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import type { LoginRequest, LoginResponse } from './types';
import styles from './LoginPage.module.css';

// 매장 로그인 (FR-ACC-02). 매장 PC에서 스탭 모두가 쓰는 공용 계정.
// 로그인 직후에는 근무자가 없으므로 근무자 PIN 입력(/shift)으로 보낸다 (CONTRACTS 4장 POST /api/session).
export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  // 오류(login_failed · login_locked 등)는 alert 대신 폼 안에 보여 준다. 화면을 떠나면 기본 동작으로 되돌린다.
  useEffect(() => {
    configureApi({ showError: setError });
    return () => configureApi({ showError: (message) => window.alert(message) });
  }, []);

  async function login(body: LoginRequest): Promise<LoginResponse> {
    try {
      return await api<LoginResponse>('/api/session', { method: 'POST', body });
    } catch (e) {
      // 처음 접속하면 아직 CSRF 쿠키가 없어 csrf_invalid가 난다. 그 응답에 쿠키가 내려오므로 한 번만 다시 보낸다.
      if (e instanceof ApiError && e.code === 'csrf_invalid') {
        setError('');
        return api<LoginResponse>('/api/session', { method: 'POST', body });
      }
      throw e;
    }
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending) return;
    setError('');
    setPending(true);
    try {
      await login({ email: email.trim(), password });
      router.replace('/shift');
    } catch {
      // 메시지는 api()가 showError로 이미 넣었다
      setPassword('');
      setPending(false);
    }
  }

  return (
    <main className={styles.page}>
      <section className={styles.card} aria-labelledby="login-title">
        <img className={styles.logo} src={logoImg.src} alt="Keepsy" />
        <h1 id="login-title" className={styles.title}>
          매장 로그인
        </h1>
        <p className={styles.sub}>매장에서 함께 쓰는 계정으로 로그인하세요.</p>

        <form className={styles.form} onSubmit={onSubmit}>
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
              autoFocus
            />
          </label>

          <label className={styles.field}>
            <span className={styles.label}>비밀번호</span>
            <input
              className={styles.input}
              type="password"
              name="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>

          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}

          <button className={styles.submit} type="submit" disabled={pending}>
            {pending ? '로그인 중…' : '로그인'}
          </button>
        </form>

        <p className={styles.foot}>
          처음이신가요? <Link href="/signup">매장 가입</Link>
        </p>
      </section>
    </main>
  );
}
