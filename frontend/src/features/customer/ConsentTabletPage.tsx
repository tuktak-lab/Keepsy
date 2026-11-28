import { useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { api, ApiError } from '@/lib/api';
import { consentChanges, consentLabel, OPTIONAL_CONSENTS, privacyDesc, privacyLabel, type ConsentChange, type ConsentState } from '@/lib/consents';
import { useShop } from '@/hooks/useIndustries';
import type { ConsentItem } from './types';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import styles from './ConsentTabletPage.module.css';
import { t } from '@/lib/i18n';

// 태블릿 본인 체크 (FR-CUS-09). 직원이 태블릿을 건네면 고객이 항목을 읽고 직접 체크한다.
// GET /api/customers/{id} · /consents로 지금 상태를 보여 주고, 바뀐 항목만 POST /api/customers/{id}/consents에 한 건씩 보낸다
// (방법 TABLET, 철회 경로는 서버 기본값 DIRECT). CONSENT는 공용 고정이다 (2장, E8). 잘못 입력한 동의 정정은 직원 화면(고객 상세) 몫이다.
// 고객이 보는 화면이라 직원 메뉴(사이드바)를 두지 않는다.

type Consents = { current: ConsentState[] };
type Step = 'loading' | 'form' | 'saving' | 'done' | 'error';

export default function ConsentTabletPage() {
  const customerId = Number(useSearchParams().get('customerId'));
  const valid = Number.isInteger(customerId) && customerId > 0;
  const [step, setStep] = useState<Step>(valid ? 'loading' : 'error');
  const [error, setError] = useState(valid ? '' : t('customer.tablet.noInfo'));
  const [name, setName] = useState('');
  const [current, setCurrent] = useState<ConsentState[]>([]);
  const [chosen, setChosen] = useState<Partial<Record<ConsentItem, boolean>>>({});
  const [saved, setSaved] = useState<ConsentChange[]>([]);
  const [loadTry, setLoadTry] = useState(0);

  useEffect(() => {
    if (!valid) return;
    Promise.all([
      api<{ name: string }>(`/api/customers/${customerId}`, { silent: true }),
      api<Consents>(`/api/customers/${customerId}/consents`, { silent: true }),
    ])
      .then(([customer, consents]) => {
        setName(customer.name);
        setCurrent(consents.current);
        setChosen(Object.fromEntries(consents.current.map((c) => [c.item, c.agreed])));
        setStep('form');
      })
      .catch(() => {
        // 고객이 보는 화면이라 서버 문구는 보이지 않는다 (#487)
        setError(t('common.loadFailed'));
        setStep('error');
      });
  }, [customerId, valid, loadTry]);

  // 동의서의 보관 기간은 매장 설정값 (FR-SET-06). 모르는 동안은 개인정보 동의를 새로 받지 않는다 (선택 항목은 그대로 받는다)
  const [shopTry, setShopTry] = useState(0);
  const shop = useShop(shopTry);
  const privacyText = privacyDesc(shop?.retentionYears);
  const privacyAgreed = current.some((c) => c.item === 'PRIVACY' && c.agreed);
  const toggle = (item: ConsentItem) => setChosen((c) => ({ ...c, [item]: !c[item] }));

  async function submit() {
    const changes = consentChanges(current, chosen);
    if (!privacyText && changes.some((c) => c.item === 'PRIVACY')) {
      setError(t('customer.tablet.retentionRetry'));
      return;
    }
    setError('');
    setStep('saving');
    const done: ConsentChange[] = [];
    try {
      // 항목마다 따로 저장한다 (CONTRACTS 6장: 요청 하나에 항목 하나)
      for (const change of changes) {
        await api(`/api/customers/${customerId}/consents`, {
          method: 'POST',
          body: { item: change.item, agreed: change.agreed, method: 'TABLET' },
          silent: true,
        });
        done.push(change);
      }
      setSaved(done);
      setStep('done');
    } catch (err) {
      // 앞에서 저장된 항목은 지금 상태로 반영해, 다시 누르면 남은 항목만 보낸다
      setCurrent((list) =>
        list.map((c) => {
          const d = done.find((x) => x.item === c.item);
          return d ? { ...c, agreed: d.agreed } : c;
        }),
      );
      setError(
        (err instanceof ApiError ? err.message : t('common.pin.tryLater')) +
          (done.length ? t('customer.tablet.partialSaved', { items: done.map((d) => consentLabel(d.item)).join(', ') }) : ''),
      );
      setStep('form');
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <img className={styles.logo} src={logoImg.src} alt="Keepsy" />

        {step === 'loading' && <p className={styles.sub}>{t('common.loading')}</p>}

        {step === 'error' && (
          <>
            <p className={styles.error} role="alert">
              {error}
            </p>
            {valid && (
              <button
                type="button"
                className={styles.submit}
                onClick={() => {
                  setError('');
                  setStep('loading');
                  setLoadTry((n) => n + 1);
                  setShopTry((n) => n + 1); // 보관 기간(매장 정보)도 함께 다시 받는다 (한 번 눌러 전부 복구)
                }}
              >
                {t('common.retry')}
              </button>
            )}
            <AppLink href="/customers" className={styles.staffLink}>
              {t('customer.tablet.staffToList')}
            </AppLink>
          </>
        )}

        {(step === 'form' || step === 'saving') && (
          <>
            <h1 className={styles.title}>{t('customer.tablet.title', { name })}</h1>
            <p className={styles.sub}>
              {t('customer.tablet.pick')}
              <br />
              {t('customer.tablet.optionalNote')}
            </p>

            <div className={styles.items}>
              <label className={`${styles.item} ${styles.required}`}>
                <input
                  type="checkbox"
                  checked={Boolean(chosen.PRIVACY)}
                  disabled={privacyAgreed || step === 'saving' || (!privacyText && !chosen.PRIVACY)}
                  onChange={() => toggle('PRIVACY')}
                />
                <span>
                  <b>
                    {privacyLabel()} <em>{t('customer.required')}</em>
                  </b>
                  <span className={styles.desc}>
                    {privacyText ??
                      (shop === undefined ? (
                        t('customer.new.retentionLoading')
                      ) : (
                        <>
                          {t('customer.tablet.retentionFailedShort')}{' '}
                          <button type="button" className={styles.staffLink} onClick={() => setShopTry((n) => n + 1)}>
                            {t('common.retry')}
                          </button>
                        </>
                      ))}
                  </span>
                </span>
              </label>

              {OPTIONAL_CONSENTS.map(([item, label, desc]) => (
                <label key={item} className={styles.item}>
                  <input type="checkbox" checked={Boolean(chosen[item])} disabled={step === 'saving'} onChange={() => toggle(item)} />
                  <span>
                    <b>
                      {t(label)} <em className={styles.optional}>{t('customer.optional')}</em>
                    </b>
                    <span className={styles.desc}>{t(desc)}</span>
                    {item === 'PHOTO' && current.some((c) => c.item === 'PHOTO' && c.agreed) && !chosen.PHOTO && (
                      <span className={styles.warn}>{t('customer.tablet.warnPhoto')}</span>
                    )}
                    {item === 'HEALTH' && current.some((c) => c.item === 'HEALTH' && c.agreed) && !chosen.HEALTH && (
                      <span className={styles.warn}>{t('customer.tablet.warnHealth')}</span>
                    )}
                  </span>
                </label>
              ))}
            </div>

            {error && (
              <p className={styles.error} role="alert">
                {error}
              </p>
            )}

            <button type="button" className={styles.submit} onClick={submit} disabled={step === 'saving'}>
              {step === 'saving' ? t('common.saving') : t('customer.tablet.confirmBtn')}
            </button>
          </>
        )}

        {step === 'done' && (
          <>
            <h1 className={styles.title}>{t('customer.tablet.thanks')}</h1>
            {saved.length > 0 ? (
              <ul className={styles.summary}>
                {saved.map((s) => (
                  <li key={s.item}>
                    {consentLabel(s.item)} <b>{s.agreed ? t('customer.agreed') : t('customer.withdrawn')}</b>
                  </li>
                ))}
              </ul>
            ) : (
              <p className={styles.sub}>{t('customer.tablet.noChange')}</p>
            )}
            <p className={styles.sub}>{t('customer.tablet.handBack')}</p>
            <AppLink href={`/customers/detail?id=${customerId}`} className={styles.staffLink}>
              {t('customer.tablet.staffToDetail')}
            </AppLink>
          </>
        )}
      </div>
    </div>
  );
}
