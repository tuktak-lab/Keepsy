'use client'; // AuthFields(ChevronIcon)가 클라이언트 모듈이라 함께 클라이언트로 그린다

import Link from 'next/link';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import { ChevronIcon } from '@/features/account/AuthFields';
import auth from '@/features/account/AuthPage.module.css';
import styles from './not-found.module.css';

// 없는 주소로 들어왔을 때의 화면 (#406). 정적 빌드에서는 out/404.html로 나온다.
// 로그인 화면(AuthPage)과 같은 유리 카드 · 와인 패널을 그대로 쓴다: 왼쪽 안내 + [대시보드로 가기], 오른쪽 패널 [처음으로].
// 상세 화면의 없는 ?id=(API not_found)는 여기로 오지 않고 화면 안 문구로 둔다 (#406 범위 밖).
export default function NotFound() {
  return (
    <main className={auth.page}>
      <Link href="/" className={`${auth.logo} ${styles.plain}`} aria-label="Keepsy 소개 화면으로">
        <img src={logoImg.src} alt="Keepsy" />
      </Link>

      <div className={auth.card}>
        <div className={auth.cardBg} aria-hidden="true" />

        <div className={`${auth.hero} ${auth.login}`}>
          <h2>길을 잃으셨나요?</h2>
          <p>{'Keepsy 소개 화면에서\n다시 시작할 수 있어요.'}</p>
          <Link href="/" className={`${auth.switch} ${styles.plain}`}>
            처음으로
            <ChevronIcon />
          </Link>
        </div>

        <section className={`${auth.form} ${auth.login}`} aria-labelledby="not-found-title">
          <p className={styles.code} aria-hidden="true">
            404
          </p>
          <h1 id="not-found-title">페이지를 찾을 수 없어요</h1>
          <p className={auth.sub}>
            주소가 바뀌었거나 없는 페이지예요.
            <br />
            입력한 주소를 다시 확인해 주세요.
          </p>
          <Link href="/dashboard" className={`${auth.submit} ${styles.plain}`}>
            대시보드로 가기
            <ChevronIcon />
          </Link>
          {/* 좁은 화면에서는 오른쪽 패널이 숨으므로 처음으로 링크를 폼 아래에 둔다 */}
          <p className={auth.formSwitch}>
            <Link href="/" className={styles.homeLink}>
              처음으로
            </Link>
          </p>
        </section>
      </div>
    </main>
  );
}
