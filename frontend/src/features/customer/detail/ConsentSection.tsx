import { useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { api } from '@/lib/api';
import { OPTIONAL_CONSENTS, PRIVACY_TEXT } from '@/lib/consents';
import { formatDateTime } from '@/lib/format';
import type { ConsentMethod } from '@/lib/newCustomer';
import type { ConsentHistoryRow, ConsentItem, CustomerConsents } from '../types';
import { AreaError, errorText } from './common';

// 동의 항목 (FR-CUS-09, E8). 받기 · 철회는 공용 고정(CONSENT), 잘못 입력한 동의 정정만 PIN(CUSTOMER_EDIT).
// 사진 철회는 사진을, 건강 정보 철회는 건강 주의사항만 서버가 바로 지운다.
// 정정은 실수했을 때만 쓰므로 동의 이력 안, 항목별 최신 행에만 둔다 (서버도 최신 행만 고친다).

const LABEL: Record<ConsentItem, string> = {
  PRIVACY: PRIVACY_TEXT.label,
  ...(Object.fromEntries(OPTIONAL_CONSENTS.map(([i, l]) => [i, l])) as Record<Exclude<ConsentItem, 'PRIVACY'>, string>),
};
/**
 * 매장에서는 동의를 두 가지로 받는다: 고객이 태블릿에서 직접 체크(TABLET), 말로 확인하고 직원이 앱에서 누름.
 * 직원 입력은 계약 값 VERBAL(구두)로 남기고 화면에는 "직원 입력"으로 보인다. PAPER(서면)는 예전 기록 표시용이다.
 */
const STAFF_METHOD: ConsentMethod = 'VERBAL';
const METHOD_LABEL: Record<string, string> = { TABLET: '태블릿', VERBAL: '직원 입력', PAPER: '서면', IMPORT: '가져오기' };
const WITHDRAW_WARN: Partial<Record<ConsentItem, string>> = {
  PHOTO: '철회하면 저장된 시술 사진이 모두 바로 삭제돼요.',
  HEALTH: '철회하면 건강 주의사항이 바로 삭제돼요.',
};

/**
 * 상태 칩: [글자, 배경, 글자색]. 철회는 철회 경로가 있는 행이다.
 * 정정으로 미동의가 된 행은 철회 경로가 없어 "받은 적 없음"이다 (CONTRACTS 6장, F19 · F44).
 */
function statusChip(agreed: boolean, withdrawn: boolean): [string, string, string] {
  if (agreed) return ['동의', '#F9EBE8', '#7A4450'];
  if (withdrawn) return ['철회', '#EFE7E3', '#5B4D47'];
  return ['받은 적 없음', '#F5F1EF', '#7A6F6A'];
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
      agreed ? `${LABEL[item]} 동의를 받았어요` : `${LABEL[item]} 동의를 철회했어요`,
      agreed ? 'success' : 'warn',
    );

  const correct = (row: ConsentHistoryRow) =>
    run(
      () => api(`/api/consents/${row.consentId}/corrections`, { method: 'POST', body: { agreed: !row.agreed }, silent: true }),
      `${LABEL[row.item]} 기록을 ${row.agreed ? '미동의' : '동의'}로 바로잡았어요`,
    );

  // 항목별 최신 이력 행 (현재 상태 · 정정 대상)
  const latest = Object.fromEntries(props.consents.history.map((r) => [r.item, r])) as Partial<Record<ConsentItem, ConsentHistoryRow>>;
  const latestIds = new Set(Object.values(latest).map((r) => r!.consentId));

  return (
    <section className="card col" style={{ gap: 14 }}>
      <div className="row between">
        <h2 className="h2" id="consent-title">
          동의 항목
        </h2>
        <span className="row" style={{ gap: 8 }}>
          <AppLink href={`/consent?customerId=${props.customerId}`} className="btn">
            태블릿으로 고객 본인 체크
          </AppLink>
          <button type="button" className="btn" onClick={props.onClose} aria-label="닫기">
            닫기
          </button>
        </span>
      </div>

      <div className="col">
        {props.consents.current.map((c) => {
          const [chip, bg, fg] = statusChip(c.agreed, Boolean(latest[c.item]?.withdrawalPath));
          return (
            <div key={c.item} className="crow2">
              <span className="col" style={{ gap: 2 }}>
                <strong style={{ fontSize: 15 }}>{LABEL[c.item]}</strong>
                <span className="meta">{c.item === 'PRIVACY' ? '필수 · 철회는 고객 삭제로 처리' : '선택'}</span>
              </span>
              <span className="col" style={{ gap: 4, alignItems: 'flex-start' }}>
                <span className="chip" style={{ background: bg, color: fg }}>
                  <i></i>
                  {chip}
                </span>
                {c.recordedAt && chip !== '받은 적 없음' && <span className="meta num">{formatDateTime(c.recordedAt)}</span>}
              </span>
              <span style={{ justifySelf: 'end' }}>
                {c.item === 'PRIVACY' ? null : c.agreed ? (
                  <button type="button" className="btn" disabled={busy || confirm !== null} onClick={() => setConfirm(c.item)}>
                    철회
                  </button>
                ) : (
                  <button type="button" className="btn btn-p" disabled={busy || confirm !== null} onClick={() => setConfirm(c.item)}>
                    동의 받기
                  </button>
                )}
              </span>
              {/* 공용 고정 기능이라(E8) PIN 대신 확인 단계로 잘못 누르는 것을 막는다 */}
              {confirm === c.item && (
                <div
                  className="note col"
                  style={{ gap: 10, gridColumn: '1 / -1', justifySelf: 'center', width: 'min(440px, 100%)', boxSizing: 'border-box', alignItems: 'center', textAlign: 'center' }}
                  role="alertdialog"
                  aria-label={`${LABEL[c.item]} 확인`}
                >
                  {c.agreed ? (
                    <span>
                      <b>{LABEL[c.item]}</b> 동의를 철회할까요?
                      {WITHDRAW_WARN[c.item] && (
                        <>
                          <br />
                          {WITHDRAW_WARN[c.item]}
                        </>
                      )}
                    </span>
                  ) : (
                    <span>
                      고객에게 <b>{LABEL[c.item]}</b> 동의를 직접 확인했나요?
                      <br />
                      처리한 직원 이름이 기록에 남아요.
                    </span>
                  )}
                  <span className="row" style={{ gap: 8, justifyContent: 'center' }}>
                    <button type="button" className="btn btn-p" disabled={busy} onClick={() => change(c.item, !c.agreed)}>
                      {c.agreed ? '철회' : '확인했어요'}
                    </button>
                    <button type="button" className="btn" onClick={() => setConfirm(null)}>
                      취소
                    </button>
                  </span>
                </div>
              )}
            </div>
          );
        })}
      </div>

      <AreaError text={error} />
      <p className="meta">사진 동의를 철회하면 저장된 사진이, 건강 정보 동의를 철회하면 건강 주의사항만 바로 삭제돼요.</p>

      <button type="button" className="lk" style={{ alignSelf: 'flex-start' }} onClick={() => setShowHistory((s) => !s)}>
        {showHistory ? '동의 이력 접기' : `동의 이력 보기 (${props.consents.history.length}건)`}
      </button>
      {showHistory && (
        <div className="col">
          <p className="meta" style={{ paddingBottom: 6 }}>
            직원이 잘못 입력한 기록만 [정정]으로 바로잡아요. 고객이 마음을 바꾼 경우는 위의 [철회] · [동의 받기]를 써 주세요. 정정은 PIN이 필요해요.
          </p>
          {[...props.consents.history].reverse().map((r) => {
            const [chip, bg, fg] = r.correctsConsentId ? ['정정', '#EEF2EE', '#3F6150'] : statusChip(r.agreed, Boolean(r.withdrawalPath));
            return (
              <div key={r.consentId} className="hrow">
                <span className="meta num">{formatDateTime(r.recordedAt)}</span>
                <strong>{LABEL[r.item]}</strong>
                <span className="row" style={{ gap: 6 }}>
                  <span className="chip" style={{ background: bg, color: fg }}>
                    {chip}
                  </span>
                  {r.correctsConsentId && <span className="meta">→ {r.agreed ? '동의' : '미동의'}</span>}
                </span>
                <span className="meta">
                  {r.staff?.name ?? '고객 직접'}
                  {r.method && ` · ${METHOD_LABEL[r.method] ?? r.method}`}
                  {r.withdrawalPath && ` · ${r.withdrawalPath === 'UNSUBSCRIBE' ? '수신 거부' : '직접 철회'}`}
                </span>
                <span>
                  {r.item !== 'PRIVACY' &&
                    latestIds.has(r.consentId) &&
                    (correcting === r.consentId ? (
                      <span className="row" style={{ gap: 8 }}>
                        <span className="meta">{r.agreed ? '미동의' : '동의'}로 바로잡을까요?</span>
                        <button type="button" className="lk" disabled={busy} onClick={() => correct(r)}>
                          바로잡기
                        </button>
                        <button type="button" className="lk" style={{ color: '#574B46' }} onClick={() => setCorrecting(null)}>
                          취소
                        </button>
                      </span>
                    ) : (
                      <button type="button" className="lk" onClick={() => setCorrecting(r.consentId)}>
                        정정
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
