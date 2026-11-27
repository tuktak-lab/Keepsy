import { useEffect, useState } from 'react';
import styles from './ScrollTopButton.module.css';

// 아래로 내려가면 나타나는 「맨 위로」 버튼. 모든 화면 공통 자리(ClientEffects)에 한 번만 둔다.
// 화면은 문서(window) 스크롤이라 window.scrollY만 본다. 움직임 줄이기 설정이면 부드럽게 올라가지 않고 바로 간다.

const SHOW_AFTER = 300;

export function ScrollTopButton() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const onScroll = () => setVisible(window.scrollY > SHOW_AFTER);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  function toTop() {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
  }

  return (
    <button
      type="button"
      className={`${styles.btn} ${visible ? styles.show : ''}`}
      onClick={toTop}
      aria-label="맨 위로 이동"
      title="맨 위로"
      tabIndex={visible ? 0 : -1}
      aria-hidden={!visible}
    >
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 19V5M5 12l7-7 7 7" />
      </svg>
    </button>
  );
}
