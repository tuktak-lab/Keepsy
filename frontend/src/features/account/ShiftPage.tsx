import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { logout, refreshSession, useSession } from '@/lib/session';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import type { WorkerRequest, WorkerResponse } from './types';
import card from './LoginPage.module.css';
import styles from './ShiftPage.module.css';

const PIN_MAX = 6; // PIN은 숫자 4~6자리 (C21)
const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'clear', '0', 'back'] as const;

// 근무자 PIN 입력 · 근무자 전환 (FR-ACC-10). 매장 로그인 뒤 스탭이 자기 PIN을 넣으면 현재 근무자가 된다.
// 교대할 때도 같은 화면 · 같은 API(PUT /api/session/worker)에 다른 PIN을 넣는다.
export default function ShiftPage() {
  const router = useRouter();
  // 매장 이름과 지금 근무자. 로그인이 안 돼 있으면 api()가 /login으로 보낸다.
  const session = useSession();
  const [pin, setPin] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  function press(key: (typeof KEYS)[number]) {
    setError('');
    if (key === 'clear') setPin('');
    else if (key === 'back') setPin((p) => p.slice(0, -1));
    else setPin((p) => (p.length < PIN_MAX ? p + key : p));
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending || pin.length < 4) return;
    setError('');
    setPending(true);
    try {
      const body: WorkerRequest = { pin };
      // 오류(pin_invalid · pin_locked 등)는 전역 토스트 대신 폼 안에 보여 준다 (silent)
      await api<WorkerResponse>('/api/session/worker', { method: 'PUT', body, silent: true });
      await refreshSession(); // 상단 근무자 표시를 맞춘다 (lib/session.ts)
      router.replace('/dashboard');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
      setPin('');
      setPending(false);
    }
  }

  const current = session?.currentStaff;

  return (
    <main className={card.page}>
      <section className={`${card.card} ${styles.card}`} aria-labelledby="shift-title">
        <img className={card.logo} src={logoImg.src} alt="Keepsy" />
        {session && <p className={styles.shop}>{session.shopName}</p>}
        <h1 id="shift-title" className={card.title}>
          {current ? '근무자 전환' : '근무 시작'}
        </h1>
        <p className={card.sub}>
          {current ? (
            <>
              지금 근무자는 <strong className={styles.current}>{current.name}</strong>님입니다. 교대할 스탭의 PIN을 입력하세요.
            </>
          ) : (
            '본인 PIN을 입력하면 근무를 시작합니다.'
          )}
        </p>

        <form onSubmit={onSubmit}>
          <input
            className={styles.pin}
            type="password"
            inputMode="numeric"
            autoComplete="off"
            aria-label="PIN"
            placeholder="PIN 4~6자리"
            maxLength={PIN_MAX}
            value={pin}
            onChange={(e) => {
              setError('');
              setPin(e.target.value.replace(/\D/g, '').slice(0, PIN_MAX));
            }}
            autoFocus
          />

          <div className={styles.keypad}>
            {KEYS.map((key) => (
              <button
                key={key}
                type="button"
                className={key.length === 1 ? styles.key : `${styles.key} ${styles.keySub}`}
                onClick={() => press(key)}
              >
                {key === 'clear' ? '전체 지움' : key === 'back' ? '← 지움' : key}
              </button>
            ))}
          </div>

          {error && (
            <p className={`${card.error} ${styles.error}`} role="alert">
              {error}
            </p>
          )}

          <button className={`${card.submit} ${styles.submit}`} type="submit" disabled={pending || pin.length < 4}>
            {pending ? '확인 중…' : current ? '전환' : '시작'}
          </button>
        </form>

        <p className={card.foot}>
          다른 매장 계정인가요?{' '}
          <button type="button" className={styles.linkButton} onClick={logout}>
            로그아웃
          </button>
        </p>
      </section>
    </main>
  );
}
