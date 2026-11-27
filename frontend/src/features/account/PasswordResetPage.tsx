import { useState, type FormEvent, type ReactNode } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { toast } from '@/components/common/Toast';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import type { PasswordResetRequest, PasswordResetConfirmRequest } from './types';
import { ChevronIcon, PasswordInput } from './AuthFields';
import styles from './AuthPage.module.css';
import own from './PasswordResetPage.module.css';

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
  return err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요';
}

/** 로그인 화면과 같은 틀: 카드 위 로고, 왼쪽 폼, 오른쪽 와인 패널(로그인으로 돌아가기) */
function ResetShell({ children }: { children: ReactNode }) {
  return (
    <main className={styles.page}>
      <Link href="/" className={`${styles.logo} ${own.plain}`} aria-label="Keepsy 소개 화면으로">
        <img src={logoImg.src} alt="Keepsy" />
      </Link>

      <div className={styles.card}>
        <div className={styles.cardBg} aria-hidden="true" />

        <div className={`${styles.hero} ${styles.login}`}>
          <h2>비밀번호가 생각났나요?</h2>
          <p>{'매장 계정으로 다시 로그인하고\n오늘 업무를 이어 가세요.'}</p>
          <Link href="/login" className={`${styles.switch} ${own.plain}`}>
            로그인
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
        <h1 id="reset-title">비밀번호 재설정</h1>

        {sent ? (
          <>
            {/* 가입된 이메일인지 드러내지 않도록 결과와 상관없이 같은 안내를 한다 (CONTRACTS 4장, F49) */}
            <p className={styles.sub} role="status">
              가입된 매장 계정이면 <strong>{email.trim()}</strong>(으)로 재설정 링크를 보냈어요.
              <br />
              링크는 30분 동안만 쓸 수 있어요.
            </p>
            <Link href={`/virtual-mailbox?email=${encodeURIComponent(email.trim())}`} className={`${styles.submit} ${own.plain}`}>
              가상 메일함 열기
              <ChevronIcon />
            </Link>
            <p className={own.note}>시연용이라 실제 메일은 오가지 않아요.</p>
          </>
        ) : (
          <>
            <p className={styles.sub}>매장 계정 이메일로 재설정 링크를 보내 드려요.</p>

            <label className={styles.field}>
              <span className={styles.label}>매장 계정 이메일</span>
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
              {pending ? '보내는 중…' : '재설정 링크 받기'}
              {!pending && <ChevronIcon />}
            </button>
          </>
        )}

        {/* 모바일에서만 보인다 (데스크톱은 옆 패널 버튼을 쓴다) */}
        <p className={styles.formSwitch}>
          비밀번호가 생각났나요?
          <Link href="/login" className={own.switchLink}>
            로그인
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
      setConfirmError('비밀번호가 서로 다릅니다');
      return;
    }
    setPending(true);
    try {
      await send('PUT', { token, newPassword: password });
      toast('비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.');
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
        <h1 id="reset-title">새 비밀번호 설정</h1>
        <p className={styles.sub}>매장 계정에서 쓸 새 비밀번호를 정해 주세요.</p>

        <div className={styles.field}>
          <label htmlFor="reset-password" className={styles.label}>
            새 비밀번호
          </label>
          <PasswordInput
            id="reset-password"
            name="newPassword"
            autoComplete="new-password"
            placeholder="8~64자로 입력해주세요"
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
            새 비밀번호 확인
          </label>
          <PasswordInput
            id="reset-confirm"
            name="confirmPassword"
            autoComplete="new-password"
            placeholder="비밀번호를 다시 입력해주세요"
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
            재설정 링크 다시 받기
            <ChevronIcon />
          </Link>
        ) : (
          <button className={styles.submit} type="submit" disabled={pending}>
            {pending ? '바꾸는 중…' : '비밀번호 바꾸기'}
            {!pending && <ChevronIcon />}
          </button>
        )}

        {/* 모바일에서만 보인다 (데스크톱은 옆 패널 버튼을 쓴다) */}
        <p className={styles.formSwitch}>
          비밀번호가 생각났나요?
          <Link href="/login" className={own.switchLink}>
            로그인
          </Link>
        </p>
      </form>
    </ResetShell>
  );
}
