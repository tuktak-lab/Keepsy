import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { api, ApiError } from '@/lib/api';
import { CONSENT_METHODS } from '@/lib/newCustomer';
import { OPTIONAL_CONSENTS, privacyDesc, privacyLabel } from '@/lib/consents';
import { useShop } from '@/hooks/useIndustries';
import type {
  AcquisitionChannel,
  ConsentItem,
  ConsentMethod,
  CustomerCreateRequest,
  CustomerCreateResponse,
  Gender,
} from './types';
import styles from './CustomerNewPage.module.css';
import type { MessageKey } from '@/lib/messages/ko';
import { t } from '@/lib/i18n';

// 고객 등록 (FR-CUS-01). POST /api/customers. 처리 스탭은 서버가 현재 근무자로 남긴다 (FR-ACC-10).
// 개인정보 수집 · 이용 동의(PRIVACY)가 없으면 고객으로 등록하지 않는다 (E3) — 그런 손님은 고객 없는 예약으로 시간만 막는다.
// 고객 선호 언어는 받지 않는다 (FR-I18N-01 범위 제외, F76). API 필드는 선택이라 보내지 않아도 된다.

const GENDERS: [Gender, MessageKey][] = [
  ['FEMALE', 'customer.gender.female'],
  ['MALE', 'customer.gender.male'],
  ['OTHER', 'customer.gender.other'],
];
const CHANNELS: [AcquisitionChannel, MessageKey][] = [
  ['INSTAGRAM', 'customer.channel.instagram'],
  ['NAVER_SEARCH', 'customer.channel.naver'],
  ['REFERRAL', 'customer.channel.referral'],
  ['PASSING_BY', 'customer.channel.passing'],
  ['ETC', 'customer.channel.etc'],
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
  // 동의서의 보관 기간은 매장 설정값 (FR-SET-06). 모르는 동안은 개인정보 동의를 받지 않는다
  const [shopTry, setShopTry] = useState(0);
  const shop = useShop(shopTry);
  const privacyText = privacyDesc(shop?.retentionYears);

  const toggle = (item: ConsentItem) =>
    setConsents((list) => (list.includes(item) ? list.filter((c) => c !== item) : [...list, item]));

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending || !privacy || !method || !privacyText) return;
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
      toast(t('customer.new.toast', { name: body.name }));
      router.push('/customers');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid' && Array.isArray(err.details)) {
        const byField: Partial<Record<Field, string>> = {};
        for (const d of err.details as { field: string; reason: string }[]) byField[d.field as Field] ??= d.reason;
        setErrors(byField);
      } else if (err instanceof ApiError && err.code === 'duplicate') {
        setErrors({ phone: err.message });
      } else {
        setError(err instanceof ApiError ? err.message : t('common.pin.tryLater'));
      }
      setPending(false);
    }
  }

  const fieldError = (f: Field) => errors[f] && <span className={styles.fieldError}>{errors[f]}</span>;

  return (
    <>
      <form className={styles.wrap} onSubmit={onSubmit}>
        <div className={styles.head}>
          <h1 className={styles.title}>{t('customer.new.title')}</h1>
          <p className={styles.sub}>{t('customer.new.sub')}</p>
        </div>

        <section className={styles.card} aria-labelledby="basic">
          <h2 id="basic" className={styles.cardTitle}>{t('customer.new.basic')}</h2>
          <div className={styles.grid}>
            <label className={styles.field}>
              <span className={styles.label}>
                {t('customer.new.name')} <em>{t('customer.required')}</em>
              </span>
              <input className={styles.input} value={name} onChange={(e) => setName(e.target.value)} maxLength={50} required aria-invalid={Boolean(errors.name)} />
              {fieldError('name')}
            </label>
            <label className={styles.field}>
              <span className={styles.label}>
                {t('customer.new.phone')} <em>{t('customer.required')}</em>
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
              <span className={styles.label}>{t('customer.new.gender')}</span>
              <div className={styles.segment} role="radiogroup" aria-label={t('customer.new.gender')}>
                {([['', 'customer.new.notSelected'], ...GENDERS] as [Gender | '', MessageKey][]).map(([value, label]) => (
                  <button key={value} type="button" role="radio" aria-checked={gender === value} onClick={() => setGender(value)}>
                    {t(label)}
                  </button>
                ))}
              </div>
            </div>
            <label className={styles.field}>
              <span className={styles.label}>{t('customer.new.birthYear')}</span>
              <input
                className={styles.input}
                inputMode="numeric"
                placeholder={t('customer.new.birthPlaceholder')}
                value={birthYear}
                onChange={(e) => setBirthYear(e.target.value.replace(/\D/g, '').slice(0, 4))}
                aria-invalid={Boolean(errors.birthYear)}
              />
              {fieldError('birthYear')}
            </label>
            <label className={styles.field}>
              <span className={styles.label}>{t('customer.new.channelLabel')}</span>
              <select className={styles.input} value={channel} onChange={(e) => setChannel(e.target.value as AcquisitionChannel | '')}>
                <option value="">{t('customer.new.notSelected')}</option>
                {CHANNELS.map(([value, label]) => (
                  <option key={value} value={value}>{t(label)}</option>
                ))}
              </select>
            </label>
          </div>
        </section>

        <section className={styles.card} aria-labelledby="consent">
          <h2 id="consent" className={styles.cardTitle}>{t('customer.new.consentTitle')}</h2>

          <label className={`${styles.consent} ${styles.required}`}>
            <input type="checkbox" checked={privacy && privacyText !== null} disabled={!privacyText} onChange={(e) => setPrivacy(e.target.checked)} />
            <span>
              <b>
                {privacyLabel()} <em>{t('customer.required')}</em>
              </b>
              <span className={styles.consentDesc}>
                {privacyText ??
                  (shop === undefined ? (
                    t('customer.new.retentionLoading')
                  ) : (
                    <>
                      {t('customer.new.retentionFailed')}{' '}
                      <button type="button" className={styles.secondary} onClick={() => setShopTry((n) => n + 1)}>
                        {t('common.retry')}
                      </button>
                    </>
                  ))}
              </span>
            </span>
          </label>
          {fieldError('consents')}

          <div className={styles.consentGrid}>
            {OPTIONAL_CONSENTS.map(([item, label, desc]) => (
              <label key={item} className={styles.consent}>
                <input type="checkbox" checked={consents.includes(item)} onChange={() => toggle(item)} />
                <span>
                  <b>{t(label)}</b>
                  <span className={styles.consentDesc}>{t(desc)}</span>
                </span>
              </label>
            ))}
          </div>

          <div className={styles.field}>
            <span className={styles.label}>
              {t('customer.new.method')} <em>{t('customer.required')}</em>
            </span>
            <div className={styles.segment} role="radiogroup" aria-label={t('customer.new.method')}>
              {CONSENT_METHODS.map(([value, label]) => (
                <button key={value} type="button" role="radio" aria-checked={method === value} onClick={() => setMethod(value)}>
                  {t(label)}
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
            <span className={styles.hint}>{t('customer.new.hintNoPrivacy')}</span>
          ) : !method ? (
            <span className={styles.hint}>{t('customer.new.hintMethod')}</span>
          ) : null}
          <AppLink href="/customers" className={styles.secondary}>
            {t('common.cancel')}
          </AppLink>
          <button className={styles.primary} type="submit" disabled={pending || !privacy || !method || !privacyText}>
            {pending ? t('reservation.newCustomer.registering') : t('customer.new.title')}
          </button>
        </div>
      </form>
    </>
  );
}
