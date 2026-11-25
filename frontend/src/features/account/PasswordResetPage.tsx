import { useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { toast } from '@/components/common/Toast';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import type { PasswordResetRequest, PasswordResetConfirmRequest } from './types';
import card from './LoginPage.module.css';
import fields from './SignUpPage.module.css';

type Violation = { field: string; reason: string };

// 비밀번호 재설정 (FR-ACC-06). 비로그인 화면.
// 주소에 ?token=이 없으면 재설정 메일 요청, 있으면(메일 링크) 새 비밀번호 설정 (CONTRACTS 4장 POST · PUT /api/password-reset).
export default function PasswordResetPage() {
  const token = useSearchParams().get('token');
  return token ? <NewPasswordForm token={token} /> : <RequestForm />;
}

// 오류는 전역 토스트 대신 폼 안에 보여 준다 (silent).
// 처음 접속하면 CSRF 쿠키가 없어 csrf_invalid가 난다. 그 응답에 쿠키가 내려오므로 한 번만 다시 보낸다 (로그인 화면과 같음)
async function send(method: 'POST' | 'PUT', body: PasswordResetRequest | PasswordResetConfirmRequest): Promise<void> {
  const call = () => api<void>('/api/password-reset', { method, body, silent: true });
  try {
    await call();
  } catch (e) {
    if (e instanceof ApiError && e.code === 'csrf_invalid') return call();
    throw e;
  }
}

/** invalid 400의 details에서 그 칸의 사유를 꺼낸다 (CONTRACTS 2장) */
function reasonFor(err: unknown, field: string): string | undefined {
  if (!(err instanceof ApiError) || err.code !== 'invalid' || !Array.isArray(err.details)) return undefined;
  return (err.details as Violation[]).find((d) => d.field === field)?.reason;
}

function messageOf(err: unknown): string {
  return err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요';
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
    <main className={card.page}>
      <section className={card.card} aria-labelledby="reset-title">
        <img className={card.logo} src={logoImg.src} alt="Keepsy" />
        <h1 id="reset-title" className={card.title}>
          비밀번호 재설정
        </h1>

        {sent ? (
          // 가입된 이메일인지 드러내지 않도록 결과와 상관없이 같은 안내를 한다 (CONTRACTS 4장, F49)
          <p className={card.sub} role="status">
            가입된 매장 계정이면 <strong>{email.trim()}</strong>(으)로 재설정 링크를 보냈어요.
            <br />
            링크는 30분 동안만 쓸 수 있어요.
          </p>
        ) : (
          <>
            <p className={card.sub}>매장 계정 이메일로 재설정 링크를 보내 드려요.</p>
            <form className={card.form} onSubmit={onSubmit}>
              <label className={card.field}>
                <span className={card.label}>매장 계정 이메일</span>
                <input
                  className={`${card.input} ${emailError ? fields.invalid : ''}`}
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
                {emailError && <span className={fields.fieldError}>{emailError}</span>}
              </label>

              {error && (
                <p className={card.error} role="alert">
                  {error}
                </p>
              )}

              <button className={card.submit} type="submit" disabled={pending}>
                {pending ? '보내는 중…' : '재설정 링크 받기'}
              </button>
            </form>
          </>
        )}

        <p className={card.foot}>
          비밀번호가 생각났나요? <Link href="/login">로그인</Link>
        </p>
      </section>
    </main>
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
    <main className={card.page}>
      <section className={card.card} aria-labelledby="reset-title">
        <img className={card.logo} src={logoImg.src} alt="Keepsy" />
        <h1 id="reset-title" className={card.title}>
          새 비밀번호 설정
        </h1>
        <p className={card.sub}>매장 계정에서 쓸 새 비밀번호를 정해 주세요.</p>

        <form className={card.form} onSubmit={onSubmit}>
          <label className={card.field}>
            <span className={card.label}>새 비밀번호</span>
            <input
              className={`${card.input} ${passwordError ? fields.invalid : ''}`}
              type="password"
              name="newPassword"
              autoComplete="new-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                setPasswordError('');
              }}
              aria-invalid={Boolean(passwordError)}
              required
              autoFocus
            />
            {passwordError ? (
              <span className={fields.fieldError}>{passwordError}</span>
            ) : (
              <span className={fields.hint}>8~64자</span>
            )}
          </label>

          <label className={card.field}>
            <span className={card.label}>새 비밀번호 확인</span>
            <input
              className={`${card.input} ${confirmError ? fields.invalid : ''}`}
              type="password"
              name="confirmPassword"
              autoComplete="new-password"
              value={confirm}
              onChange={(e) => {
                setConfirm(e.target.value);
                setConfirmError('');
              }}
              aria-invalid={Boolean(confirmError)}
              required
            />
            {confirmError && <span className={fields.fieldError}>{confirmError}</span>}
          </label>

          {error && (
            <p className={card.error} role="alert">
              {error}
            </p>
          )}

          <button className={card.submit} type="submit" disabled={pending || tokenInvalid}>
            {pending ? '바꾸는 중…' : '비밀번호 바꾸기'}
          </button>
        </form>

        <p className={card.foot}>
          {tokenInvalid ? (
            <Link href="/password-reset">재설정 링크 다시 받기</Link>
          ) : (
            <>
              비밀번호가 생각났나요? <Link href="/login">로그인</Link>
            </>
          )}
        </p>
      </section>
    </main>
  );
}
