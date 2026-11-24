import { useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { toast } from '@/components/common/Toast';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import type { SignUpRequest, SignUpResponse } from './types';
import card from './LoginPage.module.css';
import styles from './SignUpPage.module.css';

type Field = keyof SignUpRequest;
type FieldErrors = Partial<Record<Field, string>>;

const FIELDS: { name: Field; label: string; type: string; autoComplete: string; placeholder?: string; hint?: string }[] = [
  { name: 'email', label: '매장 계정 이메일', type: 'email', autoComplete: 'username', placeholder: 'shop@example.com' },
  { name: 'password', label: '비밀번호', type: 'password', autoComplete: 'new-password', hint: '8~64자' },
  { name: 'shopName', label: '매장 이름', type: 'text', autoComplete: 'organization' },
  { name: 'ownerName', label: '원장 이름', type: 'text', autoComplete: 'name' },
  {
    name: 'ownerPin',
    label: '원장 PIN',
    type: 'password',
    autoComplete: 'off',
    hint: '숫자 4~6자리. 근무 시작과 잠긴 기능(설정 · 캠페인 등)을 열 때 씁니다',
  },
];

const EMPTY: SignUpRequest = { email: '', password: '', shopName: '', ownerName: '', ownerPin: '' };

// 매장 가입 (FR-ACC-01). 매장 1개와 원장 스탭을 만든다. 가입만 하고 로그인은 되지 않는다 (CONTRACTS 4장 POST /api/shops).
export default function SignUpPage() {
  const router = useRouter();
  const [form, setForm] = useState<SignUpRequest>(EMPTY);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  function change(name: Field, value: string) {
    setForm((f) => ({ ...f, [name]: name === 'ownerPin' ? value.replace(/\D/g, '').slice(0, 6) : value }));
    setErrors((e) => ({ ...e, [name]: undefined }));
  }

  async function signUp(body: SignUpRequest): Promise<SignUpResponse> {
    const send = () => api<SignUpResponse>('/api/shops', { method: 'POST', body, silent: true });
    try {
      return await send();
    } catch (e) {
      // 처음 접속하면 CSRF 쿠키가 없어 csrf_invalid가 난다. 그 응답에 쿠키가 내려오므로 한 번만 다시 보낸다 (로그인 화면과 같음)
      if (e instanceof ApiError && e.code === 'csrf_invalid') return send();
      throw e;
    }
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending) return;
    setError('');
    setErrors({});
    setPending(true);
    try {
      await signUp({ ...form, email: form.email.trim(), shopName: form.shopName.trim(), ownerName: form.ownerName.trim() });
      toast('가입이 끝났어요. 매장 계정으로 로그인해 주세요.');
      router.replace('/login');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid' && Array.isArray(err.details)) {
        // 칸별 사유: [{ field, reason }] (CONTRACTS 6장 POST /api/shops)
        const byField: FieldErrors = {};
        for (const d of err.details as { field: string; reason: string }[]) byField[d.field as Field] ??= d.reason;
        setErrors(byField);
      } else if (err instanceof ApiError && err.code === 'duplicate') {
        setErrors({ email: err.message });
      } else {
        setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
      }
      setPending(false);
    }
  }

  return (
    <main className={card.page}>
      <section className={`${card.card} ${styles.card}`} aria-labelledby="signup-title">
        <img className={card.logo} src={logoImg.src} alt="Keepsy" />
        <h1 id="signup-title" className={card.title}>
          매장 가입
        </h1>
        <p className={card.sub}>매장 계정과 원장 정보를 만들어요.</p>

        <form className={card.form} onSubmit={onSubmit}>
          {FIELDS.map((f) => (
            <label key={f.name} className={card.field}>
              <span className={card.label}>{f.label}</span>
              <input
                className={`${card.input} ${errors[f.name] ? styles.invalid : ''}`}
                type={f.type}
                name={f.name}
                inputMode={f.name === 'ownerPin' ? 'numeric' : undefined}
                autoComplete={f.autoComplete}
                placeholder={f.placeholder}
                value={form[f.name]}
                onChange={(e) => change(f.name, e.target.value)}
                aria-invalid={Boolean(errors[f.name])}
                required
              />
              {errors[f.name] ? (
                <span className={styles.fieldError}>{errors[f.name]}</span>
              ) : f.hint ? (
                <span className={styles.hint}>{f.hint}</span>
              ) : null}
            </label>
          ))}

          {error && (
            <p className={card.error} role="alert">
              {error}
            </p>
          )}

          <button className={card.submit} type="submit" disabled={pending}>
            {pending ? '가입 중…' : '가입하기'}
          </button>
        </form>

        <p className={card.foot}>
          이미 계정이 있나요? <Link href="/login">로그인</Link>
        </p>
      </section>
    </main>
  );
}
