'use client';

import { useEffect, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { isSearchShortcut, KEYWORDS_URL, matchFeature, type FeatureEntry } from '@/lib/featureSearch';
import { changeLocale, isLocale, LOCALE_NAMES, LOCALES, locale, t } from '@/lib/i18n';
import { lockPin, logout, useSession, useWorkerRequired } from '@/lib/session';

// 모든 화면 공통 상단 (docs ARCHITECTURE 4장 「모든 화면에 공통」)
// - 기능 검색 (FR-SCH-01 · 02): 키워드 사전 public/search-keywords.json, `/` · Ctrl+K로 포커스
// - 현재 근무자 크게 표시 · [근무자 전환] · [로그아웃] (FR-ACC-10)
// - PIN 인증 중이면 인증 스탭 이름과 [잠금] (FR-ACC-08)

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
}

export function Topbar() {
  const pathname = usePathname();
  const router = useRouter();
  const session = useSession();
  useWorkerRequired(session);
  const [q, setQ] = useState('');
  const [miss, setMiss] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [seenPath, setSeenPath] = useState(pathname);

  // 상단바는 화면을 옮겨도 유지되므로, 경로가 바뀌면 검색어 · 검색 실패 표시 · 열린 메뉴를 처음으로 돌린다
  if (seenPath !== pathname) {
    setSeenPath(pathname);
    setQ('');
    setMiss(false);
    setMenuOpen(false);
  }
  const [keywords, setKeywords] = useState<FeatureEntry[]>([]);
  const searchInput = useRef<HTMLInputElement>(null);

  // 스크롤되면 헤더에 배경·경계선을 깔아 아래 콘텐츠와 구분
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 8);
    on();
    window.addEventListener('scroll', on, { passive: true });
    return () => window.removeEventListener('scroll', on);
  }, []);

  useEffect(() => {
    fetch(KEYWORDS_URL)
      .then((res) => (res.ok ? res.json() : []))
      .then((list: FeatureEntry[]) => setKeywords(list))
      .catch(() => setKeywords([]));
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!isSearchShortcut(e, isTyping(e.target))) return;
      e.preventDefault();
      searchInput.current?.focus();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  function onSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!q.trim()) return;
    const path = matchFeature(keywords, q);
    if (path) {
      setMiss(false);
      setQ('');
      router.push(path);
    } else {
      setMiss(true);
    }
  }

  const menuItem: React.CSSProperties = {
    display: 'block',
    width: '100%',
    padding: '10px 16px',
    border: 0,
    background: 'transparent',
    textAlign: 'left',
    font: "400 13px 'Noto Sans KR', sans-serif",
    color: '#3E3532',
    cursor: 'pointer',
  };

  return (
    <header
      className={'kp-top' + (scrolled ? ' scrolled' : '')}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '20px',
        padding: '0 32px 0 50px',
        height: '92px',
        boxSizing: 'border-box',
        fontFamily: "'Noto Sans KR', system-ui, sans-serif",
        fontSize: '14px',
        color: '#3E3532',
      }}
    >
      <form
        onSubmit={onSearch}
        role="search"
        style={{
          // 경로 표시를 지운 자리(왼쪽 끝)에 검색을 두고, 나머지는 오른쪽으로 민다
          marginRight: 'auto',
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          height: '42px',
          width: 'min(300px, 28vw)',
          padding: '0 16px',
          boxSizing: 'border-box',
          borderRadius: '999px',
          background: 'rgba(255,255,255,0.6)',
          border: `1px solid ${miss ? '#C98B8B' : 'rgba(255,255,255,0.9)'}`,
          boxShadow: 'inset 0 1px 0 #fff, 0 0 0 1px rgba(150,100,108,0.12), 0 6px 18px -8px rgba(150,90,100,0.25)',
          WebkitBackdropFilter: 'blur(14px) saturate(1.15)',
          backdropFilter: 'blur(14px) saturate(1.15)',
        }}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#6B605B" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
          <circle cx="11" cy="11" r="7"></circle>
          <path d="M20 20l-4-4"></path>
        </svg>
        <input
          ref={searchInput}
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setMiss(false);
          }}
          placeholder={miss ? t('shell.topbar.searchMiss') : t('shell.topbar.searchPlaceholder')}
          aria-keyshortcuts="/ Control+K"
          aria-label={t('shell.topbar.searchLabel')}
          style={{ flex: 1, minWidth: 0, border: 0, outline: 0, background: 'transparent', font: "400 13px 'Noto Sans KR', sans-serif", color: '#3E3532' }}
        />
      </form>
      {/* 화면 언어 (FR-I18N-02). 브라우저별 저장, 바꾸면 새로고침 */}
      <select
        aria-label={t('locale.label')}
        value={locale()}
        onChange={(e) => {
          if (isLocale(e.target.value)) changeLocale(e.target.value);
        }}
        style={{
          height: '32px',
          padding: '0 8px',
          borderRadius: '999px',
          border: '1px solid rgba(255,255,255,0.9)',
          background: 'rgba(255,255,255,0.55)',
          boxShadow: '0 0 0 1px rgba(150,100,108,0.12)',
          font: "400 12.5px 'Noto Sans KR', sans-serif",
          color: '#4A3F3B',
          cursor: 'pointer',
        }}
      >
        {LOCALES.map((l) => (
          <option key={l} value={l}>
            {LOCALE_NAMES[l]}
          </option>
        ))}
      </select>
      {session?.pinStaff && (
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '8px',
            height: '32px',
            padding: '0 6px 0 12px',
            borderRadius: '999px',
            background: 'rgba(255,255,255,0.55)',
            border: '1px solid rgba(255,255,255,0.9)',
            boxShadow: '0 0 0 1px rgba(150,100,108,0.12)',
            font: "500 12.5px 'Noto Sans KR', sans-serif",
            color: '#7A4450',
            whiteSpace: 'nowrap',
          }}
        >
          {/* 사람 + 열린 자물쇠 아이콘. PIN 입력 창의 user-lock에서 고리만 연 모양 (Lucide user-lock · lock-open, ISC) */}
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#7A4450" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="10" cy="7" r="4" />
            <path d="M10.3 15H7a4 4 0 0 0-4 4v2" />
            <path d="M15 16V13a2 2 0 0 1 3.9-.6" />
            <rect width="8" height="5" x="13" y="16" rx=".9" />
          </svg>
          {t('shell.topbar.pinActive', { name: session.pinStaff.name })}
          <button
            type="button"
            onClick={() => void lockPin()}
            style={{
              height: '24px',
              padding: '0 10px',
              border: 0,
              borderRadius: '999px',
              background: '#7A4450',
              color: '#fff',
              font: "500 12px 'Noto Sans KR', sans-serif",
              cursor: 'pointer',
            }}
          >
            {t('shell.topbar.lock')}
          </button>
        </span>
      )}
      <span style={{ position: 'relative' }}>
        <button
          type="button"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((o) => !o)}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '10px',
            border: 0,
            background: 'transparent',
            font: "400 13px 'Noto Sans KR', sans-serif",
            color: '#4A3F3B',
            cursor: 'pointer',
          }}
        >
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', color: '#221D1B', fontWeight: 600, fontSize: '16px', whiteSpace: 'nowrap' }}>
            <span style={{ width: '8px', height: '8px', borderRadius: '50%', background: '#7A4450' }} />
            {session?.currentStaff ? t('shell.topbar.worker', { name: session.currentStaff.name }) : t('shell.topbar.workerChecking')}
          </span>
          <svg
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M6 9l6 6 6-6"></path>
          </svg>
        </button>
        {menuOpen && (
          <span
            role="menu"
            style={{
              position: 'absolute',
              top: 'calc(100% + 8px)',
              right: 0,
              zIndex: 50,
              minWidth: '160px',
              padding: '6px 0',
              borderRadius: '14px',
              background: '#FFFFFF',
              border: '1px solid #F1E4E0',
              boxShadow: '0 6px 22px rgba(160,90,90,.12)',
            }}
          >
            <button type="button" role="menuitem" style={menuItem} onClick={() => router.push('/shift')}>
              {t('shell.topbar.switchWorker')}
            </button>
            <button type="button" role="menuitem" style={menuItem} onClick={() => void logout()}>
              {t('shell.topbar.logout')}
            </button>
          </span>
        )}
      </span>
      {session && (
        <>
          <span style={{ width: '1px', height: '14px', background: '#D9CBC4' }} />
          {/* 매장 이름 안내 대신 바로 근무자 PIN 화면으로 가는 버튼 (#403) */}
          <button
            type="button"
            onClick={() => router.push('/shift')}
            style={{
              border: 0,
              background: 'transparent',
              padding: 0,
              font: "500 13px 'Noto Sans KR', sans-serif",
              color: '#7A4450',
              cursor: 'pointer',
              whiteSpace: 'nowrap',
            }}
          >
            {t('shell.topbar.switchWorkerLong')}
          </button>
        </>
      )}
      <span style={{ width: '1px', height: '26px', background: '#D9CBC4' }} />
      <span
        aria-hidden="true"
        style={{
          width: '48px',
          height: '48px',
          borderRadius: '50%',
          background: '#D8CCC6',
          display: 'flex',
          alignItems: 'flex-end',
          justifyContent: 'center',
          overflow: 'hidden',
        }}
      >
        <svg width="36" height="36" viewBox="0 0 24 24" fill="#F3ECE8">
          <circle cx="12" cy="9" r="4.2"></circle>
          <path d="M3.5 24c0-5 3.8-8 8.5-8s8.5 3 8.5 8z"></path>
        </svg>
      </span>
    </header>
  );
}
