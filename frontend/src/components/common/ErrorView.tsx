'use client';

import { useEffect } from 'react';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import { ChevronIcon } from '@/features/account/AuthFields';
import auth from '@/features/account/AuthPage.module.css';
import styles from './ErrorView.module.css';
import { t } from '@/lib/i18n';

// 화면 오류(error boundary) 공통 화면 (#407). app/error.tsx · app/(shop)/error.tsx · app/global-error.tsx가 쓴다.
// 로그인 · 404 화면과 같은 유리 카드 + 와인 패널. 오류 내용(메시지 · 스택)은 화면에 보여 주지 않는다.
// inShell: 매장 화면 안(사이드바 · 상단바는 그대로)에서는 로고 없이 카드만 본문 자리에 그린다.
// 버튼 · 링크 모두 페이지를 새로 불러온다. 화면 안 이동(Link)이나 같은 코드로 다시 그리기(retry)로는
// 레이아웃 · 모듈에서 난 오류가 그대로 남거나, 같은 주소(예: 대시보드)에서 난 오류면 아무 일도 일어나지 않는다.
export function ErrorView(props: { error: unknown; inShell?: boolean }) {
  const { error } = props;

  useEffect(() => {
    // 개발자 도구에서 원인을 볼 수 있게 남긴다 (화면에는 내용을 보여 주지 않음)
    console.error(error);
  }, [error]);

  const card = (
    <div className={`${auth.card} ${props.inShell ? styles.shellCard : ''}`}>
      <div className={auth.cardBg} aria-hidden="true" />

      <div className={`${auth.hero} ${auth.login}`}>
        <h2>{t('error.hero.title')}</h2>
        <p>{t('error.hero.body')}</p>
        <a href="/dashboard" className={`${auth.switch} ${styles.plain}`}>
          {t('common.toDashboard')}
          <ChevronIcon />
        </a>
      </div>

      <section className={`${auth.form} ${auth.login}`} aria-labelledby="error-title" role="alert">
        <p className={styles.eyebrow} aria-hidden="true">
          Error
        </p>
        <h1 id="error-title">{t('error.title')}</h1>
        <p className={auth.sub}>
          {t('error.body.line1')}
          <br />
          {t('error.body.line2')}
        </p>
        <button type="button" className={auth.submit} onClick={() => window.location.reload()}>
          {t('error.retry')}
          <ChevronIcon />
        </button>
        {/* 좁은 화면에서는 오른쪽 패널이 숨으므로 대시보드 링크를 버튼 아래에 둔다 */}
        <p className={auth.formSwitch}>
          <a href="/dashboard" className={styles.textLink}>
            {t('common.toDashboard')}
          </a>
        </p>
      </section>
    </div>
  );

  if (props.inShell) return <div className={styles.shell}>{card}</div>;

  return (
    <main className={auth.page}>
      <a href="/" className={`${auth.logo} ${styles.plain}`} aria-label={t('common.logoLabel')}>
        <img src={logoImg.src} alt="Keepsy" />
      </a>
      {card}
    </main>
  );
}
