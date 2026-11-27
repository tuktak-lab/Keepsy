import { useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { Avatar } from '@/components/common/Avatar';
import { api } from '@/lib/api';
import type { CustomerConsents, CustomerDetail } from './types';
import { AreaError, errorText } from './detail/common';
import { ConsentSection } from './detail/ConsentSection';
import { DeleteCustomer } from './detail/DeleteCustomer';
import { EditBasicInfo } from './detail/EditBasicInfo';
import { HistoryTab } from './detail/HistoryTab';
import { MemoTab } from './detail/MemoTab';
import { MessagesTab } from './detail/MessagesTab';
import { SummaryTab } from './detail/SummaryTab';
import styleText from './CustomerDetailPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { useLoad } from '@/hooks/useLoad';
import { t } from '@/lib/i18n';

// 고객 상세 · 시술 히스토리북 (FR-CUS-03~11, FR-PRD-02). `?id=`로 연다.
// 영역마다 기능 키가 달라(명세서 2장) 각 API를 따로 부르고, 실패하면 그 영역에만 사유를 보인다.
// 데모의 `/history`는 문서 경로 표에 없어 이 화면의 「시술 히스토리북」 탭으로 합쳤다.

const TABS = [
  ['summary', 'customer.detail.tabSummary'],
  ['history', 'customer.detail.tabHistory'],
  ['memo', 'customer.detail.tabMemo'],
  ['messages', 'customer.detail.tabMessages'],
] as const;
type Tab = (typeof TABS)[number][0];

export default function CustomerDetailPage() {
  useStyleSheet('CustomerDetailPage', styleText);
  const customerId = Number(useSearchParams().get('id'));
  const valid = Number.isInteger(customerId) && customerId > 0;
  const [tab, setTab] = useState<Tab>('summary');
  // 전체 번호는 그 고객 것일 때만 보인다 (다른 고객으로 바꾸면 가린 번호로)
  const [fullPhone, setFullPhone] = useState<{ id: number; phone: string } | null>(null);
  const phone = fullPhone?.id === customerId ? fullPhone.phone : null;
  const [phoneError, setPhoneError] = useState('');
  const [consentOpen, setConsentOpen] = useState(false);

  // 고객 상세 + 동의를 함께 받는다. 다른 고객으로 바꾸면 지난 응답은 버린다 (useLoad, #344)
  const load = useLoad(valid ? String(customerId) : null, () =>
    Promise.all([
      api<CustomerDetail>(`/api/customers/${customerId}`, { silent: true }),
      api<CustomerConsents>(`/api/customers/${customerId}/consents`, { silent: true }),
    ]),
  );
  const [detail, consents] = load.data ?? [null, null];
  const error = valid ? (load.error ? errorText(load.error) : '') : t('customer.detail.noCustomer');
  const reload = load.reload;

  async function showPhone() {
    setPhoneError('');
    try {
      const r = await api<{ phone: string }>(`/api/customers/${customerId}/contact`, { silent: true });
      setFullPhone({ id: customerId, phone: r.phone });
    } catch (err) {
      setPhoneError(errorText(err));
    }
  }

  const photoAgreed = Boolean(consents?.current.some((c) => c.item === 'PHOTO' && c.agreed));

  return (
    <>
      <div className="wrap">
        <div className="row between">
          <AppLink href="/customers" className="meta">
            {t('customer.detail.back')}
          </AppLink>
          {detail && (
            <span className="row" style={{ gap: 8, alignItems: 'flex-start' }}>
              <EditBasicInfo customerId={customerId} detail={detail} onSaved={reload} />
              <DeleteCustomer customerId={customerId} name={detail.name} />
            </span>
          )}
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
                      <i></i>{t('customer.detail.noShow', { n: detail.noShowCount })}
                    </span>
                  )}
                  {detail.bookingRestricted && (
                    <span className="chip" style={{ background: '#F6E3DF', color: '#8A2F3F' }} title={detail.bookingRestrictionReason ?? ''}>
                      <i></i>{t('customer.detail.restricted')}{detail.bookingRestrictionReason ? ` · ${detail.bookingRestrictionReason}` : ''}
                    </span>
                  )}
                </div>
                <span className="sub num row" style={{ gap: 8 }}>
                  {phone ?? detail.phone}
                  {!phone && (
                    <button type="button" className="lk" onClick={showPhone}>
                      {t('customer.detail.fullNumber')}
                    </button>
                  )}
                </span>
                {phoneError && <span className="meta" style={{ color: '#8A2F3F' }}>{phoneError}</span>}
              </div>
              <button type="button" className="btn" style={{ marginLeft: 'auto', gap: 8 }} onClick={() => setConsentOpen(true)}>
                {t('customer.detail.consentItems')}
                <span className="chip" style={{ background: '#F9EBE8', color: '#7A4450' }}>
                  {t('customer.detail.consentCount', { done: consents.current.filter((c) => c.agreed).length, total: consents.current.length })}
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
              <div className="note col" style={{ gap: 4 }} role="note" aria-label={t('customer.detail.cautionLabel')}>
                {detail.serviceCaution && (
                  <span>
                    <b>{t('customer.detail.serviceCautionShort')}</b> {detail.serviceCaution}
                  </span>
                )}
                {detail.healthCaution && (
                  <span>
                    <b>{t('customer.detail.healthCautionShort')}</b> {detail.healthCaution}
                  </span>
                )}
              </div>
            )}

            <div className="tabs" role="tablist" aria-label={t('customer.detail.tabsLabel')}>
              {TABS.map(([id, label]) => (
                <button type="button" role="tab" aria-selected={tab === id} onClick={() => setTab(id)} key={id}>
                  {t(label)}
                </button>
              ))}
            </div>

            {/* 고객이 바뀌면 탭을 새로 만든다: 쓰던 메모 · 펼친 페이지가 다른 고객에게 남지 않게 (#344) */}
            <div className="kp-fade" key={`${tab}-${customerId}`} style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
              {tab === 'summary' && <SummaryTab customerId={customerId} detail={detail} consents={consents} reload={reload} />}
              {tab === 'history' && <HistoryTab customerId={customerId} photoAgreed={photoAgreed} />}
              {tab === 'memo' && <MemoTab customerId={customerId} />}
              {tab === 'messages' && <MessagesTab customerId={customerId} />}
            </div>
          </>
        )}
      </div>
    </>
  );
}
