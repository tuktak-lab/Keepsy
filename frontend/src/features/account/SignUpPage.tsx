import { useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { toast } from '@/components/common/Toast';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import { EMPTY_SIGNUP, INDUSTRIES, STEPS, formatBusinessNumber, stepOf, toSignUpRequest, validateStep, type SignUpField, type SignUpValues } from '@/lib/signup';
import type { SignUpRequest, SignUpResponse } from './types';
import card from './LoginPage.module.css';
import styles from './SignUpPage.module.css';

type FieldErrors = Partial<Record<SignUpField, string>>;

// 칸마다 화면에 보이는 모양. 이름은 단계 규칙(lib/signup.ts)의 칸 이름과 같다
const FIELD: Record<Exclude<SignUpField, 'industries'>, { label: string; type: string; autoComplete: string; placeholder?: string; hint?: string }> = {
  email: { label: '매장 아이디 (로그인에 쓰는 이메일)', type: 'email', autoComplete: 'username', placeholder: 'shop@example.com' },
  password: { label: '비밀번호 (8~64자)', type: 'password', autoComplete: 'new-password' },
  passwordConfirm: { label: '비밀번호 확인', type: 'password', autoComplete: 'new-password' },
  ownerName: { label: '대표자명', type: 'text', autoComplete: 'name' },
  ownerPin: { label: '원장 PIN', type: 'password', autoComplete: 'off', hint: '숫자 4~6자리 · 근무 시작과 잠긴 기능을 열 때 써요' },
  shopName: { label: '매장명', type: 'text', autoComplete: 'organization' },
  businessNumber: { label: '사업자등록번호', type: 'text', autoComplete: 'off', placeholder: '000-00-00000', hint: '시연용이에요. 실제 사업자등록번호를 넣지 말고 가짜 번호를 넣어 주세요' },
  phone: { label: '매장 연락처', type: 'tel', autoComplete: 'tel', placeholder: '02-000-0000' },
  address: { label: '매장 주소', type: 'text', autoComplete: 'street-address', placeholder: '서울시 가상구 예시로 1' },
};

const STEP_SUB = ['매장 계정과 대표자 정보를 만들어요.', '매장의 사업자 정보를 입력해 주세요.', '매장의 주요 서비스 분야를 모두 선택해 주세요. (중복 선택 가능)'];

// 매장 가입 (FR-ACC-01). 매장 1개와 원장 스탭을 만든다. 가입만 하고 로그인은 되지 않는다 (CONTRACTS 4장 POST /api/shops).
// 3단계: 개인정보 → 사업자 정보 → 업종 설정. 단계마다 그 단계 칸만 검사하고, 마지막에 한 번에 보낸다.
export default function SignUpPage() {
  const router = useRouter();
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<SignUpValues>(EMPTY_SIGNUP);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const last = step === STEPS.length - 1;

  function change(name: Exclude<SignUpField, 'industries'>, value: string) {
    const v = name === 'ownerPin' ? value.replace(/\D/g, '').slice(0, 6) : name === 'businessNumber' ? formatBusinessNumber(value) : value;
    setForm((f) => ({ ...f, [name]: v }));
    setErrors((e) => ({ ...e, [name]: undefined }));
  }

  // 업종은 여러 개 고를 수 있다: 누르면 넣고, 다시 누르면 뺀다
  function toggleIndustry(value: string) {
    setForm((f) => ({ ...f, industries: f.industries.includes(value) ? f.industries.filter((x) => x !== value) : [...f.industries, value] }));
    setErrors((e) => ({ ...e, industries: undefined }));
  }

  // 처음 접속해 CSRF 쿠키가 없을 때의 csrf_invalid는 api.ts가 한 번 다시 보낸다
  const signUp = (body: SignUpRequest) => api<SignUpResponse>('/api/shops', { method: 'POST', body, silent: true });

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending) return;
    setError('');
    const found = validateStep(step, form);
    if (Object.keys(found).length > 0) return setErrors(found);
    setErrors({});
    if (!last) return setStep(step + 1);

    setPending(true);
    try {
      await signUp(toSignUpRequest(form));
      toast('가입이 끝났어요. 매장 계정으로 로그인해 주세요.');
      router.replace('/login');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid' && Array.isArray(err.details)) {
        // 칸별 사유: [{ field, reason }] (CONTRACTS 4장 POST /api/shops). 가장 앞 단계의 칸으로 돌아가 보여 준다
        const byField: FieldErrors = {};
        for (const d of err.details as { field: string; reason: string }[]) byField[d.field as SignUpField] ??= d.reason;
        setErrors(byField);
        const steps = Object.keys(byField).map(stepOf).filter((s) => s >= 0);
        if (steps.length > 0) setStep(Math.min(...steps));
      } else if (err instanceof ApiError && err.code === 'duplicate') {
        setErrors({ email: err.message });
        setStep(0);
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
        <p className={styles.stepNo} aria-live="polite">
          STEP {String(step + 1).padStart(2, '0')} / {String(STEPS.length).padStart(2, '0')} · {STEPS[step].title}
        </p>
        <div className={styles.bar} aria-hidden="true">
          {STEPS.map((s, i) => (
            <span key={s.title} className={i <= step ? styles.barOn : ''} />
          ))}
        </div>
        <p className={card.sub}>{STEP_SUB[step]}</p>

        <form className={card.form} onSubmit={onSubmit} noValidate>
          {STEPS[step].fields.map((name) => {
            if (name === 'industries') {
              return (
                <fieldset key={name} className={styles.industry}>
                  <legend className={card.label}>매장 업종 선택 *</legend>
                  <div className={styles.industryGrid}>
                    {INDUSTRIES.map(([value, label]) => (
                      <label key={value} className={`${styles.industryItem} ${form.industries.includes(value) ? styles.industryOn : ''}`}>
                        <input type="checkbox" name="industries" value={value} checked={form.industries.includes(value)} onChange={() => toggleIndustry(value)} />
                        {label}
                      </label>
                    ))}
                  </div>
                  {errors.industries && <span className={styles.fieldError}>{errors.industries}</span>}
                </fieldset>
              );
            }
            const f = FIELD[name];
            return (
              <label key={name} className={card.field}>
                <span className={card.label}>{f.label} *</span>
                <input
                  className={`${card.input} ${errors[name] ? styles.invalid : ''}`}
                  type={f.type}
                  name={name}
                  inputMode={name === 'ownerPin' || name === 'businessNumber' ? 'numeric' : undefined}
                  autoComplete={f.autoComplete}
                  placeholder={f.placeholder}
                  value={form[name]}
                  onChange={(e) => change(name, e.target.value)}
                  aria-invalid={Boolean(errors[name])}
                />
                {errors[name] ? <span className={styles.fieldError}>{errors[name]}</span> : f.hint ? <span className={styles.hint}>{f.hint}</span> : null}
              </label>
            );
          })}

          {last && (
            <div className={styles.notice}>
              <b>가입 후 진행할 초기 설정</b>
              <span>스탭 등록 · 개인 PIN 설정 · 기능별 권한 설정 · 기본 재방문 주기 설정 · 다국어 설정</span>
            </div>
          )}

          {error && (
            <p className={card.error} role="alert">
              {error}
            </p>
          )}

          <div className={styles.actions}>
            {step > 0 && (
              <button type="button" className={styles.back} disabled={pending} onClick={() => { setErrors({}); setError(''); setStep(step - 1); }}>
                이전
              </button>
            )}
            <button className={card.submit} type="submit" disabled={pending}>
              {last ? (pending ? '가입 중…' : '가입하기') : '다음'}
            </button>
          </div>
        </form>

        <p className={card.foot}>
          이미 계정이 있나요? <Link href="/login">로그인</Link>
        </p>
      </section>
    </main>
  );
}
