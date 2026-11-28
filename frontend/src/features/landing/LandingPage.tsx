'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import logoImg from '@/assets/images/keepsy-logo-thin.png';
import styles from './LandingPage.module.css';
import { LocaleSelect } from '@/components/common/LocaleSelect';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';

// 로그인 전 첫 화면 (서비스 소개). [로그인] → /login → /shift(근무자 PIN) → /dashboard.
// 미리보기 화면의 이름 · 수치는 모두 모의 데이터다.
// 문구는 사전(lib/messages의 landing.*)에 있고, 언어가 정해진 뒤 그릴 때 읽는다 (FR-I18N-02).

// icon: 사람이 아닌 행(캠페인)은 이름 첫 글자 대신 Lucide 아이콘(lucide-static 0.460.0의 path)을 그린다
// 사람 행은 모의 이름(name, 번역하지 않음), 캠페인 행은 제목 키(title)
type Row = { name?: string; title?: MessageKey; meta: MessageKey; tag?: MessageKey; icon?: string[] };

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
  label: MessageKey;
  title: MessageKey;
  desc: MessageKey;
  steps: MessageKey[];
  screen: MessageKey;
  nav: number; // NAV_ICONS 중 강조할 칸
  rows: Row[];
};

const FEATURES: Feature[] = [
  {
    id: 'customers',
    label: 'landing.customers.label',
    title: 'landing.customers.title',
    desc: 'landing.customers.desc',
    steps: ['landing.customers.step1', 'landing.customers.step2', 'landing.customers.step3', 'landing.customers.step4'],
    screen: 'landing.customers.screen',
    nav: 2,
    rows: [
      { name: '김○○', meta: 'landing.customers.row1', tag: 'landing.tag.regular' },
      { name: '이○○', meta: 'landing.customers.row2', tag: 'landing.tag.churn' },
      { name: '박○○', meta: 'landing.customers.row3', tag: 'landing.tag.new' },
      { name: '최○○', meta: 'landing.customers.row4', tag: 'landing.tag.regular' },
    ],
  },
  {
    id: 'reservations',
    label: 'landing.reservations.label',
    title: 'landing.reservations.title',
    desc: 'landing.reservations.desc',
    steps: ['landing.reservations.step1', 'landing.reservations.step2', 'landing.reservations.step3', 'landing.reservations.step4'],
    screen: 'landing.reservations.screen',
    nav: 1,
    rows: [
      { name: '10:30 김○○', meta: 'landing.reservations.row1', tag: 'landing.tag.confirmed' },
      { name: '13:00 정○○', meta: 'landing.reservations.row2', tag: 'landing.tag.confirmed' },
      { name: '15:30 한○○', meta: 'landing.reservations.row3', tag: 'landing.tag.waiting' },
      { name: '18:00 오○○', meta: 'landing.reservations.row4', tag: 'landing.tag.confirmed' },
    ],
  },
  {
    id: 'campaigns',
    label: 'landing.campaigns.label',
    title: 'landing.campaigns.title',
    desc: 'landing.campaigns.desc',
    steps: ['landing.campaigns.step1', 'landing.campaigns.step2', 'landing.campaigns.step3', 'landing.campaigns.step4'],
    screen: 'landing.campaigns.screen',
    nav: 3,
    rows: [
      { title: 'landing.campaigns.row1', meta: 'landing.campaigns.row1Meta', tag: 'landing.tag.done', icon: ICON.calendarCheck },
      { title: 'landing.campaigns.row2', meta: 'landing.campaigns.row2Meta', tag: 'landing.tag.scheduled', icon: ICON.gift },
      { title: 'landing.campaigns.row3', meta: 'landing.campaigns.row3Meta', tag: 'landing.tag.draft', icon: ICON.sparkles },
      { title: 'landing.campaigns.row4', meta: 'landing.campaigns.row4Meta', tag: 'landing.tag.done', icon: ICON.bell },
    ],
  },
  {
    id: 'insights',
    label: 'landing.insights.label',
    title: 'landing.insights.title',
    desc: 'landing.insights.desc',
    steps: ['landing.insights.step1', 'landing.insights.step2', 'landing.insights.step3', 'landing.insights.step4'],
    screen: 'landing.insights.screen',
    nav: 4,
    rows: [
      { name: '이○○', meta: 'landing.insights.row1', tag: 'landing.tag.high' },
      { name: '윤○○', meta: 'landing.insights.row2', tag: 'landing.tag.high' },
      { name: '서○○', meta: 'landing.insights.row3', tag: 'landing.tag.medium' },
      { name: '강○○', meta: 'landing.insights.row4', tag: 'landing.tag.medium' },
    ],
  },
];

// 아이콘은 24×24 선 아이콘 경로 (Sidebar.tsx와 같은 방식)
const PROBLEMS: { icon: string[]; title: MessageKey; desc: MessageKey }[] = [
  {
    icon: ['M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4', 'M2 6h4 M2 10h4 M2 14h4 M2 18h4', 'M21.378 5.626a1 1 0 1 0-3.004-3.004l-5.01 5.012a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506z'],
    title: 'landing.problem.notes.title',
    desc: 'landing.problem.notes.desc',
  },
  {
    icon: ['M8 2v4 M16 2v4', 'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M3 10h18'],
    title: 'landing.problem.booking.title',
    desc: 'landing.problem.booking.desc',
  },
  {
    icon: ['M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z', 'M9 8h6l-6 8h6'],
    title: 'landing.problem.leaving.title',
    desc: 'landing.problem.leaving.desc',
  },
  {
    icon: ['M14 9a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2z', 'M18 9h2a2 2 0 0 1 2 2v11l-4-4h-6a2 2 0 0 1-2-2v-1'],
    title: 'landing.problem.texts.title',
    desc: 'landing.problem.texts.desc',
  },
];

const FAQ: { q: MessageKey; a: MessageKey }[] = [
  {
    q: 'landing.faq.start.q',
    a: 'landing.faq.start.a',
  },
  {
    q: 'landing.faq.guide.q',
    a: 'landing.faq.guide.a',
  },
  {
    q: 'landing.faq.staff.q',
    a: 'landing.faq.staff.a',
  },
  {
    q: 'landing.faq.optout.q',
    a: 'landing.faq.optout.a',
  },
  {
    q: 'landing.faq.old.q',
    a: 'landing.faq.old.a',
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

function Screen({ title, rows, nav }: { title: MessageKey; rows: Row[]; nav: number }) {
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
          <span>{t(title)}</span>
          <small>{t('landing.seeAll')}</small>
        </div>
        <ul className={styles.screenRows}>
          {rows.map((r) => {
            const name = r.title ? t(r.title) : (r.name ?? '');
            return (
              <li key={name}>
                <span className={styles.avatar}>
                  {r.icon ? (
                    <svg className={styles.rowIcon} viewBox="0 0 24 24" aria-hidden="true">
                      {r.icon.map((d) => (
                        <path key={d} d={d} />
                      ))}
                    </svg>
                  ) : (
                    name.replace(/^[\d:]+\s/, '').charAt(0)
                  )}
                </span>
                <span className={styles.rowText}>
                  <b>{name}</b>
                  <small>{t(r.meta)}</small>
                </span>
                {r.tag && <em className={styles.tag}>{t(r.tag)}</em>}
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}

// FAQ 한 줄: 버튼으로 열고 닫는다. 높이 · 투명도는 CSS로 움직인다. 어느 항목을 열지는 FaqSection이 정한다
function FaqItem({ q, a, index, open, onToggle }: { q: string; a: string; index: number; open: boolean; onToggle: () => void }) {
  const bodyId = `faq-body-${index}`;
  return (
    <div className={`${styles.faqItem} ${styles.reveal}`} style={{ '--i': index + 2 } as React.CSSProperties} data-open={open}>
      <h3 className={styles.faqQ}>
        <button type="button" aria-expanded={open} aria-controls={bodyId} onClick={onToggle}>
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

/** --i × 90ms 간격으로 나타난다 (CSS .reveal · .rise) */
const order = (i: number) => ({ '--i': i }) as React.CSSProperties;

// 화면에 처음 들어올 때 한 번만 안의 요소가 순서대로 나타나는 섹션 (다시 스크롤해도 반복하지 않는다)
function RevealSection({ labelledBy, children }: { labelledBy: string; children: React.ReactNode }) {
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
      // 아래 15%는 빼고 본다: 윗여백만 걸친 상태(첫 화면 끝)에서 미리 나타나 스크롤할 때는 이미 끝나 있지 않게
      { threshold: 0.12, rootMargin: '0px 0px -15% 0px' },
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return (
    <section ref={ref} className={`${styles.section} ${shown ? styles.revealOn : ''}`} aria-labelledby={labelledBy}>
      {children}
    </section>
  );
}

function FaqSection() {
  const [openIndex, setOpenIndex] = useState<number | null>(null); // 한 번에 하나만 연다
  return (
    <RevealSection labelledBy="faq-title">
      <p className={styles.eyebrow}>FAQ</p>
      <h2 id="faq-title" className={`${styles.sectionTitle} ${styles.reveal}`} style={order(1)}>
        {t('landing.faq.title')}
      </h2>
      <div className={styles.faq}>
        {FAQ.map((x, i) => (
          <FaqItem key={x.q} q={t(x.q)} a={t(x.a)} index={i} open={openIndex === i} onToggle={() => setOpenIndex((v) => (v === i ? null : i))} />
        ))}
      </div>
    </RevealSection>
  );
}

export default function LandingPage() {
  const [tab, setTab] = useState(FEATURES[0].id);
  const f = FEATURES.find((x) => x.id === tab) ?? FEATURES[0];

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <Link href="/" aria-label={t('landing.home')}>
          <Logo />
        </Link>
        <div className={styles.headerRight}>
          <p className={styles.tagline}>
            Beauty Business, Smarter with <em>Keepsy</em>
          </p>
          <LocaleSelect />
        </div>
      </header>

      <main>
        <section className={styles.hero}>
          <div className={styles.heroText}>
            <h1 className={styles.heroTitle}>
              {t('landing.hero.line1')}
              <br />
              {t('landing.hero.line2')}
              <br />
              <img className={styles.heroLogo} src={logoImg.src} alt="Keepsy" />
            </h1>
            <p className={styles.heroSub}>
              {t('landing.hero.sub1')}
              <br />
              {t('landing.hero.sub2')}
            </p>
            <div className={styles.heroCta}>
              <Link href="/login" className={styles.primary}>
                {t('landing.login')} <span aria-hidden="true">→</span>
              </Link>
              <Link href="/signup" className={styles.secondary}>
                {t('account.hero.signup')}
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
        <RevealSection labelledBy="features-title">
          <p className={styles.eyebrow}>FEATURES</p>
          <h2 id="features-title" className={`${styles.sectionTitle} ${styles.rise}`} style={order(1)}>
            {t('landing.features.title')}
          </h2>
          <p className={`${styles.sectionSub} ${styles.rise}`} style={order(2)}>{t('landing.features.sub')}</p>
          <div className={`${styles.tabs} ${styles.rise}`} style={order(3)} role="tablist" aria-label={t('landing.features.tabs')} data-slide-tabs>
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
                {t(x.label)}
              </button>
            ))}
          </div>
          {/* 스크롤 등장은 바깥 칸이 맡는다: 탭을 바꿀 때 아래 칸만 새로 만들어도 다시 기다리지 않는다 */}
          <div className={styles.rise} style={order(4)}>
            {/* key: 탭을 바꾸면 새로 만들어 등장 효과(CSS)가 다시 돈다 */}
            <div key={f.id} className={styles.feature} role="tabpanel" id="feature-panel" aria-labelledby={`tab-${f.id}`}>
              <div className={styles.featureText}>
                <h3>{t(f.title)}</h3>
                <p>{t(f.desc)}</p>
                <ol className={styles.steps}>
                  {f.steps.map((s) => (
                    <li key={s}>
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <circle cx="12" cy="12" r="10" />
                        <path d="m8 12.5 3 3 5-6" />
                      </svg>
                      {t(s)}
                    </li>
                  ))}
                </ol>
              </div>
              <Screen title={f.screen} rows={f.rows} nav={f.nav} />
            </div>
          </div>
        </RevealSection>

        <RevealSection labelledBy="problem-title">
          <p className={styles.eyebrow}>PROBLEM</p>
          <h2 id="problem-title" className={`${styles.sectionTitle} ${styles.rise}`} style={order(1)}>
            {t('landing.problem.title1')}
            <br />
            {t('landing.problem.title2')}
          </h2>
          <ul className={styles.cards}>
            {PROBLEMS.map((p, i) => (
              <li key={p.title} className={`${styles.card} ${styles.rise}`} style={order(i + 2)}>
                <svg className={styles.cardIcon} viewBox="0 0 24 24" aria-hidden="true">
                  {p.icon.map((d) => (
                    <path key={d} d={d} />
                  ))}
                </svg>
                <h3>{t(p.title)}</h3>
                <p>{t(p.desc)}</p>
              </li>
            ))}
          </ul>
          <p className={`${styles.answer} ${styles.rise}`} style={order(PROBLEMS.length + 2)}>
            {t('landing.answer.pre')}{' '}
            <strong>
              <img className={styles.heroLogo} src={logoImg.src} alt="Keepsy" />
              {t('landing.answer.post')}
            </strong>
          </p>
        </RevealSection>
        </div>

        <FaqSection />
      </main>

      <footer className={styles.footer}>
        <Logo />
      </footer>
    </div>
  );
}
