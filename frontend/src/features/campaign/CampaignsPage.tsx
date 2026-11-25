import { useEffect, useState } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { PinGate } from '@/components/common/PinGate';
import { CountUp } from '@/components/common/CountUp';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import { toast } from '@/components/common/Toast';
import { ApiError, api, type Page } from '@/lib/api';
import { CAMPAIGN_STATUS_LABEL, SEND_STATUS_LABEL } from '@/lib/campaignResult';
import { formatDateTime } from '@/lib/format';
import { CATEGORY_LABEL, type Category } from '@/lib/menus';
import styleText from './CampaignsPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';

// 캠페인 목록 (FR-CMP-10). 경로: /campaigns. 기능 키 CAMPAIGN_VIEW (PinGate, 권한 검사는 API).
// API (docs CONTRACTS 6장 CMP): GET /api/campaigns (목록) · GET /api/campaigns/{id} (상세 · 고객별 발송 결과)
// 고객별 결과 전체 · 퍼널 · 대조군 비교는 캠페인 결과 화면(/campaigns/detail?id=)에서 본다. 작성은 /campaigns/new.

type Campaign = {
  id: number;
  name: string;
  category: Category | null;
  messageKind: 'AD' | 'INFO';
  status: string;
  targetCount: number;
  controlCount: number;
  scheduledAt: string | null;
  sentAt: string | null;
  cancelReason: string | null;
};
type Message = {
  customerId: number;
  customerName: string | null;
  customerDeleted: boolean;
  phone: string | null;
  controlGroup: boolean;
  sendStatus: string | null;
  failureReason: string | null;
};
type Detail = Campaign & {
  controlPercent: number;
  reviewedBy: { id: number; name: string } | null;
  reviewedAt: string | null;
  canceledAt: string | null;
  messages: Page<Message>;
};

const LIST_SIZE = 100;
const RESULT_SIZE = 100;
const FAILURES_SHOWN = 5;
const POLL_MS = 3000;
const KIND_LABEL: Record<string, string> = { AD: '광고', INFO: '안내' };
const CANCEL_LABEL: Record<string, string> = { USER_CANCELED: '사용자 취소', DAILY_LIMIT_EXCEEDED: '일 발송 한도 초과' };
// 상태 칩 [배경, 글자]
const STATUS_CHIP: Record<string, [string, string]> = {
  DRAFT: ['#F5EBD3', '#6B4509'],
  SCHEDULED: ['#F1E4E0', '#683945'],
  SENDING: ['#EEF2EE', '#3F6150'],
  SENT: ['#F9EBE8', '#7A4450'],
  CANCELED: ['#F1ECE9', '#6B605B'],
};
const unit = (u: string) => (n: number) => n + u;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : '잠시 후 다시 열어 주세요');

/** 'YYYY-MM' (서울) */
const seoulMonth = (d: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit' }).format(d);

/** 목록 한 줄의 일정 글자 */
function scheduleText(c: Campaign): string {
  if (c.status === 'CANCELED') return `취소${c.cancelReason ? ` · ${CANCEL_LABEL[c.cancelReason] ?? c.cancelReason}` : ''}`;
  if (c.sentAt) return `${formatDateTime(c.sentAt)} 발송`;
  if (c.scheduledAt) return `${formatDateTime(c.scheduledAt)} 예약`;
  return '발송 전';
}

function Chip(props: { status: string }) {
  const [bg, fg] = STATUS_CHIP[props.status] ?? STATUS_CHIP.CANCELED;
  return (
    <span className="cp-chip" style={{ background: bg, color: fg }}>
      {CAMPAIGN_STATUS_LABEL[props.status] ?? props.status}
    </span>
  );
}

function Body() {
  const [items, setItems] = useState<Campaign[] | null>(null);
  const [total, setTotal] = useState(0);
  const [listError, setListError] = useState('');
  const [sel, setSel] = useState<number | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [detailError, setDetailError] = useState('');
  // 예약 취소 뒤 목록 · 요약을 다시 받는다
  const [version, setVersion] = useState(0);

  useEffect(() => {
    api<Page<Campaign>>(`/api/campaigns?page=0&size=${LIST_SIZE}`, { silent: true })
      .then((p) => {
        setItems(p.items);
        setTotal(p.totalElements);
        setSel((cur) => cur ?? p.items[0]?.id ?? null);
      })
      .catch((err) => setListError(errorText(err)));
  }, [version]);

  useEffect(() => {
    if (sel === null) return;
    let alive = true;
    api<Detail>(`/api/campaigns/${sel}?page=0&size=${RESULT_SIZE}`, { silent: true })
      .then((d) => alive && (setDetail(d), setDetailError('')))
      .catch((err) => alive && setDetailError(errorText(err)));
    return () => {
      alive = false;
    };
  }, [sel, version]);

  // 발송 중이거나 결과 대기가 남아 있으면 가상 발송기 주기(3초)마다 다시 받는다. 다 끝나면 멈춘다
  const inFlight =
    Boolean(items?.some((c) => c.status === 'SENDING')) || Boolean(detail?.messages.items.some((m) => m.sendStatus === 'PENDING'));
  useEffect(() => {
    if (!inFlight) return;
    const timer = setTimeout(() => setVersion((v) => v + 1), POLL_MS);
    return () => clearTimeout(timer);
  }, [inFlight, version]);

  const thisMonth = seoulMonth(new Date());
  const scheduled = items?.filter((c) => c.status === 'SCHEDULED').length ?? 0;
  const sentThisMonth = items?.filter((c) => c.sentAt && seoulMonth(new Date(c.sentAt)) === thisMonth).length ?? 0;
  const shown = detail && detail.id === sel ? detail : null;

  return (
    <div className="cp-wrap">
      <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
        <div>
          <h1 className="cp-h1">캠페인 관리</h1>
          <p className="cp-sub">만든 캠페인의 상태와 발송 결과를 확인해요. 고객별 결과와 성과는 결과 화면에서 자세히 볼 수 있어요.</p>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <AppLink href="/campaigns/templates" className="cp-btn">템플릿</AppLink>
          <AppLink href="/virtual-phone" className="cp-btn">가상 휴대폰</AppLink>
          <AppLink href="/campaigns/new" className="cp-btn p">+ 캠페인 만들기</AppLink>
        </div>
      </div>

      <section className="cp-kpis cp-card" aria-label="캠페인 현황">
        <div className="cp-kpi"><span>예약 발송 대기</span><b className="num"><CountUp value={scheduled} format={unit('건')} /></b><em>발송 전까지 취소 가능</em></div>
        <div className="cp-kpi"><span>이번 달 발송</span><b className="num"><CountUp value={sentThisMonth} format={unit('건')} /></b><em>발송한 캠페인 수 · 가상 발송</em></div>
        <div className="cp-kpi"><span>캠페인 수</span><b className="num"><CountUp value={total} format={unit('건')} /></b><em>작성 중 · 예약 · 완료 · 취소 포함</em></div>
        <div className="cp-kpi"><span>성과 확인</span><b style={{ fontSize: 16, paddingTop: 8 }}><AppLink href="/analytics" style={{ color: '#7A4450' }}>성과 분석 보기 →</AppLink></b><em>귀속 14일 기준</em></div>
      </section>

      {listError ? (
        <div className="cp-warn" role="alert">{listError}</div>
      ) : (
        <div className="cp-grid">
          <section className="cp-card cp-list" aria-label="캠페인 목록">
            <h2>캠페인 목록</h2>
            {items === null && <p className="cp-note" style={{ padding: '0 10px' }}>불러오는 중…</p>}
            {items?.length === 0 && <p className="cp-note" style={{ padding: '0 10px' }}>아직 만든 캠페인이 없어요.</p>}
            {items?.map((x) => (
              <button type="button" className="cp-item" key={x.id} aria-pressed={sel === x.id} onClick={() => setSel(x.id)}>
                <b>{x.name}</b>
                <span className="cp-meta num">
                  {x.category ? CATEGORY_LABEL[x.category] : '동의 재확인'} · {KIND_LABEL[x.messageKind] ?? x.messageKind} · 대상 {x.targetCount}명
                </span>
                <span className="cp-row2">
                  <span className="cp-meta num">{scheduleText(x)}</span>
                  <Chip status={x.status} />
                </span>
              </button>
            ))}
            {items && items.length < total && <p className="cp-note" style={{ padding: '6px 10px 0' }}>최근 {items.length}개만 보여요.</p>}
          </section>

          <section className="cp-card cp-main" aria-label="캠페인 상세">
            {items?.length === 0 ? (
              <div className="cp-ok">
                대상 선정부터 발송까지 단계별로 진행해요.{' '}
                <AppLink href="/campaigns/new" style={{ color: '#7A4450', fontWeight: 600 }}>새 캠페인 만들기 →</AppLink>
              </div>
            ) : detailError ? (
              <div className="cp-warn" role="alert">{detailError}</div>
            ) : shown ? (
              <div className="kp-slide cp-main-in" key={shown.id} style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
                <Summary campaign={shown} onChanged={() => setVersion((v) => v + 1)} />
              </div>
            ) : (
              items && items.length > 0 && <p className="cp-note">불러오는 중…</p>
            )}
          </section>
        </div>
      )}
      <p className="cp-foot">마케팅 수신 동의 고객에게만 발송해요. 발송은 검토 체크가 필요하고, MVP는 가상 발송이라 실제 문자는 나가지 않아요.</p>
    </div>
  );
}

/** 선택한 캠페인 요약과 발송 결과 (FR-CMP-10). 고객별 결과는 앞 RESULT_SIZE명까지 센다 */
function Summary({ campaign: c, onChanged }: { campaign: Detail; onChanged: () => void }) {
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const rows = c.messages.items.filter((m) => !m.controlGroup);
  const count = (s: string) => rows.filter((m) => m.sendStatus === s).length;
  const failures = rows.filter((m) => m.sendStatus === 'FAILED');
  const partial = c.messages.totalElements > c.messages.items.length;
  const started = c.messages.totalElements > 0;

  // 작성 중 · 예약 발송 대기 캠페인 취소 (F7, POST /api/campaigns/{id}/cancel, CAMPAIGN_SEND)
  async function cancel() {
    setBusy(true);
    setError('');
    try {
      await api(`/api/campaigns/${c.id}/cancel`, { method: 'POST', silent: true });
      toast('캠페인을 취소했어요', 'warn');
      onChanged();
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  }

  return (
    <>
      <div className="cp-row2">
        <h2 style={{ fontSize: 20, fontWeight: 700 }}>{c.name}</h2>
        <Chip status={c.status} />
      </div>
      <span className="cp-meta">
        {c.category ? CATEGORY_LABEL[c.category] : '동의 재확인 안내'} · {KIND_LABEL[c.messageKind] ?? c.messageKind}
        {c.reviewedBy && ` · 검토 ${c.reviewedBy.name}${c.reviewedAt ? ` (${formatDateTime(c.reviewedAt)})` : ''}`}
        <br />
        <span className="num">{scheduleText(c)}</span>
      </span>

      <div className="cp-stats">
        <div className="cp-stat"><b className="num">{c.targetCount}명</b><span>대상 (대조군 포함)</span></div>
        <div className="cp-stat"><b className="num">{c.controlCount}명</b><span>대조군 {c.controlPercent}%</span></div>
        <div className="cp-stat"><b className="num">{c.targetCount - c.controlCount}명</b><span>발송 대상</span></div>
      </div>

      {c.status === 'CANCELED' && c.canceledAt && (
        <div className="cp-ok">
          {formatDateTime(c.canceledAt)}에 취소됐어요{c.cancelReason ? ` · ${CANCEL_LABEL[c.cancelReason] ?? c.cancelReason}` : ''}.
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <h3 style={{ fontSize: 15.5, fontWeight: 600 }}>발송 결과</h3>
        {!started ? (
          <p className="cp-note">아직 발송을 시작하지 않았어요.</p>
        ) : (
          <>
            <div className="cp-excl">
              <span className="cp-ex">{SEND_STATUS_LABEL.SENT} <b className="num">{count('SENT')}</b></span>
              <span className="cp-ex">{SEND_STATUS_LABEL.FAILED} <b className="num">{count('FAILED')}</b></span>
              <span className="cp-ex">{SEND_STATUS_LABEL.PENDING} <b className="num">{count('PENDING')}</b></span>
            </div>
            {partial && <span className="cp-note">고객 ID 순 앞 {c.messages.items.length}명 기준이에요. 전체는 결과 화면에서 볼 수 있어요.</span>}
            {count('PENDING') > 0 && <span className="cp-note">가상 발송기가 몇 초 안에 결과를 정해요. 화면이 알아서 다시 불러와요.</span>}
            {failures.length > 0 && (
              <table className="cp-tbl">
                <thead>
                  <tr>
                    <th scope="col" style={{ textAlign: 'left' }}>고객</th>
                    <th scope="col" style={{ textAlign: 'left' }}>번호</th>
                    <th scope="col" style={{ textAlign: 'left' }}>실패 사유</th>
                  </tr>
                </thead>
                <tbody>
                  {failures.slice(0, FAILURES_SHOWN).map((m) => (
                    <tr key={m.customerId}>
                      <td>{m.customerDeleted ? '삭제된 고객' : m.customerName}</td>
                      <td className="num">{m.phone ?? '—'}</td>
                      <td>{m.failureReason ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {failures.length > FAILURES_SHOWN && <span className="cp-note">실패 {failures.length - FAILURES_SHOWN}건 더 있어요.</span>}
          </>
        )}
      </div>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {error && <div className="cp-warn" role="alert" style={{ width: '100%' }}>{error}</div>}
        {c.status === 'DRAFT' && <AppLink href={`/campaigns/new?id=${c.id}`} className="cp-btn p">이어서 작성</AppLink>}
        {(c.status === 'DRAFT' || c.status === 'SCHEDULED') &&
          (confirmCancel ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="cp-note">{c.status === 'SCHEDULED' ? '예약 발송을 취소할까요?' : '이 캠페인을 취소할까요?'}</span>
              <button type="button" className="cp-btn" style={{ color: '#8F2F2F' }} disabled={busy} onClick={cancel}>취소하기</button>
              <button type="button" className="cp-btn" disabled={busy} onClick={() => setConfirmCancel(false)}>그대로 두기</button>
            </span>
          ) : (
            <button type="button" className="cp-btn" onClick={() => setConfirmCancel(true)}>{c.status === 'SCHEDULED' ? '예약 발송 취소' : '캠페인 취소'}</button>
          ))}
        {c.status !== 'DRAFT' && <AppLink href={`/campaigns/detail?id=${c.id}`} className="cp-btn p">결과 자세히 보기</AppLink>}
      </div>
    </>
  );
}

export default function CampaignsPage() {
  useStyleSheet('CampaignsPage', styleText);
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', minHeight: '1100px' }}>
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="campaigns" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb="파트너 센터 / 캠페인 관리" />
        <PinGate id="campaigns" title="캠페인 관리">
          <Body />
        </PinGate>
      </main>
    </div>
  );
}
