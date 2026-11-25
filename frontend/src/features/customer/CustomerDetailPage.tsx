import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { Avatar } from '@/components/common/Avatar';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import { api } from '@/lib/api';
import type { CustomerConsents, CustomerDetail } from './types';
import { AreaError, errorText } from './detail/common';
import { ConsentSection } from './detail/ConsentSection';
import { DeleteCustomer } from './detail/DeleteCustomer';
import { HistoryTab } from './detail/HistoryTab';
import { MemoTab } from './detail/MemoTab';
import { MessagesTab } from './detail/MessagesTab';
import { SummaryTab } from './detail/SummaryTab';
import styleText from './CustomerDetailPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';

// 고객 상세 · 시술 히스토리북 (FR-CUS-03~11, FR-PRD-02). `?id=`로 연다.
// 영역마다 기능 키가 달라(명세서 2장) 각 API를 따로 부르고, 실패하면 그 영역에만 사유를 보인다.
// 데모의 `/history`는 문서 경로 표에 없어 이 화면의 「시술 히스토리북」 탭으로 합쳤다.

const TABS = [
  ['summary', '고객 요약'],
  ['history', '시술 히스토리북'],
  ['memo', '상담 메모'],
  ['messages', '수신 이력'],
] as const;
type Tab = (typeof TABS)[number][0];

export default function CustomerDetailPage() {
  useStyleSheet('CustomerDetailPage', styleText);
  const customerId = Number(useSearchParams().get('id'));
  const valid = Number.isInteger(customerId) && customerId > 0;
  const [tab, setTab] = useState<Tab>('summary');
  const [detail, setDetail] = useState<CustomerDetail | null>(null);
  const [consents, setConsents] = useState<CustomerConsents | null>(null);
  const [error, setError] = useState(valid ? '' : '고객을 고르지 않았어요. 고객 목록에서 열어 주세요.');
  const [phone, setPhone] = useState<string | null>(null);
  const [phoneError, setPhoneError] = useState('');
  const [consentOpen, setConsentOpen] = useState(false);

  const reload = useCallback(async () => {
    try {
      const [d, c] = await Promise.all([
        api<CustomerDetail>(`/api/customers/${customerId}`, { silent: true }),
        api<CustomerConsents>(`/api/customers/${customerId}/consents`, { silent: true }),
      ]);
      setDetail(d);
      setConsents(c);
    } catch (err) {
      setError(errorText(err));
    }
  }, [customerId]);

  useEffect(() => {
    if (!valid) return;
    Promise.all([
      api<CustomerDetail>(`/api/customers/${customerId}`, { silent: true }),
      api<CustomerConsents>(`/api/customers/${customerId}/consents`, { silent: true }),
    ])
      .then(([d, c]) => {
        setDetail(d);
        setConsents(c);
      })
      .catch((err) => setError(errorText(err)));
  }, [customerId, valid]);

  async function showPhone() {
    setPhoneError('');
    try {
      setPhone((await api<{ phone: string }>(`/api/customers/${customerId}/contact`, { silent: true })).phone);
    } catch (err) {
      setPhoneError(errorText(err));
    }
  }

  const photoAgreed = Boolean(consents?.current.some((c) => c.item === 'PHOTO' && c.agreed));

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', minHeight: '1280px' }}>
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="customers" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: '0', display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb={`Customer Intelligence / 고객 목록 / ${detail?.name ?? '고객 상세'}`} />
        <div className="wrap">
          <div className="row between">
            <AppLink href="/customers" className="meta">
              ‹ 고객 목록
            </AppLink>
            {detail && <DeleteCustomer customerId={customerId} name={detail.name} />}
          </div>

          {error && <AreaError text={error} />}

          {detail && consents && (
            <>
              <section className="card row" style={{ gap: '18px' }}>
                <Avatar who={detail.name} size="72" />
                <div className="col" style={{ gap: '4px' }}>
                  <div className="row" style={{ gap: '10px' }}>
                    <h1 className="h1" style={{ fontSize: '24px' }}>
                      {detail.name}
                    </h1>
                    {detail.noShowCount > 0 && (
                      <span className="chip" style={{ background: '#F6E3DF', color: '#8A2F3F' }}>
                        <i></i>노쇼 {detail.noShowCount}회
                      </span>
                    )}
                    {detail.bookingRestricted && (
                      <span className="chip" style={{ background: '#F6E3DF', color: '#8A2F3F' }} title={detail.bookingRestrictionReason ?? ''}>
                        <i></i>예약 제한{detail.bookingRestrictionReason ? ` · ${detail.bookingRestrictionReason}` : ''}
                      </span>
                    )}
                  </div>
                  <span className="sub num row" style={{ gap: 8 }}>
                    {phone ?? detail.phone}
                    {!phone && (
                      <button type="button" className="lk" onClick={showPhone}>
                        전체 번호 보기
                      </button>
                    )}
                  </span>
                  {phoneError && <span className="meta" style={{ color: '#8A2F3F' }}>{phoneError}</span>}
                </div>
                <button type="button" className="btn" style={{ marginLeft: 'auto', gap: 8 }} onClick={() => setConsentOpen(true)}>
                  동의 항목
                  <span className="chip" style={{ background: '#F9EBE8', color: '#7A4450' }}>
                    {consents.current.filter((c) => c.agreed).length}/{consents.current.length} 동의
                  </span>
                </button>
              </section>

              {consentOpen && (
                <div
                  role="dialog"
                  aria-modal="true"
                  aria-labelledby="consent-title"
                  onKeyDown={(e) => e.key === 'Escape' && setConsentOpen(false)}
                  onClick={(e) => e.target === e.currentTarget && setConsentOpen(false)}
                  style={{ position: 'fixed', inset: 0, zIndex: 900, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24, background: 'rgba(34,29,27,.32)' }}
                >
                  <div style={{ width: 'min(820px, 100%)', maxHeight: '86vh', overflowY: 'auto', borderRadius: 20, background: '#FFFDFB' }}>
                    <ConsentSection customerId={customerId} consents={consents} onChanged={reload} onClose={() => setConsentOpen(false)} />
                  </div>
                </div>
              )}

              {(detail.serviceCaution || detail.healthCaution) && (
                <div className="note col" style={{ gap: 4 }} role="note" aria-label="주의사항">
                  {detail.serviceCaution && (
                    <span>
                      <b>시술 주의</b> {detail.serviceCaution}
                    </span>
                  )}
                  {detail.healthCaution && (
                    <span>
                      <b>건강 주의</b> {detail.healthCaution}
                    </span>
                  )}
                </div>
              )}

              <div className="tabs" role="tablist" aria-label="고객 상세">
                {TABS.map(([id, label]) => (
                  <button type="button" role="tab" aria-selected={tab === id} onClick={() => setTab(id)} key={id}>
                    {label}
                  </button>
                ))}
              </div>

              <div className="kp-fade" key={tab} style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
                {tab === 'summary' && <SummaryTab customerId={customerId} detail={detail} consents={consents} reload={reload} />}
                {tab === 'history' && <HistoryTab customerId={customerId} photoAgreed={photoAgreed} />}
                {tab === 'memo' && <MemoTab customerId={customerId} />}
                {tab === 'messages' && <MessagesTab customerId={customerId} />}
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  );
}
