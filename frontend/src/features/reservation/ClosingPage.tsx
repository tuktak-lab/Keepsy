import { useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api } from '@/lib/api';
import { CHANNEL_LABEL, DOW_LABEL, dayIndex, formatMinutes, seoulParts, type Channel } from '@/lib/calendar';
import {
  PAYMENT_METHODS,
  asksMenuExclusion,
  canExcludeFromCycle,
  parseAmount,
  sumPrices,
  toClosingRequest,
  validateClosing,
  type ClosingService,
  type PaymentMethod,
} from '@/lib/closing';
import { formatWon } from '@/lib/format';
import { detailsText } from '@/lib/reservationForm';
import { ConsentRequests, PhotoPicker, uploadPhotos } from './ClosingExtras';
import { CustomerSearch, type CustomerPick } from './CustomerSearch';
import styleText from './SchedulePage.css?inline';

// 시술 마감 카드 (FR-PAY-04 · FR-VIS-05). 경로: /reservations/closing?id=
// 저장 한 번으로 결제 · 방문 생성 · 예약 완료가 함께 처리된다. 이미 완료된 예약은 추가 결제 · 메모만 더한다 (E4).
// 고객 없는 예약은 이 카드 안에서 고객을 연결하거나 「고객 없음」으로 마감한다 (E3).
// 시술마다 [이번 시술 주기 계산 제외]를 체크할 수 있고, 「일반」 메뉴라면 메뉴 전체를 제외할지 묻는다 (FR-VIS-05).
// API: GET · POST /api/reservations/{id}/closing, PUT …/customer, PATCH /api/service-menus/{id} (docs CONTRACTS 6장)
// 동의 받기(POST /api/customers/{id}/consents)는 누를 때 바로 저장하고, 사진은 마감 저장 응답의 방문 ID로 한 장씩 올린다 (F9).
// 사진 일부가 실패해도 마감은 이미 저장됐으므로 마감을 다시 보내지 않는다 (추가 결제가 두 번 생기지 않게). 실패한 사진만 다시 올린다.

type StaffRef = { id: number; name: string };
type Menu = { id: number; name: string; price: number; durationMinutes: number; predictionAttribute: string };
type Closing = {
  reservationId: number;
  status: 'CONFIRMED' | 'COMPLETED';
  servicesConfirmRequired: boolean;
  visitId: number | null;
  customer: { id: number; name: string | null } | null;
  services: { serviceMenuId: number; name: string; price: number; predictionAttribute: string; staff: StaffRef; cycleExcluded: boolean }[];
  photoAllowed: boolean;
  consentsToRequest: string[];
};
/** 마감은 저장됐는데 사진 일부를 못 올린 상태 */
type PhotoRetry = { visitId: number; failed: { file: File; reason: string }[]; busy: boolean };
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
  const sending = useRef(false); // 같은 틱의 연속 제출 차단 (setBusy는 다음 렌더에야 버튼을 막는다). 서버 멱등성은 아님 (#352)
  const [reload, setReload] = useState(0);
  const [noCustomer, setNoCustomer] = useState(false); // 고객 없는 예약을 「고객 없음」으로 마감 (E3)
  const [ask, setAsk] = useState<{ menuId: number; error: string; busy: boolean } | null>(null); // 「앞으로 이 메뉴를 모두 제외할까요?」 (FR-VIS-05)
  const [agreed, setAgreed] = useState<string[]>([]); // 이 카드에서 방금 받은 동의 (목록에서 빼고, 사진 동의면 사진 칸을 연다)
  const [photos, setPhotos] = useState<File[]>([]);
  const [retry, setRetry] = useState<PhotoRetry | null>(null);

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
        setRows(closing.services.map((s) => ({ serviceMenuId: s.serviceMenuId, staffId: s.staff.id, cycleExcluded: s.cycleExcluded })));
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
  }, [id, validId, reload]);

  const current = loaded?.id === id ? loaded : null;
  const closing = current?.closing;
  const completed = closing?.status === 'COMPLETED';
  const hasCustomer = closing?.customer != null;
  const needsCustomer = closing !== undefined && !completed && !hasCustomer; // 고객 없는 확정 예약: 연결하거나 「고객 없음」으로 마감
  const needsChoice = needsCustomer && !noCustomer; // 아직 둘 중 하나를 고르지 않음 → 폼을 열지 않는다
  const excludable = hasCustomer && !completed; // 주기 계산 제외는 고객이 있는 마감에서만 (E3)
  // 동의 받기 · 사진은 고객이 있는 마감에서만 (E3). 사진은 사진 동의가 있을 때만 (FR-CUS-08)
  const consentItems = hasCustomer ? (closing?.consentsToRequest ?? []).filter((c) => !agreed.includes(c)) : [];
  const photoAllowed = hasCustomer && (!!closing?.photoAllowed || agreed.includes('PHOTO'));

  // 이름 · 가격 · 예측 속성: 새로 더한 시술은 메뉴 목록에서, 처음 채워진 시술은 마감 카드 응답에서
  const info = (menuId: number) => {
    const m = menus.find((x) => x.id === menuId);
    const c = closing?.services.find((x) => x.serviceMenuId === menuId);
    return {
      name: m?.name ?? c?.name ?? `시술 ${menuId}`,
      price: m?.price ?? c?.price ?? 0,
      attribute: m?.predictionAttribute ?? c?.predictionAttribute ?? 'NORMAL',
    };
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
  const setExcluded = (menuId: number, cycleExcluded: boolean) => setRows((cur) => cur.map((r) => (r.serviceMenuId === menuId ? { ...r, cycleExcluded } : r)));

  // [이번 시술 주기 계산 제외] 체크 (FR-VIS-05): 「일반」 메뉴에 체크하면 메뉴 전체를 제외할지 먼저 묻는다
  const toggleExcluded = (menuId: number, checked: boolean) => {
    setError('');
    if (checked && asksMenuExclusion(info(menuId).attribute)) setAsk({ menuId, error: '', busy: false });
    else setExcluded(menuId, checked);
  };
  // [예]: 메뉴의 예측 속성을 「주기 계산 제외」로 바꾼다. 매장 설정 권한이라 원장 PIN이 필요하다 (api.ts가 PIN 창을 띄움)
  async function excludeWholeMenu() {
    if (!ask) return;
    setAsk({ ...ask, busy: true, error: '' });
    try {
      await api(`/api/service-menus/${ask.menuId}`, { method: 'PATCH', body: { predictionAttribute: 'EXCLUDE' }, silent: true });
      setMenus((cur) => cur.map((m) => (m.id === ask.menuId ? { ...m, predictionAttribute: 'EXCLUDE' } : m)));
      setExcluded(ask.menuId, true);
      setAsk(null);
      toast(`앞으로 ${info(ask.menuId).name} 메뉴는 주기 계산에서 제외돼요.`);
    } catch (err) {
      setAsk({ ...ask, busy: false, error: err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요' });
    }
  }
  // [아니요]: 이번 방문만 제외한다
  const excludeThisVisitOnly = () => {
    if (!ask) return;
    setExcluded(ask.menuId, true);
    setAsk(null);
  };

  // 고객 없는 예약에 고객을 연결한 뒤 카드를 다시 읽는다 (예약 제한 고객은 PIN 없이 연결하고 안내만, FR-CUS-14 · E3)
  async function linkCustomer(c: CustomerPick) {
    setError('');
    try {
      const res = await api<{ notice: string | null }>(`/api/reservations/${id}/customer`, { method: 'PUT', body: { customerId: c.id }, silent: true });
      toast(`${c.name} 고객을 연결했어요.`);
      if (res?.notice) toast(res.notice, 'warn');
      setNoCustomer(false);
      setReload((n) => n + 1);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const values = { services: rows, amount: shownAmount, method, memo };
    const mode = { completed: !!completed, hasCustomer, noCustomer: needsCustomer && noCustomer };
    const problem = validateClosing(values, mode);
    if (problem) return setError(problem);
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setError('');
    try {
      const res = await api<{ visitId: number }>(`/api/reservations/${id}/closing`, { method: 'POST', body: toClosingRequest(values, mode), silent: true });
      toast(completed ? '추가 결제를 저장했어요.' : '시술 마감을 저장했어요.');
      const toUpload = photoAllowed ? photos : [];
      if (toUpload.length > 0 && res?.visitId) {
        const failed = await uploadPhotos(res.visitId, toUpload);
        if (failed.length > 0) {
          // 마감은 저장됐다. 마감을 다시 보내지 않고 실패한 사진만 다시 올리게 한다
          setRetry({ visitId: res.visitId, failed, busy: false });
          return;
        }
        toast(`사진 ${toUpload.length}장을 올렸어요.`);
      }
      router.push(`/reservations/detail?id=${id}`);
    } catch (err) {
      setError(err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : '잠시 후 다시 시도해 주세요');
      // 실패했을 때만 다시 제출할 수 있다. 성공하면 화면을 떠날 때까지 버튼을 막아 둔다
      sending.current = false;
      setBusy(false);
    }
  }

  async function retryPhotos() {
    if (!retry) return;
    setRetry({ ...retry, busy: true });
    const failed = await uploadPhotos(retry.visitId, retry.failed.map((f) => f.file));
    if (failed.length === 0) {
      toast('남은 사진을 모두 올렸어요.');
      router.push(`/reservations/detail?id=${id}`);
    } else setRetry({ ...retry, failed, busy: false });
  }

  const at = summary ? seoulParts(summary.startAt) : null;
  const backHref = `/reservations/detail?id=${validId ? id : ''}`;
  const unchosen = menus.filter((m) => !rows.some((r) => r.serviceMenuId === m.id));
  const amountOk = parseAmount(shownAmount);

  return (
    <>
      <div className="wrap">
        <header className="head">
          <div>
            <h1 className="h1">{completed ? '추가 결제' : '시술 마감'}</h1>
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
          ) : retry ? (
            <div className="detail">
              <div className="box warn" role="alert">
                <p>
                  <b>{completed ? '추가 결제는' : '시술 마감은'} 저장됐어요.</b> 사진 {retry.failed.length}장을 올리지 못했어요. 마감은 다시 저장하지 않아도 돼요.
                </p>
                <ul style={{ margin: '8px 0 0', paddingLeft: 18 }}>
                  {retry.failed.map((f, i) => (
                    <li key={`${f.file.name}-${i}`} className="meta">
                      {f.file.name} · {f.reason}
                    </li>
                  ))}
                </ul>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
                <AppLink href={backHref} className="btn">
                  사진 없이 끝내기
                </AppLink>
                <button type="button" className="btn btn-p" disabled={retry.busy} onClick={() => void retryPhotos()}>
                  {retry.busy ? '올리는 중…' : `${retry.failed.length}장 다시 올리기`}
                </button>
              </div>
            </div>
          ) : (
            <form className="detail" onSubmit={submit}>
              <dl className="d-dl">
                <dt>고객</dt>
                <dd className="strong">
                  {closing.customer ? (closing.customer.name ?? '이름 없음') : needsCustomer && noCustomer ? '고객 없음으로 마감' : '고객 정보 없음'}
                </dd>
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

              {needsChoice ? (
                <>
                  <div className="box warn" role="note">
                    <p>고객 없는 예약이에요. 시술 마감 전에 고객을 연결하거나, 손님이 동의를 거부했다면 「고객 없음」으로 마감해 주세요.</p>
                  </div>
                  <div>
                    <span className="lbl">고객 연결</span>
                    <CustomerSearch onPick={(c) => void linkCustomer(c)} />
                  </div>
                  <div className="box">
                    <p>
                      <b>고객 없음으로 마감</b> — 실제 시술 · 담당 스탭 · 금액 · 결제 수단만 기록하고, 사진 · 메모 · 동의 받기 · 주기 계산은 없어요.
                    </p>
                    <button type="button" className="btn btn-sm" style={{ marginTop: 8 }} onClick={() => setNoCustomer(true)}>
                      고객 없음으로 마감하기
                    </button>
                  </div>
                </>
              ) : (
                <>
                  {needsCustomer && noCustomer ? (
                    <div className="box" role="note">
                      <p>
                        고객 없음으로 마감해요. 메모와 주기 계산은 기록하지 않아요.{' '}
                        <button type="button" className="lk" onClick={() => setNoCustomer(false)}>
                          고객 연결하기
                        </button>
                      </p>
                    </div>
                  ) : null}
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
                        const { name, price, attribute } = info(r.serviceMenuId);
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
                            {/* 주기 계산 제외 (FR-VIS-05): 고객이 있는 마감에서만. 이미 항상 제외인 메뉴는 체크가 필요 없다 */}
                            {excludable && canExcludeFromCycle(attribute) ? (
                              <label className="chk svc-opt">
                                <input type="checkbox" checked={!!r.cycleExcluded} onChange={(e) => toggleExcluded(r.serviceMenuId, e.target.checked)} />
                                <span>이번 시술 주기 계산 제외</span>
                              </label>
                            ) : null}
                            {hasCustomer && !completed && !canExcludeFromCycle(attribute) ? <span className="meta svc-opt">주기 계산에서 항상 제외되는 메뉴예요</span> : null}
                            {completed && r.cycleExcluded ? <span className="meta svc-opt">이번 시술은 주기 계산에서 제외됐어요</span> : null}
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

                  {closing.customer ? (
                    <ConsentRequests customerId={closing.customer.id} items={consentItems} onAgreed={(item) => setAgreed((cur) => [...cur, item])} />
                  ) : null}

                  {photoAllowed ? (
                    <PhotoPicker files={photos} onChange={setPhotos} disabled={busy} />
                  ) : hasCustomer ? (
                    <p className="meta">사진 동의가 없는 고객이라 사진을 올릴 수 없어요. 위 「동의 받기」에서 시술 사진 동의를 받으면 사진 칸이 열려요.</p>
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
                {!needsChoice ? (
                  <button type="submit" className="btn btn-p" disabled={busy}>
                    {busy ? '저장 중…' : completed ? '추가 결제 저장' : '시술 마감 저장'}
                  </button>
                ) : null}
              </div>
            </form>
          )}
        </section>
      </div>

      {ask ? (
        <div className="overlay">
          <div className="card modal" role="dialog" aria-modal="true" aria-label="주기 계산 제외 확인">
            <h2 className="h2" style={{ fontSize: '19px' }}>
              앞으로 {info(ask.menuId).name} 메뉴를 모두 주기 계산에서 제외할까요?
            </h2>
            <p className="meta">
              [예]를 고르면 메뉴의 예측 속성이 「주기 계산 제외」로 바뀌어요. 매장 설정 권한(원장 PIN)이 필요해요. [아니요]를 고르면 이번 방문만 제외해요.
            </p>
            {ask.error ? (
              <span className="err" role="alert">
                {ask.error}
              </span>
            ) : null}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
              <button type="button" className="btn" disabled={ask.busy} onClick={excludeThisVisitOnly}>
                아니요 (이번 방문만)
              </button>
              <button type="button" className="btn btn-p" disabled={ask.busy} onClick={() => void excludeWholeMenu()}>
                {ask.busy ? '변경 중…' : '예 (메뉴 전체 제외)'}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
