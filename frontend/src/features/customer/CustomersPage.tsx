import { useEffect, useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { Avatar } from '@/components/common/Avatar';
import { api, type Page } from '@/lib/api';
import type { StaffRef } from '@/lib/session';
import type { Category, CustomerListFilter, CustomerListItem, PredictionStatus } from './types';
import styleText from './CustomersPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';

// 고객 목록 · 검색 · 필터 (FR-CUS-02). GET /api/customers, 20건씩.
// 데모의 누적 결제액 · 시술 카테고리 · 예측 상태 칸은 목록 API 응답에 없어 뺐다 (필터로는 쓴다).

const PAGE_SIZE = 20;

const CATEGORIES: [Category, MessageKey][] = [
  ['NAIL', 'category.nail'],
  ['LASH', 'category.lash'],
  ['WAXING', 'category.waxing'],
  ['SEMI_PERMANENT', 'category.semi'],
  ['ETC', 'category.etc'],
];

const STATUSES: [PredictionStatus, MessageKey][] = [
  ['SCHEDULED', 'dashboard.seg.scheduled'],
  ['DUE', 'prediction.status.due'],
  ['CHURN_SIGNAL', 'prediction.status.churn'],
  ['LONG_ABSENT', 'prediction.status.long'],
  ['NO_PREDICTION', 'customer.status.none'],
];

// 방문 횟수 범위 → visitCountMin · visitCountMax
const VISITS: [string, MessageKey, number | undefined, number | undefined][] = [
  ['', 'customer.list.visitsAll', undefined, undefined],
  ['0', 'customer.list.visitsFirst', 0, 0],
  ['1-2', 'customer.list.visits12', 1, 2],
  ['3-5', 'customer.list.visits35', 3, 5],
  ['6+', 'customer.list.visits6', 6, undefined],
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
    <>
      <div className="wrap">
        <div className="row between" style={{ alignItems: 'flex-end' }}>
          <div className="col" style={{ gap: '6px' }}>
            <h1 className="h1">{t('customer.list.title')}</h1>
            <p className="sub">{t('customer.list.sub')}</p>
          </div>
          <div className="row" style={{ gap: '8px' }} data-guide="customers-actions">
            <AppLink href="/customers/retention" className="btn">{t('customer.list.retention')}</AppLink>
            <AppLink href="/customers/import" className="btn">{t('customer.list.import')}</AppLink>
            <AppLink href="/customers/new" className="btn btn-p">{t('customer.list.new')}</AppLink>
          </div>
        </div>

        <div className="row" style={{ gap: '8px' }} data-guide="customers-search">
          <input
            type="search"
            className="fld"
            style={{ flex: '1 1 260px', width: 'auto' }}
            placeholder={t('customer.list.searchPlaceholder')}
            aria-label={t('customer.list.searchLabel')}
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <select className="sel" aria-label={t('customer.list.catLabel')} value={cat} onChange={(e) => pick(setCat)(e.target.value as Category | '')}>
            <option value="">{t('customer.list.catAll')}</option>
            {CATEGORIES.map(([value, label]) => (
              <option key={value} value={value}>{t(label)}</option>
            ))}
          </select>
          <select className="sel" aria-label={t('customer.list.statusLabel')} value={status} onChange={(e) => pick(setStatus)(e.target.value as PredictionStatus | '')}>
            <option value="">{t('customer.list.statusAll')}</option>
            {STATUSES.map(([value, label]) => (
              <option key={value} value={value}>{t(label)}</option>
            ))}
          </select>
          <select className="sel" aria-label={t('customer.list.visitsLabel')} value={vis} onChange={(e) => pick(setVis)(e.target.value)}>
            {VISITS.map(([value, label]) => (
              <option key={value} value={value}>{t(label)}</option>
            ))}
          </select>
          <select className="sel" aria-label={t('common.staff')} value={staffId} onChange={(e) => pick(setStaffId)(e.target.value)}>
            <option value="">{t('customer.list.staffAll')}</option>
            {staff.map((s) => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
          <select className="sel" aria-label={t('customer.list.consentLabel')} value={consent} onChange={(e) => pick(setConsent)(e.target.value)}>
            <option value="">{t('customer.list.consentAll')}</option>
            <option value="true">{t('common.consent')}</option>
            <option value="false">{t('common.noConsent')}</option>
          </select>
        </div>

        <section className="card" style={{ padding: '10px 0 6px', overflowX: 'auto' }}>
          <div className="crow head" data-guide="customers-list">
            <span>{t('common.customer')}</span>
            <span>{t('customer.list.colVisits')}</span>
            <span>{t('customer.list.colLast')}</span>
            <span>{t('customer.list.colMarketing')}</span>
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
              <span className="num" style={{ fontWeight: 600 }}>{t('customer.visits', { n: r.visitCount })}</span>
              <span className="num">{r.lastVisitDate ? dot(r.lastVisitDate) : <span className="meta">{t('customer.list.beforeVisit')}</span>}</span>
              <span className={r.marketingConsent ? '' : 'meta'}>{r.marketingConsent ? t('customer.agreed') : t('customer.notAgreed')}</span>
            </div>
          ))}
          {result && rows.length === 0 ? (
            <div className="kp-empty"><b>{t('customer.list.emptyTitle')}</b>{t('customer.list.emptyHint')}</div>
          ) : null}
          </div>
          <div className="row between meta" style={{ padding: '10px 18px 8px', borderTop: '1px solid #F1E4E0' }}>
            <span>{result ? t('customer.list.showing', { total, shown: rows.length }) : t('common.loading')}</span>
            <span className="row" style={{ gap: '8px' }}>
              <button type="button" className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>{t('customer.list.prev')}</button>
              <span className="num">{page + 1} / {pages}</span>
              <button type="button" className="btn" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>{t('customer.list.next')}</button>
            </span>
          </div>
        </section>

        {cur ? (
          <section className="card" data-guide="customers-detail">
            <div className="row between kp-slide" key={cur.customerId}>
            <div className="row" style={{ gap: '14px' }}>
              <Avatar who={cur.name} size="48" />
              <div className="col">
                <strong style={{ fontSize: '17px' }}>{cur.name}</strong>
                <span className="sub">
                  {t('customer.list.summary', { visits: cur.visitCount, last: cur.lastVisitDate ? dot(cur.lastVisitDate) : t('customer.none'), consent: cur.marketingConsent ? t('customer.agreed') : t('customer.notAgreed') })}
                </span>
              </div>
            </div>
            <div className="row" style={{ gap: '8px' }}>
              <AppLink href={`/customers/detail?id=${cur.customerId}`} className="btn btn-p">{t('customer.list.detailBtn')}</AppLink>
            </div>
            </div>
          </section>
        ) : null}
        <p className="meta">
          {t('customer.list.filterNote')}
        </p>
      </div>
    </>
  );
}
