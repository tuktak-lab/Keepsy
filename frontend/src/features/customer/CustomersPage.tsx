import { useEffect, useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { Avatar } from '@/components/common/Avatar';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import { api, type Page } from '@/lib/api';
import type { StaffRef } from '@/lib/session';
import type { Category, CustomerListFilter, CustomerListItem, PredictionStatus } from './types';
import styleText from './CustomersPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';

// 고객 목록 · 검색 · 필터 (FR-CUS-02). GET /api/customers, 20건씩.
// 데모의 누적 결제액 · 시술 카테고리 · 예측 상태 칸은 목록 API 응답에 없어 뺐다 (필터로는 쓴다).

const PAGE_SIZE = 20;

const CATEGORIES: [Category, string][] = [
  ['NAIL', '네일'],
  ['LASH', '속눈썹'],
  ['WAXING', '왁싱'],
  ['SEMI_PERMANENT', '반영구'],
  ['ETC', '기타'],
];

const STATUSES: [PredictionStatus, string][] = [
  ['SCHEDULED', '예정'],
  ['DUE', '주기 도래'],
  ['CHURN_SIGNAL', '이탈 신호'],
  ['LONG_ABSENT', '장기 미방문'],
  ['NO_PREDICTION', '예측 없음'],
];

// 방문 횟수 범위 → visitCountMin · visitCountMax
const VISITS: [string, string, number | undefined, number | undefined][] = [
  ['', '방문 횟수 전체', undefined, undefined],
  ['0', '첫 방문 전', 0, 0],
  ['1-2', '방문 1~2회', 1, 2],
  ['3-5', '방문 3~5회', 3, 5],
  ['6+', '방문 6회 이상', 6, undefined],
];

/** `2026-10-24` → `2026.10.24` (서버 날짜는 이미 Asia/Seoul) */
const dot = (date: string) => date.replaceAll('-', '.');

function toQuery(filter: CustomerListFilter, page: number): string {
  const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  for (const [key, value] of Object.entries(filter)) {
    if (value !== undefined && value !== '') params.set(key, String(value));
  }
  return params.toString();
}

export default function CustomersPage() {
  useStyleSheet('CustomersPage', styleText);
  const [q, setQ] = useState('');
  const [query, setQuery] = useState(''); // 입력이 멈춘 뒤의 검색어
  const [cat, setCat] = useState<Category | ''>('');
  const [status, setStatus] = useState<PredictionStatus | ''>('');
  const [vis, setVis] = useState('');
  const [staffId, setStaffId] = useState('');
  const [consent, setConsent] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<Page<CustomerListItem> | null>(null);
  const [staff, setStaff] = useState<StaffRef[]>([]);
  const [sel, setSel] = useState<number | null>(null);

  // 담당 스탭 필터 목록
  useEffect(() => {
    api<StaffRef[]>('/api/staff').then(setStaff, () => {});
  }, []);

  // 검색어는 입력이 0.3초 멈추면 반영
  useEffect(() => {
    const t = setTimeout(() => {
      setQuery(q.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [q]);

  useEffect(() => {
    const range = VISITS.find(([key]) => key === vis);
    const filter: CustomerListFilter = {
      q: query || undefined,
      category: cat || undefined,
      predictionStatus: status || undefined,
      visitCountMin: range?.[2],
      visitCountMax: range?.[3],
      staffId: staffId ? Number(staffId) : undefined,
      marketingConsent: consent ? consent === 'true' : undefined,
    };
    let alive = true;
    api<Page<CustomerListItem>>('/api/customers?' + toQuery(filter, page)).then(
      (res) => alive && setResult(res),
      () => {},
    );
    return () => {
      alive = false;
    };
  }, [query, cat, status, vis, staffId, consent, page]);

  // 필터를 바꾸면 첫 페이지부터
  const pick =
    <T,>(set: (v: T) => void) =>
    (v: T) => {
      set(v);
      setPage(0);
    };

  const rows = result?.items ?? [];
  const total = result?.totalElements ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const cur = rows.find((r) => r.customerId === sel) ?? rows[0];

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', minHeight: '1020px' }}>
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="customers" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: '0', display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb="Customer Intelligence / 고객 목록" />
        <div className="wrap">
          <div className="row between" style={{ alignItems: 'flex-end' }}>
            <div className="col" style={{ gap: '6px' }}>
              <h1 className="h1">고객 목록</h1>
              <p className="sub">이름이나 연락처 뒷자리로 찾고, 방문 횟수 · 시술 카테고리 · 예측 상태로 다시 만날 고객을 골라 보세요.</p>
            </div>
            <div className="row" style={{ gap: '8px' }}>
              <AppLink href="/customers/retention" className="btn">파기 대상</AppLink>
              <AppLink href="/customers/import" className="btn">CSV 일괄 등록</AppLink>
              <AppLink href="/customers/new" className="btn btn-p">+ 고객 등록</AppLink>
            </div>
          </div>

          <div className="row" style={{ gap: '8px' }}>
            <input
              type="search"
              className="fld"
              style={{ flex: '1 1 260px', width: 'auto' }}
              placeholder="이름 또는 연락처 뒷자리 4자리"
              aria-label="고객 검색"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
            <select className="sel" aria-label="시술 카테고리" value={cat} onChange={(e) => pick(setCat)(e.target.value as Category | '')}>
              <option value="">시술 카테고리 전체</option>
              {CATEGORIES.map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <select className="sel" aria-label="예측 상태" value={status} onChange={(e) => pick(setStatus)(e.target.value as PredictionStatus | '')}>
              <option value="">예측 상태 전체</option>
              {STATUSES.map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <select className="sel" aria-label="방문 횟수" value={vis} onChange={(e) => pick(setVis)(e.target.value)}>
              {VISITS.map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <select className="sel" aria-label="담당 스탭" value={staffId} onChange={(e) => pick(setStaffId)(e.target.value)}>
              <option value="">담당 스탭 전체</option>
              {staff.map((s) => (
                <option key={s.id} value={s.id}>{s.name}</option>
              ))}
            </select>
            <select className="sel" aria-label="마케팅 수신 동의" value={consent} onChange={(e) => pick(setConsent)(e.target.value)}>
              <option value="">수신 동의 전체</option>
              <option value="true">수신 동의</option>
              <option value="false">수신 미동의</option>
            </select>
          </div>

          <section className="card" style={{ padding: '10px 0 6px', overflowX: 'auto' }}>
            <div className="crow head">
              <span>고객</span>
              <span>총 방문</span>
              <span>최근 방문</span>
              <span>마케팅 수신</span>
            </div>
            <div className="kp-fade" key={[query, cat, status, vis, staffId, consent, page].join('|')}>
            {rows.map((r) => (
              <div className={'crow' + (r.customerId === cur?.customerId ? ' on' : '')} key={r.customerId}>
                <button type="button" className="pick" onClick={() => setSel(r.customerId)} aria-pressed={r.customerId === cur?.customerId}>
                  <Avatar who={r.name} size="36" />
                  <span className="col">
                    <span style={{ fontWeight: 600, fontSize: '14px' }}>{r.name}</span>
                    <span className="meta num">{r.phone}</span>
                  </span>
                </button>
                <span className="num" style={{ fontWeight: 600 }}>{r.visitCount}회</span>
                <span className="num">{r.lastVisitDate ? dot(r.lastVisitDate) : <span className="meta">방문 전</span>}</span>
                <span className={r.marketingConsent ? '' : 'meta'}>{r.marketingConsent ? '동의' : '미동의'}</span>
              </div>
            ))}
            {result && rows.length === 0 ? (
              <div className="kp-empty"><b>조건에 맞는 고객이 없어요</b>검색어나 필터를 바꿔 보세요.</div>
            ) : null}
            </div>
            <div className="row between meta" style={{ padding: '10px 18px 8px', borderTop: '1px solid #F1E4E0' }}>
              <span>{result ? `${total}명 중 ${rows.length}명 표시` : '불러오는 중…'}</span>
              <span className="row" style={{ gap: '8px' }}>
                <button type="button" className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
                <span className="num">{page + 1} / {pages}</span>
                <button type="button" className="btn" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>다음</button>
              </span>
            </div>
          </section>

          {cur ? (
            <section className="card">
              <div className="row between kp-slide" key={cur.customerId}>
              <div className="row" style={{ gap: '14px' }}>
                <Avatar who={cur.name} size="48" />
                <div className="col">
                  <strong style={{ fontSize: '17px' }}>{cur.name}</strong>
                  <span className="sub">
                    총 방문 {cur.visitCount}회 · 최근 방문 {cur.lastVisitDate ? dot(cur.lastVisitDate) : '없음'} · 마케팅 수신 {cur.marketingConsent ? '동의' : '미동의'}
                  </span>
                </div>
              </div>
              <div className="row" style={{ gap: '8px' }}>
                <AppLink href={`/customers/detail?id=${cur.customerId}`} className="btn btn-p">고객 상세 · 히스토리북</AppLink>
              </div>
              </div>
            </section>
          ) : null}
          <p className="meta">
            예측 상태 필터는 오늘 기준으로 그 상태인 시술 카테고리가 하나라도 있는 고객을 보여 줘요. 카테고리를 함께 고르면 그 카테고리의 상태로 찾아요.
          </p>
        </div>
      </main>
    </div>
  );
}
