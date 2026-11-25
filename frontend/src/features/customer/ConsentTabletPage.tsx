import { useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { api, ApiError } from '@/lib/api';
import { consentChanges, OPTIONAL_CONSENTS, PRIVACY_TEXT, type ConsentChange, type ConsentState } from '@/lib/consents';
import type { ConsentItem } from './types';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import styles from './ConsentTabletPage.module.css';

// 태블릿 본인 체크 (FR-CUS-09). 직원이 태블릿을 건네면 고객이 항목을 읽고 직접 체크한다.
// GET /api/customers/{id} · /consents로 지금 상태를 보여 주고, 바뀐 항목만 POST /api/customers/{id}/consents에 한 건씩 보낸다
// (방법 TABLET, 철회 경로는 서버 기본값 DIRECT). CONSENT는 공용 고정이다 (2장, E8). 잘못 입력한 동의 정정은 직원 화면(고객 상세) 몫이다.
// 고객이 보는 화면이라 직원 메뉴(사이드바)를 두지 않는다.

type Consents = { current: ConsentState[] };
type Step = 'loading' | 'form' | 'saving' | 'done' | 'error';

const LABEL: Record<ConsentItem, string> = {
  PRIVACY: PRIVACY_TEXT.label,
  ...(Object.fromEntries(OPTIONAL_CONSENTS.map(([item, label]) => [item, label])) as Record<Exclude<ConsentItem, 'PRIVACY'>, string>),
};

export default function ConsentTabletPage() {
  const customerId = Number(useSearchParams().get('customerId'));
  const valid = Number.isInteger(customerId) && customerId > 0;
  const [step, setStep] = useState<Step>(valid ? 'loading' : 'error');
  const [error, setError] = useState(valid ? '' : '고객 정보가 없어요. 직원에게 태블릿을 돌려주세요.');
  const [name, setName] = useState('');
  const [current, setCurrent] = useState<ConsentState[]>([]);
  const [chosen, setChosen] = useState<Partial<Record<ConsentItem, boolean>>>({});
  const [saved, setSaved] = useState<ConsentChange[]>([]);

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
      .catch((err) => {
        setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
        setStep('error');
      });
  }, [customerId, valid]);

  const privacyAgreed = current.some((c) => c.item === 'PRIVACY' && c.agreed);
  const toggle = (item: ConsentItem) => setChosen((c) => ({ ...c, [item]: !c[item] }));

  async function submit() {
    const changes = consentChanges(current, chosen);
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
        (err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요') +
          (done.length ? ` (${done.map((d) => LABEL[d.item]).join(', ')}은 저장했어요)` : ''),
      );
      setStep('form');
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <img className={styles.logo} src={logoImg.src} alt="Keepsy" />

        {step === 'loading' && <p className={styles.sub}>불러오는 중…</p>}

        {step === 'error' && (
          <>
            <p className={styles.error} role="alert">
              {error}
            </p>
            <AppLink href="/customers" className={styles.staffLink}>
              직원: 고객 목록으로
            </AppLink>
          </>
        )}

        {(step === 'form' || step === 'saving') && (
          <>
            <h1 className={styles.title}>{name} 고객님, 동의 항목을 확인해 주세요</h1>
            <p className={styles.sub}>
              원하시는 항목만 체크해 주세요.
              <br />
              선택 항목은 동의하지 않아도 시술을 받으실 수 있고, 언제든 바꾸실 수 있어요.
            </p>

            <div className={styles.items}>
              <label className={`${styles.item} ${styles.required}`}>
                <input
                  type="checkbox"
                  checked={Boolean(chosen.PRIVACY)}
                  disabled={privacyAgreed || step === 'saving'}
                  onChange={() => toggle('PRIVACY')}
                />
                <span>
                  <b>
                    {PRIVACY_TEXT.label} <em>필수</em>
                  </b>
                  <span className={styles.desc}>{PRIVACY_TEXT.desc}</span>                </span>
              </label>

              {OPTIONAL_CONSENTS.map(([item, label, desc]) => (
                <label key={item} className={styles.item}>
                  <input type="checkbox" checked={Boolean(chosen[item])} disabled={step === 'saving'} onChange={() => toggle(item)} />
                  <span>
                    <b>
                      {label} <em className={styles.optional}>선택</em>
                    </b>
                    <span className={styles.desc}>{desc}</span>
                    {item === 'PHOTO' && current.some((c) => c.item === 'PHOTO' && c.agreed) && !chosen.PHOTO && (
                      <span className={styles.warn}>체크를 풀면 저장된 시술 사진이 바로 삭제돼요.</span>
                    )}
                    {item === 'HEALTH' && current.some((c) => c.item === 'HEALTH' && c.agreed) && !chosen.HEALTH && (
                      <span className={styles.warn}>체크를 풀면 기록된 건강 주의사항이 바로 삭제돼요.</span>
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
              {step === 'saving' ? '저장 중…' : '확인했어요'}
            </button>
          </>
        )}

        {step === 'done' && (
          <>
            <h1 className={styles.title}>확인해 주셔서 감사해요</h1>
            {saved.length > 0 ? (
              <ul className={styles.summary}>
                {saved.map((s) => (
                  <li key={s.item}>
                    {LABEL[s.item]} <b>{s.agreed ? '동의' : '철회'}</b>
                  </li>
                ))}
              </ul>
            ) : (
              <p className={styles.sub}>바뀐 내용 없이 그대로 두었어요.</p>
            )}
            <p className={styles.sub}>태블릿을 직원에게 돌려주세요.</p>
            <AppLink href={`/customers/detail?id=${customerId}`} className={styles.staffLink}>
              직원: 고객 상세로
            </AppLink>
          </>
        )}
      </div>
    </div>
  );
}
