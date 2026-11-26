import Link from 'next/link';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import styles from './not-found.module.css';

// 없는 주소로 들어왔을 때의 화면 (#406). 정적 빌드에서는 out/404.html로 나온다.
// 상세 화면의 없는 ?id=(API not_found)는 여기로 오지 않고 화면 안 문구로 둔다 (#406 범위 밖).
export default function NotFound() {
  return (
    <main className={styles.page}>
      <section className={styles.card} aria-labelledby="not-found-title">
        <img className={styles.logo} src={logoImg.src} alt="Keepsy" />
        <p className={styles.code} aria-hidden="true">
          404
        </p>
        <h1 id="not-found-title" className={styles.title}>
          페이지를 찾을 수 없어요
        </h1>
        <p className={styles.sub}>
          주소가 바뀌었거나 없는 페이지예요.
          <br />
          입력한 주소를 다시 확인해 주세요.
        </p>
        <div className={styles.actions}>
          <Link href="/dashboard" className={styles.primary}>
            대시보드로 가기
          </Link>
          <Link href="/" className={styles.secondary}>
            처음으로
          </Link>
        </div>
      </section>
    </main>
  );
}
