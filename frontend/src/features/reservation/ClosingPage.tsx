import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api } from '@/lib/api';
import { CHANNEL_LABEL, DOW_LABEL, dayIndex, formatMinutes, seoulParts, type Channel } from '@/lib/calendar';
import { PAYMENT_METHODS, parseAmount, sumPrices, toClosingRequest, validateClosing, type ClosingService, type PaymentMethod } from '@/lib/closing';
import { formatWon } from '@/lib/format';
import { detailsText } from '@/lib/reservationForm';
import styleText from './SchedulePage.css?inline';

// 시술 마감 카드 (FR-PAY-04). 경로: /reservations/closing?id=
// 저장 한 번으로 결제 · 방문 생성 · 예약 완료가 함께 처리된다. 이미 완료된 예약은 추가 결제 · 메모만 더한다 (E4).
// API: GET · POST /api/reservations/{id}/closing (docs CONTRACTS 6장 「시술 마감 · 결제 (PAY)」)
// 이 화면에서 아직 하지 않는 것: 고객 없는 예약의 카드 안 연결 · 고객 없음 마감, 주기 계산 제외(FR-VIS-05) → 다음 PR.
// 사진 올리기와 동의 받기는 해당 API(#46 · #48)가 생기면 붙인다.

type StaffRef = { id: number; name: string };
type Menu = { id: number; name: string; price: number; durationMinutes: number };
type Closing = {
  reservationId: number;
  status: 'CONFIRMED' | 'COMPLETED';
  servicesConfirmRequired: boolean;
  visitId: number | null;
  customer: { id: number; name: string | null } | null;
  services: { serviceMenuId: number; name: string; price: number; staff: StaffRef }[];
};
type Summary = { startAt: string; minutes: number; channel: Channel; staff: StaffRef };
type Loaded = { id: number; closing?: Closing; error?: 'notfound' | 'conflict' | 'error'; message?: string };

export default function ClosingPage() {
  useStyleSheet('SchedulePage', styleText);

  const router = useRouter();
  const params = useSearchParams();
  const id = Number(params.get('id'));
  const validId = Number.isInteger(id) && id > 0;

  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [staff, setStaff] = useState<StaffRef[]>([]);
  const [menus, setMenus] = useState<Menu[]>([]);
  const [rows, setRows] = useState<ClosingService[]>([]);
  const [amount, setAmount] = useState('');
  const [amountTouched, setAmountTouched] = useState(false); // 금액을 직접 고치기 전에는 시술 가격 합계를 따른다
  const [method, setMethod] = useState<PaymentMethod>('CARD');
  const [memo, setMemo] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // 시술별 담당 스탭 · 시술 추가에 쓰는 목록
  useEffect(() => {
    let cancelled = false;
    api<StaffRef[]>('/api/staff')
      .then((list) => !cancelled && setStaff(list))
      .catch(() => {});
    api<Menu[]>('/api/service-menus')
      .then((list) => !cancelled && setMenus(list))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  // 마감 카드 값 (예정 시술로 미리 채워진 상태) + 위쪽 요약용 예약 정보
  useEffect(() => {
    if (!validId) return;
    let cancelled = false;
    api<Closing>(`/api/reservations/${id}/closing`, { silent: true })
      .then((closing) => {
        if (cancelled) return;
        setRows(closing.services.map((s) => ({ serviceMenuId: s.serviceMenuId, staffId: s.staff.id })));
        setLoaded({ id, closing });
      })
      .catch((err) => {
        if (cancelled) return;
        const code = err instanceof ApiError ? err.code : '';
        setLoaded({ id, error: code === 'not_found' ? 'notfound' : code === 'conflict' ? 'conflict' : 'error', message: err instanceof ApiError ? err.message : undefined });
      });
    api<Summary>(`/api/reservations/${id}`, { silent: true })
      .then((d) => !cancelled && setSummary(d))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [id, validId]);

  const current = loaded?.id === id ? loaded : null;
  const closing = current?.closing;
  const completed = closing?.status === 'COMPLETED';
  const hasCustomer = closing?.customer != null;
  const needsCustomer = closing !== undefined && !completed && !hasCustomer; // 고객 없는 예약은 먼저 고객을 연결해야 한다 (다음 PR에서 이 카드 안에서)

  // 이름 · 가격: 새로 더한 시술은 메뉴 목록에서, 처음 채워진 시술은 마감 카드 응답에서
  const info = (menuId: number) => {
    const m = menus.find((x) => x.id === menuId);
    const c = closing?.services.find((x) => x.serviceMenuId === menuId);
    return { name: m?.name ?? c?.name ?? `시술 ${menuId}`, price: m?.price ?? c?.price ?? 0 };
  };
  const shownAmount = amountTouched ? amount : completed ? '' : String(sumPrices(rows.map((r) => info(r.serviceMenuId).price)));

  const addService = (menuId: number) => {
    setError('');
    setRows((cur) => [...cur, { serviceMenuId: menuId, staffId: summary?.staff.id ?? staff[0]?.id ?? 0 }]);
  };
  const removeService = (menuId: number) => {
    setError('');
    setRows((cur) => cur.filter((r) => r.serviceMenuId !== menuId));
  };
  const changeStaff = (menuId: number, staffId: number) => setRows((cur) => cur.map((r) => (r.serviceMenuId === menuId ? { ...r, staffId } : r)));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const values = { services: rows, amount: shownAmount, method, memo };
    const mode = { completed: !!completed, hasCustomer };
    const problem = validateClosing(values, mode);
    if (problem) return setError(problem);
    setBusy(true);
    setError('');
    try {
      await api(`/api/reservations/${id}/closing`, { method: 'POST', body: toClosingRequest(values, mode), silent: true });
      toast(completed ? '추가 결제를 저장했어요.' : '시술 마감을 저장했어요.');
      router.push(`/reservations/detail?id=${id}`);
    } catch (err) {
      setError(err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : '잠시 후 다시 시도해 주세요');
    } finally {
      setBusy(false);
    }
  }

  const at = summary ? seoulParts(summary.startAt) : null;
  const backHref = `/reservations/detail?id=${validId ? id : ''}`;
  const unchosen = menus.filter((m) => !rows.some((r) => r.serviceMenuId === m.id));
  const amountOk = parseAmount(shownAmount);

  return (
    <div className="shell">
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="schedule" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: '0', display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb="파트너 센터 / 예약 · 일정 / 시술 마감" />
        <div className="wrap">
          <header className="head">
            <div>
              <h1 className="h1 serif">{completed ? '추가 결제' : '시술 마감'}</h1>
              <p className="sub">
                <AppLink href={backHref} className="p-link">
                  ‹ 예약 상세로
                </AppLink>
              </p>
            </div>
          </header>

          <section className="card detail-card" aria-label="시술 마감 카드">
            {!validId || current?.error === 'notfound' ? (
              <p className="meta">찾을 수 없는 예약이에요. 예약 캘린더에서 다시 골라 주세요.</p>
            ) : current?.error === 'conflict' ? (
              <p className="meta">{current.message ?? '취소 · 노쇼한 예약은 시술 마감을 할 수 없어요.'}</p>
            ) : current?.error === 'error' ? (
              <p className="meta">시술 마감 카드를 불러오지 못했어요. 잠시 후 다시 열어 주세요.</p>
            ) : !closing ? (
              <p className="meta">불러오는 중…</p>
            ) : (
              <form className="detail" onSubmit={submit}>
                <dl className="d-dl">
                  <dt>고객</dt>
                  <dd className="strong">{closing.customer ? (closing.customer.name ?? '이름 없음') : '고객 정보 없음'}</dd>
                  {summary && at ? (
                    <>
                      <dt>예약</dt>
                      <dd className="num">
                        {Number(at.date.slice(5, 7))}월 {Number(at.date.slice(8, 10))}일 ({DOW_LABEL[dayIndex(at.date)]}) {formatMinutes(at.minutes)} –{' '}
                        {formatMinutes(at.minutes + summary.minutes)} · {CHANNEL_LABEL[summary.channel] ?? summary.channel}
                      </dd>
                    </>
                  ) : null}
                </dl>

                {needsCustomer ? (
                  <div className="box warn" role="note">
                    <p>
                      고객 없는 예약이에요. 시술 마감 전에 고객을 연결해 주세요.{' '}
                      <AppLink href={`/reservations/detail?id=${id}`} className="p-link">
                        예약 상세에서 고객 연결 →
                      </AppLink>
                    </p>
                  </div>
                ) : (
                  <>
                    {completed ? (
                      <div className="box" role="note">
                        <p>이미 마감된 예약이에요. 결제(추가 결제)와 메모만 더할 수 있고, 실제 시술은 방문 수정에서 바꿀 수 있어요.</p>
                      </div>
                    ) : closing.servicesConfirmRequired ? (
                      <div className="box warn" role="note">
                        <p>상담 후 진행 예약이에요. 실제로 한 시술을 확인해 주세요.</p>
                      </div>
                    ) : null}

                    <div>
                      <span className="lbl">실제 시술{completed ? '' : ' (1개 이상)'}</span>
                      <ul className="svc-list">
                        {rows.map((r) => {
                          const { name, price } = info(r.serviceMenuId);
                          return (
                            <li key={r.serviceMenuId} className="svc-row">
                              <span className="svc-name">
                                <b>{name}</b>
                                <span className="num meta">{formatWon(price)}</span>
                              </span>
                              {completed ? (
                                <span className="meta">{staff.find((s) => s.id === r.staffId)?.name ?? closing.services.find((s) => s.serviceMenuId === r.serviceMenuId)?.staff.name}</span>
                              ) : (
                                <>
                                  <select className="inp" aria-label={`${name} 담당 스탭`} value={r.staffId} onChange={(e) => changeStaff(r.serviceMenuId, Number(e.target.value))}>
                                    {staff.map((s) => (
                                      <option key={s.id} value={s.id}>
                                        {s.name}
                                      </option>
                                    ))}
                                  </select>
                                  <button type="button" className="ib" aria-label={`${name} 빼기`} onClick={() => removeService(r.serviceMenuId)}>
                                    ×
                                  </button>
                                </>
                              )}
                            </li>
                          );
                        })}
                      </ul>
                      {!completed && unchosen.length > 0 ? (
                        <div className="pills" style={{ marginTop: 8 }}>
                          {unchosen.map((m) => (
                            <button key={m.id} type="button" className="pill" onClick={() => addService(m.id)}>
                              + {m.name}
                            </button>
                          ))}
                        </div>
                      ) : null}
                    </div>

                    <div>
                      <span className="lbl">{completed ? '추가 결제 금액' : '결제 금액'}</span>
                      <input
                        className="inp"
                        inputMode="numeric"
                        aria-label="결제 금액"
                        placeholder={completed ? '추가로 받은 금액 (원)' : '0'}
                        value={shownAmount}
                        onChange={(e) => {
                          setError('');
                          setAmountTouched(true);
                          setAmount(e.target.value);
                        }}
                      />
                      {amountOk !== null ? <p className="meta num" style={{ marginTop: 6 }}>{formatWon(amountOk)}</p> : null}
                    </div>

                    <div>
                      <span className="lbl">결제 수단</span>
                      <div className="pills">
                        {PAYMENT_METHODS.map(([value, label]) => (
                          <button key={value} type="button" className="pill" aria-pressed={method === value} onClick={() => setMethod(value)}>
                            {label}
                          </button>
                        ))}
                      </div>
                    </div>

                    {hasCustomer ? (
                      <div>
                        <span className="lbl">방문 메모</span>
                        <textarea className="inp" maxLength={1000} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="다음 방문에 참고할 내용" />
                      </div>
                    ) : null}
                  </>
                )}

                {error ? (
                  <span className="err" role="alert">
                    {error}
                  </span>
                ) : null}

                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
                  <AppLink href={backHref} className="btn">
                    돌아가기
                  </AppLink>
                  {!needsCustomer ? (
                    <button type="submit" className="btn btn-p" disabled={busy}>
                      {busy ? '저장 중…' : completed ? '추가 결제 저장' : '시술 마감 저장'}
                    </button>
                  ) : null}
                </div>
              </form>
            )}
          </section>
        </div>
      </main>
    </div>
  );
}
