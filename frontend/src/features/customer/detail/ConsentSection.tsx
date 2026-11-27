import { useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { api } from '@/lib/api';
import { consentLabel } from '@/lib/consents';
import { formatDateTime } from '@/lib/format';
import type { ConsentMethod } from '@/lib/newCustomer';
import type { ConsentHistoryRow, ConsentItem, CustomerConsents } from '../types';
import { AreaError, errorText } from './common';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';

// 동의 항목 (FR-CUS-09, E8). 받기 · 철회는 공용 고정(CONSENT), 잘못 입력한 동의 정정만 PIN(CUSTOMER_EDIT).
// 사진 철회는 사진을, 건강 정보 철회는 건강 주의사항만 서버가 바로 지운다.
// 정정은 실수했을 때만 쓰므로 동의 이력 안, 항목별 최신 행에만 둔다 (서버도 최신 행만 고친다).

/**
 * 매장에서는 동의를 두 가지로 받는다: 고객이 태블릿에서 직접 체크(TABLET), 말로 확인하고 직원이 앱에서 누름.
 * 직원 입력은 계약 값 VERBAL(구두)로 남기고 화면에는 "직원 입력"으로 보인다. PAPER(서면)는 예전 기록 표시용이다.
 */
const STAFF_METHOD: ConsentMethod = 'VERBAL';
const METHOD_KEY: Record<string, MessageKey> = { TABLET: 'customer.method.tablet', VERBAL: 'customer.method.staffInput', PAPER: 'customer.method.paper', IMPORT: 'customer.method.import' };
const methodLabel = (m: string): string => (METHOD_KEY[m] ? t(METHOD_KEY[m]) : m);
const WITHDRAW_WARN: Partial<Record<ConsentItem, MessageKey>> = {
  PHOTO: 'customer.consentSec.warnPhoto',
  HEALTH: 'customer.consentSec.warnHealth',
};

/**
 * 상태 칩: [글자, 배경, 글자색]. 철회는 철회 경로가 있는 행이다.
 * 정정으로 미동의가 된 행은 철회 경로가 없어 "받은 적 없음"이다 (CONTRACTS 6장, F19 · F44).
 */
function statusChip(agreed: boolean, withdrawn: boolean): [MessageKey, string, string] {
  if (agreed) return ['customer.agreed', '#F9EBE8', '#7A4450'];
  if (withdrawn) return ['customer.withdrawn', '#EFE7E3', '#5B4D47'];
  return ['customer.consentSec.chipNever', '#F5F1EF', '#7A6F6A'];
}

export function ConsentSection(props: {
  customerId: number;
  consents: CustomerConsents;
  onChanged: () => Promise<void>;
  onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [confirm, setConfirm] = useState<ConsentItem | null>(null);
  const [correcting, setCorrecting] = useState<number | null>(null);
  const [showHistory, setShowHistory] = useState(false);

  async function run(action: () => Promise<unknown>, done: string, tone: 'success' | 'warn' = 'success') {
    setBusy(true);
    setError('');
    try {
      await action();
      toast(done, tone);
      setConfirm(null);
      setCorrecting(null);
      await props.onChanged();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  const change = (item: ConsentItem, agreed: boolean) =>
    run(
      () => api(`/api/customers/${props.customerId}/consents`, { method: 'POST', body: { item, agreed, method: STAFF_METHOD }, silent: true }),
      agreed ? t('customer.consentSec.agreedToast', { item: consentLabel(item) }) : t('customer.consentSec.withdrawnToast', { item: consentLabel(item) }),
      agreed ? 'success' : 'warn',
    );

  const correct = (row: ConsentHistoryRow) =>
    run(
      () => api(`/api/consents/${row.consentId}/corrections`, { method: 'POST', body: { agreed: !row.agreed }, silent: true }),
      t('customer.consentSec.fixedToast', { item: consentLabel(row.item), to: row.agreed ? t('customer.notAgreed') : t('customer.agreed') }),
    );

  // 항목별 최신 이력 행 (현재 상태 · 정정 대상)
  const latest = Object.fromEntries(props.consents.history.map((r) => [r.item, r])) as Partial<Record<ConsentItem, ConsentHistoryRow>>;
  const latestIds = new Set(Object.values(latest).map((r) => r!.consentId));

  return (
    <section className="card col" style={{ gap: 14 }}>
      <div className="row between">
        <h2 className="h2" id="consent-title">
          {t('customer.detail.consentItems')}
        </h2>
        <span className="row" style={{ gap: 8 }}>
          <AppLink href={`/consent?customerId=${props.customerId}`} className="btn">
            {t('customer.consentSec.tablet')}
          </AppLink>
          <button type="button" className="btn" onClick={props.onClose} aria-label={t('common.close')}>
            {t('common.close')}
          </button>
        </span>
      </div>

      <div className="col">
        {props.consents.current.map((c) => {
          const [chip, bg, fg] = statusChip(c.agreed, Boolean(latest[c.item]?.withdrawalPath));
          return (
            <div key={c.item} className="crow2">
              <span className="col" style={{ gap: 2 }}>
                <strong style={{ fontSize: 15 }}>{consentLabel(c.item)}</strong>
                <span className="meta">{c.item === 'PRIVACY' ? t('customer.consentSec.requiredNote') : t('customer.optional')}</span>
              </span>
              <span className="col" style={{ gap: 4, alignItems: 'flex-start' }}>
                <span className="chip" style={{ background: bg, color: fg }}>
                  <i></i>
                  {t(chip)}
                </span>
                {c.recordedAt && chip !== 'customer.consentSec.chipNever' && <span className="meta num">{formatDateTime(c.recordedAt)}</span>}
              </span>
              <span style={{ justifySelf: 'end' }}>
                {c.item === 'PRIVACY' ? null : c.agreed ? (
                  <button type="button" className="btn" disabled={busy || confirm !== null} onClick={() => setConfirm(c.item)}>
                    {t('customer.withdrawAction')}
                  </button>
                ) : (
                  <button type="button" className="btn btn-p" disabled={busy || confirm !== null} onClick={() => setConfirm(c.item)}>
                    {t('customer.consentSec.collect')}
                  </button>
                )}
              </span>
              {/* 공용 고정 기능이라(E8) PIN 대신 확인 단계로 잘못 누르는 것을 막는다 */}
              {confirm === c.item && (
                <div
                  className="note col"
                  style={{ gap: 10, gridColumn: '1 / -1', justifySelf: 'center', width: 'min(440px, 100%)', boxSizing: 'border-box', alignItems: 'center', textAlign: 'center' }}
                  role="alertdialog"
                  aria-label={t('customer.consentSec.confirmAria', { item: consentLabel(c.item) })}
                >
                  {c.agreed ? (
                    <span>
                      {t('customer.consentSec.askWithdrawPre')}<b>{consentLabel(c.item)}</b>{t('customer.consentSec.askWithdrawPost')}
                      {WITHDRAW_WARN[c.item] && (
                        <>
                          <br />
                          {t(WITHDRAW_WARN[c.item]!)}
                        </>
                      )}
                    </span>
                  ) : (
                    <span>
                      {t('customer.consentSec.askConfirmPre')}<b>{consentLabel(c.item)}</b>{t('customer.consentSec.askConfirmPost')}
                      <br />
                      {t('customer.consentSec.recordStaff')}
                    </span>
                  )}
                  <span className="row" style={{ gap: 8, justifyContent: 'center' }}>
                    <button type="button" className="btn btn-p" disabled={busy} onClick={() => change(c.item, !c.agreed)}>
                      {c.agreed ? t('customer.withdrawAction') : t('customer.tablet.confirmBtn')}
                    </button>
                    <button type="button" className="btn" onClick={() => setConfirm(null)}>
                      {t('common.cancel')}
                    </button>
                  </span>
                </div>
              )}
            </div>
          );
        })}
      </div>

      <AreaError text={error} />
      <p className="meta">{t('customer.consentSec.footNote')}</p>

      <button type="button" className="lk" style={{ alignSelf: 'flex-start' }} onClick={() => setShowHistory((s) => !s)}>
        {showHistory ? t('customer.consentSec.historyHide') : t('customer.consentSec.historyShow', { n: props.consents.history.length })}
      </button>
      {showHistory && (
        <div className="col">
          <p className="meta" style={{ paddingBottom: 6 }}>
            {t('customer.consentSec.fixNote')}
          </p>
          {[...props.consents.history].reverse().map((r) => {
            const [chip, bg, fg] = r.correctsConsentId ? ['customer.consentSec.chipFix', '#EEF2EE', '#3F6150'] as [MessageKey, string, string] : statusChip(r.agreed, Boolean(r.withdrawalPath));
            return (
              <div key={r.consentId} className="hrow">
                <span className="meta num">{formatDateTime(r.recordedAt)}</span>
                <strong>{consentLabel(r.item)}</strong>
                <span className="row" style={{ gap: 6 }}>
                  <span className="chip" style={{ background: bg, color: fg }}>
                    {t(chip)}
                  </span>
                  {r.correctsConsentId && <span className="meta">→ {r.agreed ? t('customer.agreed') : t('customer.notAgreed')}</span>}
                </span>
                <span className="meta">
                  {r.staff?.name ?? t('customer.consentSec.byCustomer')}
                  {r.method && ` · ${methodLabel(r.method)}`}
                  {r.withdrawalPath && ` · ${r.withdrawalPath === 'UNSUBSCRIBE' ? t('customer.consentSec.unsub') : t('customer.consentSec.selfWithdraw')}`}
                </span>
                <span>
                  {r.item !== 'PRIVACY' &&
                    latestIds.has(r.consentId) &&
                    (correcting === r.consentId ? (
                      <span className="row" style={{ gap: 8 }}>
                        <span className="meta">{t('customer.consentSec.askFix', { to: r.agreed ? t('customer.notAgreed') : t('customer.agreed') })}</span>
                        <button type="button" className="lk" disabled={busy} onClick={() => correct(r)}>
                          {t('customer.consentSec.fixBtn')}
                        </button>
                        <button type="button" className="lk" style={{ color: '#574B46' }} onClick={() => setCorrecting(null)}>
                          {t('common.cancel')}
                        </button>
                      </span>
                    ) : (
                      <button type="button" className="lk" onClick={() => setCorrecting(r.consentId)}>
                        {t('customer.consentSec.fix')}
                      </button>
                    ))}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
