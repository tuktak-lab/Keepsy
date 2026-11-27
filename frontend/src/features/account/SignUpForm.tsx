import { useState, type ChangeEvent, type FormEvent } from 'react';
import { api, ApiError } from '@/lib/api';
import { toast } from '@/components/common/Toast';
import { EMPTY_SIGNUP, INDUSTRIES, STEPS, stepOf, toSignUpRequest, validateStep, type SignUpField, type SignUpValues } from '@/lib/signup';
import { ChevronIcon, InfoIcon, PasswordInput } from './AuthFields';
import type { SignUpRequest, SignUpResponse } from './types';
import styles from './AuthPage.module.css';
import { t } from '@/lib/i18n';

type FieldErrors = Partial<Record<SignUpField, string>>;

// 칸마다 화면에 보이는 모양. 이름은 단계 규칙(lib/signup.ts)의 칸 이름과 같다
const fields = (): Record<Exclude<SignUpField, 'industries'>, { label: string; type: string; autoComplete: string; placeholder?: string; hint?: string }> => ({
  email: { label: t('account.signup.emailLabel'), type: 'email', autoComplete: 'username', placeholder: 'shop@example.com' },
  password: { label: t('account.signup.passwordLabel'), type: 'password', autoComplete: 'new-password', placeholder: t('account.password.placeholder') },
  passwordConfirm: { label: t('account.signup.confirmLabel'), type: 'password', autoComplete: 'new-password', placeholder: t('account.password.again') },
  ownerName: { label: t('account.signup.ownerLabel'), type: 'text', autoComplete: 'name', placeholder: t('account.signup.ownerPlaceholder') },
  ownerPin: { label: t('account.signup.pinLabel'), type: 'password', autoComplete: 'off', placeholder: t('account.signup.pinPlaceholder'), hint: t('account.signup.pinHint') },
  shopName: { label: t('account.signup.shopLabel'), type: 'text', autoComplete: 'organization', placeholder: t('account.signup.shopPlaceholder') },
  phone: { label: t('account.signup.phoneLabel'), type: 'tel', autoComplete: 'tel', placeholder: '02-000-0000', hint: t('account.signup.phoneHint') },
});

const stepSub = () => [t('account.signup.sub1'), t('account.signup.sub2'), t('account.signup.sub3')];

// 비밀번호 칸은 「보기」 버튼, PIN 칸은 안내 아이콘이 오른쪽 끝에 붙는다
const REVEALABLE = new Set<SignUpField>(['password', 'passwordConfirm']);

// 매장 가입 (FR-ACC-01). 매장 1개와 원장 스탭을 만든다. 가입만 하고 로그인은 되지 않는다 (CONTRACTS 4장 POST /api/shops).
// 3단계: 개인정보 → 매장 정보 → 업종 설정. 단계마다 그 단계 칸만 검사하고, 마지막에 한 번에 보낸다.
export function SignUpForm(props: { onRegistered: () => void; onSwitch: () => void }) {
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<SignUpValues>(EMPTY_SIGNUP);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const last = step === STEPS.length - 1;

  function change(name: Exclude<SignUpField, 'industries'>, value: string) {
    const v = name === 'ownerPin' ? value.replace(/\D/g, '').slice(0, 6) : value;
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
      toast(t('account.signup.done'));
      props.onRegistered(); // 로그인 쪽으로 넘어가며 이 폼은 비워진다 (비밀번호 · PIN을 남기지 않는다)
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
        setError(err instanceof ApiError ? err.message : t('common.pin.tryLater'));
      }
      setPending(false);
    }
  }

  return (
    <form className={`${styles.form} ${styles.register}`} onSubmit={onSubmit} noValidate aria-labelledby="signup-title">
      <h1 id="signup-title">{t('account.hero.signup')}</h1>
      <p className={styles.stepNo} aria-live="polite">
        STEP {String(step + 1).padStart(2, '0')} / {String(STEPS.length).padStart(2, '0')} · {t(STEPS[step].title)}
      </p>
      <div className={styles.bar} aria-hidden="true">
        {STEPS.map((s, i) => (
          <span key={s.title} className={i <= step ? styles.barOn : ''} />
        ))}
      </div>
      <p className={styles.sub}>{stepSub()[step]}</p>

      {STEPS[step].fields.map((name) => {
        if (name === 'industries') {
          return (
            <fieldset key={name} className={styles.industry}>
              <legend>{t('account.signup.legend')}</legend>
              <div className={styles.industryGrid}>
                {INDUSTRIES.map(([value, label]) => (
                  <label key={value} className={`${styles.industryItem} ${form.industries.includes(value) ? styles.industryOn : ''}`}>
                    <input type="checkbox" name="industries" value={value} checked={form.industries.includes(value)} onChange={() => toggleIndustry(value)} />
                    {t(label)}
                  </label>
                ))}
              </div>
              {errors.industries && <span className={styles.fieldError}>{errors.industries}</span>}
            </fieldset>
          );
        }
        const f = fields()[name];
        const id = `signup-${name}`;
        const shared = {
          id,
          name,
          inputMode: name === 'ownerPin' ? ('numeric' as const) : undefined,
          autoComplete: f.autoComplete,
          placeholder: f.placeholder,
          value: form[name],
          onChange: (e: ChangeEvent<HTMLInputElement>) => change(name, e.target.value),
          'aria-invalid': Boolean(errors[name]),
        };
        const invalid = errors[name] ? styles.invalid : '';
        return (
          <div key={name} className={styles.field}>
            <label htmlFor={id} className={styles.label}>
              {f.label} *
            </label>
            {REVEALABLE.has(name) ? (
              <PasswordInput {...shared} className={invalid} />
            ) : name === 'ownerPin' ? (
              <span className={styles.inputWrap}>
                <input {...shared} className={`${styles.input} ${styles.hasIcon} ${invalid}`} type={f.type} />
                <span className={styles.inputIcon}>
                  <InfoIcon />
                </span>
              </span>
            ) : (
              <input {...shared} className={`${styles.input} ${invalid}`} type={f.type} />
            )}
            {errors[name] ? (
              <span className={styles.fieldError}>{errors[name]}</span>
            ) : f.hint ? (
              <span className={styles.hint}>
                <InfoIcon />
                {f.hint}
              </span>
            ) : null}
          </div>
        );
      })}

      {last && (
        <div className={styles.notice}>
          <b>{t('account.signup.noticeTitle')}</b>
          <span>{t('account.signup.noticeBody')}</span>
        </div>
      )}

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      <div className={styles.actions}>
        {step > 0 && (
          <button type="button" className={styles.back} disabled={pending} onClick={() => { setErrors({}); setError(''); setStep(step - 1); }}>
            {t('customer.list.prev')}
          </button>
        )}
        <button className={styles.submit} type="submit" disabled={pending}>
          {last ? (pending ? t('account.signup.pending') : t('account.signup.submit')) : t('customer.list.next')}
          {!last && <ChevronIcon />}
        </button>
      </div>

      {/* 모바일에서만 보인다 (데스크톱은 옆 패널 버튼을 쓴다) */}
      <p className={styles.formSwitch}>
        {t('account.hero.hasAccount')}
        <button type="button" onClick={props.onSwitch}>
          {t('account.hero.login')}
        </button>
      </p>
    </form>
  );
}
