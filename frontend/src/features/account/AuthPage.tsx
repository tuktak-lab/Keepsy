import { useState, type ReactNode } from 'react';
import Link from 'next/link';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import { ChevronIcon } from './AuthFields';
import { LoginForm } from './LoginForm';
import { SignUpForm } from './SignUpForm';
import styles from './AuthPage.module.css';

type Mode = 'login' | 'register';

const PATH: Record<Mode, string> = { login: '/login', register: '/signup' };

// 와인 패널 글 안의 「Keepsy」는 글자 대신 로고 이미지로 (흰색으로 바꿔 와인 바탕에서 보이게)
const HeroLogo = () => <img className={styles.heroLogo} src={logoImg.src} alt="Keepsy" />;

function Hero(props: { variant: Mode; title: ReactNode; text: ReactNode; buttonLabel: string; onSwitch: () => void }) {
  return (
    <div className={`${styles.hero} ${styles[props.variant]}`}>
      <h2>{props.title}</h2>
      <p>{props.text}</p>
      <button type="button" className={styles.switch} onClick={props.onSwitch}>
        {props.buttonLabel}
        <ChevronIcon />
      </button>
    </div>
  );
}

// 매장 로그인 · 가입 (FR-ACC-01 · 02). 한 카드에서 슬라이드 패널로 오간다. 주소는 /login · /signup 그대로 두고,
// 전환하면 새로 이동하지 않고 주소창만 바꾼다 (정적 빌드라 두 경로가 같은 화면을 시작 모드만 달리 연다).
export default function AuthPage(props: { initial: Mode }) {
  const [mode, setMode] = useState<Mode>(props.initial);
  const [signUpRun, setSignUpRun] = useState(0); // 가입이 끝나면 가입 폼을 새로 만들어 입력값을 비운다

  function go(next: Mode) {
    setMode(next);
    window.history.replaceState(null, '', PATH[next]);
  }

  return (
    <main className={styles.page}>
      <Link href="/" className={styles.logo} aria-label="Keepsy 소개 화면으로">
        <img src={logoImg.src} alt="Keepsy" />
      </Link>

      <div className={`${styles.card} ${mode === 'register' ? styles.register : ''}`}>
        <div className={styles.cardBg} aria-hidden="true" />

        <Hero
          variant="register"
          title="이미 계정이 있나요?"
          text={
            <>
              {'매장 계정으로 로그인하고\n오늘도 '}
              <HeroLogo />와 함께하세요.
            </>
          }
          buttonLabel="로그인"
          onSwitch={() => go('login')}
        />
        <SignUpForm
          key={signUpRun}
          onRegistered={() => {
            setSignUpRun((n) => n + 1);
            go('login');
          }}
          onSwitch={() => go('login')}
        />

        <Hero
          variant="login"
          title={
            <>
              <HeroLogo />가 처음이신가요?
            </>
          }
          text={'고객 기록과 재방문 흐름을\n한곳에서 관리할 수 있어요.'}
          buttonLabel="매장 가입"
          onSwitch={() => go('register')}
        />
        <LoginForm autoFocus={props.initial === 'login'} onSwitch={() => go('register')} />
      </div>
    </main>
  );
}
