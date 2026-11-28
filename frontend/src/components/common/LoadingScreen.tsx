'use client';

import { useEffect, useRef, useState } from 'react';
import { usePathname } from 'next/navigation';
import { loadingCounts } from '@/lib/loading';
import { currentPin } from '@/lib/pinQueue';
import { t } from '@/lib/i18n';
import styles from './LoadingScreen.module.css';

// 화면을 여는 데 필요한 조회가 느릴 때만 덮는 로딩 화면. 모든 화면에 하나 둔다 (app/layout.tsx).
// - 처음 접속: 글꼴 · 문서 리소스 · 조회, 화면 이동: 그 화면을 열며 시작한 조회를 기다린다
// - 0.7초 안에 끝나면 보이지 않는다 (CSS 지연이라 화면 파일을 받기 전에도 적용된다)
// - 화면을 연 뒤의 검색 · 「더 보기」 · 저장은 덮지 않는다. PIN 창이 뜨면 바로 걷는다
// 진행률은 기다리는 일 중 끝난 비율이다.
// shortcut: 메뉴를 누른 뒤 다음 화면 파일을 받는 동안은 덮지 않는다. 필요하면 라우터 이동 시작을 감지한다

const QUIET_MS = 150; // 조회가 이어질 수 있어(세션 → 화면 데이터) 잠깐 조용해야 끝난 것으로 본다
const MIN_VISIBLE_MS = 500; // 보이자마자 사라져 깜빡이지 않게
const FILL_MS = 400; // CSS .fill 전환과 같다
const FADE_MS = 500; // CSS leave 애니메이션과 같다
const MAX_WAIT_MS = 20_000; // CSS giveup 지연과 같다. 화면에 갇히지 않게
const TICK_MS = 50;

let firstScreen = true;

function pageLoaded(): Promise<void> {
  if (document.readyState === 'complete') return Promise.resolve();
  return new Promise((resolve) => window.addEventListener('load', () => resolve(), { once: true }));
}

export function LoadingScreen() {
  // 화면(경로)이 바뀔 때마다 새로 만든다: 보이기까지의 지연이 처음부터 다시 흐른다
  const pathname = usePathname();
  return <Cover key={pathname} />;
}

function Cover() {
  const [percent, setPercent] = useState(0);
  const [phase, setPhase] = useState<'loading' | 'leaving' | 'gone'>('loading');
  const screen = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const first = firstScreen;
    const began = first ? 0 : performance.now(); // 첫 화면은 문서를 열기 시작한 때부터 잰다
    const base = loadingCounts().settled;
    let pageReady = !first;
    if (first) void Promise.all([document.fonts.ready, pageLoaded()]).finally(() => (pageReady = true));

    let quietSince: number | null = null;
    let shownAt: number | null = null;
    const timers: ReturnType<typeof setTimeout>[] = [];

    const tick = setInterval(() => {
      const now = performance.now();
      if (shownAt === null && screen.current && getComputedStyle(screen.current).visibility === 'visible') shownAt = now;

      const { started, settled } = loadingCounts();
      const total = started - base + (first ? 1 : 0);
      const done = settled - base + (first && pageReady ? 1 : 0);
      // 조회가 늘어도 막대가 뒤로 가지 않게 한다
      if (total > 0) setPercent((p) => Math.max(p, Math.min(99, Math.round((done / total) * 100))));

      const busy = !pageReady || started > settled;
      quietSince = busy ? null : (quietSince ?? now);
      const complete = quietSince !== null && now - quietSince >= QUIET_MS;
      const pin = currentPin() !== null;
      if (!complete && !pin && now - began < MAX_WAIT_MS) return;

      clearInterval(tick);
      firstScreen = false;
      if (shownAt === null || pin) return setPhase('gone');
      if (complete) setPercent(100);
      timers.push(
        setTimeout(
          () => {
            setPhase('leaving');
            timers.push(setTimeout(() => setPhase('gone'), FADE_MS));
          },
          complete ? Math.max(FILL_MS, MIN_VISIBLE_MS - (now - shownAt)) : 0,
        ),
      );
    }, TICK_MS);

    return () => {
      clearInterval(tick);
      timers.forEach(clearTimeout);
    };
  }, []);

  if (phase === 'gone') return null;

  return (
    <div ref={screen} className={phase === 'leaving' ? `${styles.screen} ${styles.leaving}` : styles.screen}>
      <Ribbon id="kp-ribbon-top" className={styles.top} />
      <Ribbon id="kp-ribbon-bottom" className={styles.bottom} />
      <div className={styles.center}>
        <p className={styles.title} aria-hidden="true">
          LOADING...
        </p>
        <p className={styles.sub}>{t('common.pleaseWait')}</p>
        <div className={styles.track} role="progressbar" aria-label={t('common.loading')} aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}>
          <div className={styles.fill} style={{ width: `${percent}%` }} />
          <span className={styles.spark} aria-hidden="true" />
        </div>
      </div>
    </div>
  );
}

/** 반투명 유리 리본 장식. 같은 모양을 위 · 아래에 쓰고 아래는 CSS로 뒤집는다 */
function Ribbon(props: { id: string; className: string }) {
  return (
    <div className={`${styles.ribbon} ${props.className}`} aria-hidden="true">
      <svg viewBox="0 0 1440 240" preserveAspectRatio="xMidYMid slice" focusable="false">
        <defs>
          <linearGradient id={props.id} x1="0" y1="0" x2="1" y2="0.35">
            <stop offset="0" stopColor="#e8aab6" stopOpacity="0.1" />
            <stop offset="0.3" stopColor="#d98d9d" stopOpacity="0.34" />
            <stop offset="0.55" stopColor="#ffffff" stopOpacity="0.55" />
            <stop offset="0.8" stopColor="#e3a0ae" stopOpacity="0.3" />
            <stop offset="1" stopColor="#f3cdd3" stopOpacity="0.12" />
          </linearGradient>
        </defs>
        <path
          d="M-80 96C180-4 420 40 640 98S1090 168 1520 34V58C1110 200 860 150 640 120S210 50-80 124Z"
          fill={`url(#${props.id})`}
          stroke="#ffffff"
          strokeOpacity="0.85"
          strokeWidth="1.2"
        />
        <path
          d="M-80 150C260 196 430 70 760 72S1230 150 1520 108V118C1220 172 1030 90 760 88S300 214-80 168Z"
          fill={`url(#${props.id})`}
          stroke="#d99aa6"
          strokeOpacity="0.35"
          strokeWidth="0.8"
        />
      </svg>
    </div>
  );
}
