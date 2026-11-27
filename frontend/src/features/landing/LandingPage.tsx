'use client';

import { useState, type ReactNode } from 'react';
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
};
type Feature = {
  id: string;
  label: string;
  title: string;
  desc: string;
  steps: string[];
  screen: string;
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
    rows: [
      { name: '이○○', meta: '평소 21일 → 41일째 미방문', tag: '높음' },
      { name: '윤○○', meta: '평소 28일 → 45일째 미방문', tag: '높음' },
      { name: '서○○', meta: '평소 30일 → 38일째 미방문', tag: '보통' },
      { name: '강○○', meta: '평소 25일 → 31일째 미방문', tag: '보통' },
    ],
  },
];

// 히어로 왼쪽 미리보기: 기능 탭(고객 목록)과 겹치지 않게, 대시보드 상단 요약(KPI 카드)과 같은 항목을 카드로 보여 준다
const TODAY = [
  { head: '전체 고객', value: '128', note: '삭제된 고객 제외' },
  { head: '오늘 예약', value: '6', note: '확정 5 · 상담 후 진행 1' },
  { head: '마감 안 된 예약', value: '2', note: '시작 시각 지남 · 마감 필요' },
  { head: '주기 도래 고객', value: '4', note: '예약 없음 · 우선순위 순' },
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
    q: '스탭들도 같이 쓸 수 있나요?',
    a: '매장 계정 하나를 함께 쓰고, 근무자마다 PIN으로 구분해요.\n고객 연락처 전체 보기처럼 민감한 기능은 PIN을 한 번 더 확인해요.',
  },
  {
    q: '고객에게 실제로 문자가 가나요?',
    a: '아니요. Keepsy는 시연용이라 가상 발송기로만 보내요. 보낸 내용은 가상 휴대폰 화면에서 확인할 수 있어요.',
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

function Screen({ title, rows, children }: { title: string; rows?: Row[]; children?: ReactNode }) {
  return (
    <div className={styles.screen} aria-hidden="true">
      <div className={styles.screenBar}>
        <i />
        <i />
        <i />
        <span>{title}</span>
      </div>
      {children ?? (
        <ul className={styles.screenRows}>
          {rows?.map((r) => (
            <li key={r.name}>
              <span className={styles.avatar}>
                {r.icon ? (
                  <svg className={styles.rowIcon} viewBox="0 0 24 24" aria-hidden="true">
                    {r.icon.map((d) => (
                      <path key={d} d={d} />
                    ))}
                  </svg>
                ) : (
                  r.name.replace(/^[\d:]+\s/, '').charAt(0)
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
      )}
    </div>
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
        <nav className={styles.headerNav}>
          <Link href="/signup" className={styles.ghost}>
            매장 가입
          </Link>
          <Link href="/login" className={styles.primarySm}>
            로그인
          </Link>
        </nav>
      </header>

      <main>
        <section className={styles.hero}>
          <p className={styles.badge}>뷰티샵 원장님을 위한 고객 관리</p>
          <h1 className={styles.heroTitle}>
            다시 오고 싶은 샵,
            <br />
            <img className={styles.heroLogo} src={logoImg.src} alt="Keepsy" />로 시작하세요
          </h1>
          <p className={styles.heroSub}>
            고객 기록부터 예약, 이탈 신호, 재방문 안내까지.
            <br />
            흩어진 샵 운영을 한 곳에서 이어 줍니다.
          </p>
          <div className={styles.heroCta}>
            <Link href="/login" className={styles.primary}>
              로그인하고 시작하기
            </Link>
            <Link href="/signup" className={styles.secondary}>
              매장 가입
            </Link>
          </div>
          <div className={styles.heroPreview}>
            <Screen title="오늘 한눈에">
              <div className={styles.kpis}>
                {TODAY.map((k) => (
                  <div key={k.head} className={styles.kpi}>
                    <span>{k.head}</span>
                    <b>{k.value}</b>
                    <small>{k.note}</small>
                  </div>
                ))}
              </div>
            </Screen>
            <Screen title={FEATURES[3].screen} rows={FEATURES[3].rows} />
          </div>
        </section>

        <section className={styles.section} aria-labelledby="features-title">
          <p className={styles.eyebrow}>FEATURES</p>
          <h2 id="features-title" className={styles.sectionTitle}>
            샵 운영에 필요한 일을 하나로
          </h2>
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
                {f.steps.map((s, i) => (
                  <li key={s}>
                    <span>{String(i + 1).padStart(2, '0')}</span>
                    {s}
                  </li>
                ))}
              </ol>
            </div>
            <Screen title={f.screen} rows={f.rows} />
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
            원장님 시간을 잡아먹는 일,{' '}
            <strong>
              <img className={styles.heroLogo} src={logoImg.src} alt="Keepsy" />가 정리할게요.
            </strong>
          </p>
        </section>

        <section className={styles.section} aria-labelledby="faq-title">
          <p className={styles.eyebrow}>FAQ</p>
          <h2 id="faq-title" className={styles.sectionTitle}>
            자주 묻는 질문
          </h2>
          <div className={styles.faq}>
            {FAQ.map((x) => (
              <details key={x.q} className={styles.faqItem}>
                <summary>{x.q}</summary>
                <p>{x.a}</p>
              </details>
            ))}
          </div>
        </section>
      </main>

      <footer className={styles.footer}>
        <Logo />
        <span>시연용 서비스 · 화면의 고객 정보는 모두 모의 데이터예요</span>
      </footer>
    </div>
  );
}
