import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { api, ApiError } from '@/lib/api';
import { CONSENT_METHODS } from '@/lib/newCustomer';
import { OPTIONAL_CONSENTS, PRIVACY_TEXT } from '@/lib/consents';
import type {
  AcquisitionChannel,
  ConsentItem,
  ConsentMethod,
  CustomerCreateRequest,
  CustomerCreateResponse,
  Gender,
} from './types';
import styles from './CustomerNewPage.module.css';

// 고객 등록 (FR-CUS-01). POST /api/customers. 처리 스탭은 서버가 현재 근무자로 남긴다 (FR-ACC-10).
// 개인정보 수집 · 이용 동의(PRIVACY)가 없으면 고객으로 등록하지 않는다 (E3) — 그런 손님은 고객 없는 예약으로 시간만 막는다.
// 고객 선호 언어는 받지 않는다 (FR-I18N-01 범위 제외, F75). API 필드는 선택이라 보내지 않아도 된다.

const GENDERS: [Gender, string][] = [
  ['FEMALE', '여성'],
  ['MALE', '남성'],
  ['OTHER', '기타'],
];
const CHANNELS: [AcquisitionChannel, string][] = [
  ['INSTAGRAM', '인스타그램'],
  ['NAVER_SEARCH', '네이버 검색'],
  ['REFERRAL', '지인 소개'],
  ['PASSING_BY', '지나가다'],
  ['ETC', '기타'],
];
type Field = 'name' | 'phone' | 'birthYear' | 'consents' | 'consentMethod';

export default function CustomerNewPage() {
  const router = useRouter();
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [gender, setGender] = useState<Gender | ''>('');
  const [birthYear, setBirthYear] = useState('');
  const [channel, setChannel] = useState<AcquisitionChannel | ''>('');
  const [privacy, setPrivacy] = useState(false);
  const [consents, setConsents] = useState<ConsentItem[]>([]);
  const [method, setMethod] = useState<ConsentMethod | ''>('');
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  const toggle = (item: ConsentItem) =>
    setConsents((list) => (list.includes(item) ? list.filter((c) => c !== item) : [...list, item]));

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending || !privacy || !method) return;
    setError('');
    setErrors({});
    setPending(true);
    const body: CustomerCreateRequest = {
      name: name.trim(),
      phone: phone.trim(),
      consents: ['PRIVACY', ...consents],
      consentMethod: method,
      gender: gender || undefined,
      birthYear: birthYear ? Number(birthYear) : undefined,
      acquisitionChannel: channel || undefined,
    };
    try {
      await api<CustomerCreateResponse>('/api/customers', { method: 'POST', body, silent: true });
      toast(`${body.name} 고객을 등록했어요.`);
      router.push('/customers');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid' && Array.isArray(err.details)) {
        const byField: Partial<Record<Field, string>> = {};
        for (const d of err.details as { field: string; reason: string }[]) byField[d.field as Field] ??= d.reason;
        setErrors(byField);
      } else if (err instanceof ApiError && err.code === 'duplicate') {
        setErrors({ phone: err.message });
      } else {
        setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
      }
      setPending(false);
    }
  }

  const fieldError = (f: Field) => errors[f] && <span className={styles.fieldError}>{errors[f]}</span>;

  return (
    <>
      <form className={styles.wrap} onSubmit={onSubmit}>
        <div className={styles.head}>
          <h1 className={styles.title}>고객 등록</h1>
          <p className={styles.sub}>개인정보 수집 · 이용 동의를 받은 손님만 고객으로 등록해요.</p>
        </div>

        <section className={styles.card} aria-labelledby="basic">
          <h2 id="basic" className={styles.cardTitle}>기본 정보</h2>
          <div className={styles.grid}>
            <label className={styles.field}>
              <span className={styles.label}>
                이름 <em>필수</em>
              </span>
              <input className={styles.input} value={name} onChange={(e) => setName(e.target.value)} maxLength={50} required aria-invalid={Boolean(errors.name)} />
              {fieldError('name')}
            </label>
            <label className={styles.field}>
              <span className={styles.label}>
                휴대폰 <em>필수</em>
              </span>
              <input
                className={styles.input}
                type="tel"
                inputMode="numeric"
                placeholder="010-0000-0000"
                value={phone}
                onChange={(e) => setPhone(e.target.value.replace(/[^\d-]/g, '').slice(0, 13))}
                required
                aria-invalid={Boolean(errors.phone)}
              />
              {fieldError('phone')}
            </label>
            <div className={styles.field}>
              <span className={styles.label}>성별</span>
              <div className={styles.segment} role="radiogroup" aria-label="성별">
                {([['', '선택 안 함'], ...GENDERS] as [Gender | '', string][]).map(([value, label]) => (
                  <button key={value} type="button" role="radio" aria-checked={gender === value} onClick={() => setGender(value)}>
                    {label}
                  </button>
                ))}
              </div>
            </div>
            <label className={styles.field}>
              <span className={styles.label}>출생 연도</span>
              <input
                className={styles.input}
                inputMode="numeric"
                placeholder="예: 1995"
                value={birthYear}
                onChange={(e) => setBirthYear(e.target.value.replace(/\D/g, '').slice(0, 4))}
                aria-invalid={Boolean(errors.birthYear)}
              />
              {fieldError('birthYear')}
            </label>
            <label className={styles.field}>
              <span className={styles.label}>유입 경로</span>
              <select className={styles.input} value={channel} onChange={(e) => setChannel(e.target.value as AcquisitionChannel | '')}>
                <option value="">선택 안 함</option>
                {CHANNELS.map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </label>
          </div>
        </section>

        <section className={styles.card} aria-labelledby="consent">
          <h2 id="consent" className={styles.cardTitle}>동의</h2>

          <label className={`${styles.consent} ${styles.required}`}>
            <input type="checkbox" checked={privacy} onChange={(e) => setPrivacy(e.target.checked)} />
            <span>
              <b>
                {PRIVACY_TEXT.label} <em>필수</em>
              </b>
              <span className={styles.consentDesc}>{PRIVACY_TEXT.desc}</span>
            </span>
          </label>
          {fieldError('consents')}

          <div className={styles.consentGrid}>
            {OPTIONAL_CONSENTS.map(([item, label, desc]) => (
              <label key={item} className={styles.consent}>
                <input type="checkbox" checked={consents.includes(item)} onChange={() => toggle(item)} />
                <span>
                  <b>{label}</b>
                  <span className={styles.consentDesc}>{desc}</span>
                </span>
              </label>
            ))}
          </div>

          <div className={styles.field}>
            <span className={styles.label}>
              동의 받은 방법 <em>필수</em>
            </span>
            <div className={styles.segment} role="radiogroup" aria-label="동의 받은 방법">
              {CONSENT_METHODS.map(([value, label]) => (
                <button key={value} type="button" role="radio" aria-checked={method === value} onClick={() => setMethod(value)}>
                  {label}
                </button>
              ))}
            </div>
            {fieldError('consentMethod')}
          </div>
        </section>

        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}

        <div className={styles.actions}>
          {!privacy ? (
            <span className={styles.hint}>개인정보 동의를 받지 못한 손님은 등록하지 말고, 예약만 고객 없이 잡아 주세요.</span>
          ) : !method ? (
            <span className={styles.hint}>동의 받은 방법을 골라 주세요.</span>
          ) : null}
          <AppLink href="/customers" className={styles.secondary}>
            취소
          </AppLink>
          <button className={styles.primary} type="submit" disabled={pending || !privacy || !method}>
            {pending ? '등록 중…' : '고객 등록'}
          </button>
        </div>
      </form>
    </>
  );
}
