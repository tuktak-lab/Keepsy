'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import styles from './LandingPage.module.css';

// 로그인 전 첫 화면 (서비스 소개). [로그인] → /login → /shift(근무자 PIN) → /dashboard.
// 미리보기 화면의 이름 · 수치는 모두 모의 데이터다.

// icon: 사람이 아닌 행(캠페인)은 이름 첫 글자 대신 Lucide 아이콘(lucide-static 0.460.0의 path)을 그린다
type Row = { name: string; meta: string; tag?: string; icon?: string[] };

// Lucide 아이콘 path (https://lucide.dev, ISC). rect는 같은 모양의 path로 옮겼다
const ICON = {
  calendarCheck: ['M8 2v4', 'M16 2v4', 'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M3 10h18', 'm9 16 2 2 4-4'],
  gift: [
    'M4 8h16a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z',
    'M12 8v13',
    'M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7',
    'M7.5 8a2.5 2.5 0 0 1 0-5A4.8 8 0 0 1 12 8a4.8 8 0 0 1 4.5-5 2.5 2.5 0 0 1 0 5',
  ],
  sparkles: [
    'M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z',
    'M20 3v4',
    'M22 5h-4',
    'M4 17v2',
    'M5 18H3',
  ],
  bell: ['M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9', 'M10.3 21a1.94 1.94 0 0 0 3.4 0'],
  house: [
    'M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8',
    'M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  ],
  calendar: ['M8 2v4', 'M16 2v4', 'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M3 10h18'],
  user: ['M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2', 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z'],
  message: ['M14 9a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2z', 'M18 9h2a2 2 0 0 1 2 2v11l-4-4h-6a2 2 0 0 1-2-2v-1'],
  chart: ['M12 20V10', 'M18 20V4', 'M6 20v-4'],
  settings: [
    'M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z',
    'M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z',
  ],
};
// 미리보기 화면 왼쪽 메뉴 (장식). Feature.nav가 이 중 강조할 칸이다
const NAV_ICONS = [ICON.house, ICON.calendar, ICON.user, ICON.message, ICON.chart, ICON.settings];
type Feature = {
  id: string;
  label: string;
  title: string;
  desc: string;
  steps: string[];
  screen: string;
  nav: number; // NAV_ICONS 중 강조할 칸
  rows: Row[];
};

const FEATURES: Feature[] = [
  {
    id: 'customers',
    label: '고객 관리',
    title: '지난 시술과 메모를 한 화면에서',
    desc: '고객을 찾으면 방문 이력, 시술 기록, 메모, 수신 동의가 한 번에 보여요.',
    steps: ['이름·연락처로 고객 찾기', '방문·시술 기록 확인', '상담 메모 남기기', '수신 동의 상태 확인'],
    screen: '고객 목록',
    nav: 2,
    rows: [
      { name: '김○○', meta: '최근 방문 12일 전 · 젤 원컬러', tag: '단골' },
      { name: '이○○', meta: '최근 방문 41일 전 · 프렌치', tag: '이탈 신호' },
      { name: '박○○', meta: '최근 방문 3일 전 · 케어', tag: '신규' },
      { name: '최○○', meta: '최근 방문 27일 전 · 아트', tag: '단골' },
    ],
  },
  {
    id: 'reservations',
    label: '예약 관리',
    title: '오늘 예약을 한눈에',
    desc: '시간대별 예약과 담당 디자이너를 보고,\n예약 등록부터 방문 처리까지 이어서 해요.',
    steps: ['오늘 일정 확인', '예약 등록·변경', '방문 완료 처리', '노쇼·취소 기록'],
    screen: '오늘 예약',
    nav: 1,
    rows: [
      { name: '10:30 김○○', meta: '젤 원컬러 · 디자이너 A', tag: '확정' },
      { name: '13:00 정○○', meta: '연장 · 디자이너 B', tag: '확정' },
      { name: '15:30 한○○', meta: '케어 · 디자이너 A', tag: '대기' },
      { name: '18:00 오○○', meta: '아트 · 디자이너 B', tag: '확정' },
    ],
  },
  {
    id: 'campaigns',
    label: '캠페인',
    title: '다시 올 때가 된 고객에게 안내를',
    desc: '목적에 맞는 대상을 고르고,\n수신 동의와 야간 발송 제한을 확인한 뒤 보내요.',
    steps: ['목적과 대상 고르기', '제외 대상 확인', '미리보기 검토', '바로 또는 예약 발송'],
    screen: '캠페인 목록',
    nav: 3,
    rows: [
      { name: '재방문 안내', meta: '대상 24명 · 발송 완료', tag: '완료', icon: ICON.calendarCheck },
      { name: '생일 쿠폰', meta: '대상 8명 · 내일 10:00', tag: '예약', icon: ICON.gift },
      { name: '신규 시술 소개', meta: '대상 31명 · 작성 중', tag: '초안', icon: ICON.sparkles },
      { name: '케어 주기 알림', meta: '대상 12명 · 발송 완료', tag: '완료', icon: ICON.bell },
    ],
  },
  {
    id: 'insights',
    label: '인사이트',
    title: '놓치기 전에 알려 주는 이탈 신호',
    desc: '방문 주기가 길어진 고객을 먼저 보여 주고,\n바로 안내 캠페인으로 이어져요.',
    steps: ['이탈 신호 고객 보기', '평소 방문 주기 비교', '대상으로 담기', '캠페인 성과 확인'],
    screen: '이탈 신호',
    nav: 4,
    rows: [
      { name: '이○○', meta: '평소 21일 → 41일째 미방문', tag: '높음' },
      { name: '윤○○', meta: '평소 28일 → 45일째 미방문', tag: '높음' },
      { name: '서○○', meta: '평소 30일 → 38일째 미방문', tag: '보통' },
      { name: '강○○', meta: '평소 25일 → 31일째 미방문', tag: '보통' },
    ],
  },
];

// 아이콘은 24×24 선 아이콘 경로 (Sidebar.tsx와 같은 방식)
const PROBLEMS = [
  {
    icon: ['M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4', 'M2 6h4 M2 10h4 M2 14h4 M2 18h4', 'M21.378 5.626a1 1 0 1 0-3.004-3.004l-5.01 5.012a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506z'],
    title: '수첩에 흩어진 고객 정보',
    desc: '지난번 컬러와 디자인을 기억이나 메모장에 의존하고 있나요?',
  },
  {
    icon: ['M8 2v4 M16 2v4', 'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M3 10h18'],
    title: '여기저기 나뉜 예약',
    desc: '전화·메신저로 받은 예약을 따로 옮겨 적고 있나요?',
  },
  {
    icon: ['M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z', 'M9 8h6l-6 8h6'],
    title: '조용히 떠나는 단골',
    desc: '발길이 끊긴 고객을 한참 뒤에야 알아차리시나요?',
  },
  {
    icon: ['M14 9a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2z', 'M18 9h2a2 2 0 0 1 2 2v11l-4-4h-6a2 2 0 0 1-2-2v-1'],
    title: '손이 많이 가는 안내 문자',
    desc: '대상 고르기와 수신 동의 확인을 일일이 하고 있나요?',
  },
];

const FAQ = [
  {
    q: '어떻게 시작하나요?',
    a: '매장 가입에서 매장 계정을 만들고 로그인하면 돼요. 로그인 뒤에는 근무자 PIN을 입력해 지금 일하는 근무자를 확인해요.',
  },
  {
    q: '처음이라 사용법을 잘 모르겠어요',
    a: '화면 위쪽의 [사용 가이드]를 누르면 지금 보고 있는 화면의 주요 기능을 하나씩 짚어 가며 설명해 줘요.\n대시보드, 예약 관리, 고객 목록, 인사이트, 캠페인 관리, 성과 분석, 매장 설정 어디서든 몇 번이고 다시 볼 수 있어요.',
  },
  {
    q: '스탭들도 같이 쓸 수 있나요?',
    a: '매장 계정 하나를 함께 쓰고, 근무자마다 PIN으로 구분해요.\n고객 연락처 전체 보기처럼 민감한 기능은 PIN으로 한 번 더 확인해요.',
  },
  {
    q: '수신을 원하지 않는 고객은 어떻게 하나요?',
    a: '수신 동의가 없거나 수신을 거부한 고객은 캠페인 대상에서 자동으로 빠져요. 야간에는 광고 발송을 막아요.',
  },
  {
    q: '오래된 고객 정보는 어떻게 정리하나요?',
    a: '보관 기간이 지난 고객은 파기 대상 목록에 모여요. 확인한 뒤 한 번에 정리할 수 있어요.',
  },
];

function Logo() {
  return <img className={styles.logo} src={logoImg.src} alt="Keepsy" />;
}

function Icon({ paths }: { paths: string[] }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      {paths.map((d) => (
        <path key={d} d={d} />
      ))}
    </svg>
  );
}

function Screen({ title, rows, nav }: { title: string; rows: Row[]; nav: number }) {
  return (
    <div className={styles.screen} aria-hidden="true">
      <div className={styles.screenNav}>
        {NAV_ICONS.map((paths, i) => (
          <span key={paths[0]} className={i === nav ? styles.navOn : undefined}>
            <Icon paths={paths} />
          </span>
        ))}
      </div>
      <div className={styles.screenMain}>
        <div className={styles.screenTop}>
          <i />
          <Icon paths={ICON.bell} />
          <Icon paths={ICON.user} />
        </div>
        <div className={styles.screenBar}>
          <span>{title}</span>
          <small>전체 보기 ›</small>
        </div>
        <ul className={styles.screenRows}>
          {rows.map((r) => (
            <li key={r.name}>
              <span className={styles.avatar}>
                {r.icon ? (
                  <svg className={styles.rowIcon} viewBox="0 0 24 24" aria-hidden="true">
                    {r.icon.map((d) => (
                      <path key={d} d={d} />
                    ))}
                  </svg>
                ) : (
                  r.name.replace(/^[d:]+s/, '').charAt(0)
                )}
              </span>
              <span className={styles.rowText}>
                <b>{r.name}</b>
                <small>{r.meta}</small>
              </span>
              {r.tag && <em className={styles.tag}>{r.tag}</em>}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

// FAQ 한 줄: 버튼으로 열고 닫는다. 높이 · 투명도는 CSS로 움직이고, 항목마다 따로 열린다 (기존 details와 같은 동작)
function FaqItem({ q, a, index }: { q: string; a: string; index: number }) {
  const [open, setOpen] = useState(false);
  const bodyId = `faq-body-${index}`;
  return (
    <div className={`${styles.faqItem} ${styles.reveal}`} style={{ '--i': index + 2 } as React.CSSProperties} data-open={open}>
      <h3 className={styles.faqQ}>
        <button type="button" aria-expanded={open} aria-controls={bodyId} onClick={() => setOpen((v) => !v)}>
          {q}
        </button>
      </h3>
      <div id={bodyId} role="region" className={styles.faqBody} aria-hidden={!open}>
        <div>
          <p>{a}</p>
        </div>
      </div>
    </div>
  );
}

// FAQ 섹션: 화면에 처음 들어올 때 한 번만 제목 → 카드 순서로 나타난다 (다시 스크롤해도 반복하지 않는다)
function FaqSection() {
  const ref = useRef<HTMLElement>(null);
  const [shown, setShown] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setShown(true);
          io.disconnect();
        }
      },
      { threshold: 0.12 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return (
    <section ref={ref} className={`${styles.section} ${shown ? styles.revealOn : ''}`} aria-labelledby="faq-title">
      <p className={styles.eyebrow}>FAQ</p>
      <h2 id="faq-title" className={`${styles.sectionTitle} ${styles.reveal}`} style={{ '--i': 1 } as React.CSSProperties}>
        자주 묻는 질문
      </h2>
      <div className={styles.faq}>
        {FAQ.map((x, i) => (
          <FaqItem key={x.q} q={x.q} a={x.a} index={i} />
        ))}
      </div>
    </section>
  );
}

export default function LandingPage() {
  const [tab, setTab] = useState(FEATURES[0].id);
  const f = FEATURES.find((x) => x.id === tab) ?? FEATURES[0];

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <Link href="/" aria-label="Keepsy 처음으로">
          <Logo />
        </Link>
        <p className={styles.tagline}>
          Beauty Business, Smarter with <em>Keepsy</em>
        </p>
      </header>

      <main>
        <section className={styles.hero}>
          <div className={styles.heroText}>
            <h1 className={styles.heroTitle}>
              매장의 성장을 위한
              <br />
              가장 스마트한 선택,
              <br />
              <img className={styles.heroLogo} src={logoImg.src} alt="Keepsy" />
            </h1>
            <p className={styles.heroSub}>
              지금, Keepsy와 함께
              <br />
              더 체계적인 매장 관리를 시작해보세요.
            </p>
            <div className={styles.heroCta}>
              <Link href="/login" className={styles.primary}>
                로그인하기 <span aria-hidden="true">→</span>
              </Link>
              <Link href="/signup" className={styles.secondary}>
                매장 가입
              </Link>
            </div>
          </div>
          {/* 유리 대시보드 일러스트 (장식) */}
          <div className={styles.heroArt} aria-hidden="true" />
        </section>

        <div className={styles.flow}>
          <svg className={styles.flowBg} viewBox="0 0 1440 1300" preserveAspectRatio="xMidYMin slice" aria-hidden="true">
            <defs>
              <linearGradient id="rib-a" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stopColor="#fff" stopOpacity="0.7" />
                <stop offset="1" stopColor="#e9a9b5" stopOpacity="0.32" />
              </linearGradient>
              <linearGradient id="rib-b" x1="1" y1="0" x2="0" y2="1">
                <stop offset="0" stopColor="#fff" stopOpacity="0.7" />
                <stop offset="1" stopColor="#e9a9b5" stopOpacity="0.3" />
              </linearGradient>
            </defs>
            <path d="M-80 470 C170 410 400 560 560 880 C390 650 170 540 -80 570 Z" fill="url(#rib-a)" />
            <path d="M-80 470 C170 410 400 560 560 880" fill="none" stroke="#fff" strokeOpacity="0.8" strokeWidth="1.5" />
            <path d="M1520 650 C1270 630 1070 750 940 1010 C1100 810 1290 710 1520 740 Z" fill="url(#rib-b)" />
            <path d="M1520 650 C1270 630 1070 750 940 1010" fill="none" stroke="#fff" strokeOpacity="0.8" strokeWidth="1.5" />
          </svg>
        <section className={styles.section} aria-labelledby="features-title">
          <p className={styles.eyebrow}>FEATURES</p>
          <h2 id="features-title" className={styles.sectionTitle}>
            샵 운영에 필요한 일을 하나로
          </h2>
          <p className={styles.sectionSub}>고객 기록부터 예약, 이탈 신호, 재방문 안내까지 Keepsy가 이어서 도와드려요.</p>
          <div className={styles.tabs} role="tablist" aria-label="기능">
            {FEATURES.map((x) => (
              <button
                key={x.id}
                type="button"
                role="tab"
                id={`tab-${x.id}`}
                aria-selected={x.id === tab}
                aria-controls="feature-panel"
                className={x.id === tab ? styles.tabOn : styles.tab}
                onClick={() => setTab(x.id)}
              >
                {x.label}
              </button>
            ))}
          </div>
          <div className={styles.feature} role="tabpanel" id="feature-panel" aria-labelledby={`tab-${f.id}`}>
            <div className={styles.featureText}>
              <h3>{f.title}</h3>
              <p>{f.desc}</p>
              <ol className={styles.steps}>
                {f.steps.map((s) => (
                  <li key={s}>
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <circle cx="12" cy="12" r="10" />
                      <path d="m8 12.5 3 3 5-6" />
                    </svg>
                    {s}
                  </li>
                ))}
              </ol>
            </div>
            <Screen title={f.screen} rows={f.rows} nav={f.nav} />
          </div>
        </section>

        <section className={styles.section} aria-labelledby="problem-title">
          <p className={styles.eyebrow}>PROBLEM</p>
          <h2 id="problem-title" className={styles.sectionTitle}>
            아직도 고객 관리를
            <br />
            기억과 메모에 맡기고 계신가요?
          </h2>
          <ul className={styles.cards}>
            {PROBLEMS.map((p) => (
              <li key={p.title} className={styles.card}>
                <svg className={styles.cardIcon} viewBox="0 0 24 24" aria-hidden="true">
                  {p.icon.map((d) => (
                    <path key={d} d={d} />
                  ))}
                </svg>
                <h3>{p.title}</h3>
                <p>{p.desc}</p>
              </li>
            ))}
          </ul>
          <p className={styles.answer}>
            원장님 시간을 소모하는 일,{' '}
            <strong>
              <img className={styles.heroLogo} src={logoImg.src} alt="Keepsy" />가 할게요.
            </strong>
          </p>
        </section>
        </div>

        <FaqSection />
      </main>

      <footer className={styles.footer}>
        <Logo />
      </footer>
    </div>
  );
}
