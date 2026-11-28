import { useEffect, useRef, useState } from 'react';
import { t } from '@/lib/i18n';
import { useRouter, useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { useIndustries } from '@/hooks/useIndustries';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { ApiError, api } from '@/lib/api';
import { CHANNEL_LABEL, dayIndex, dowLabel, formatMinutes, monthDayText, seoulParts, type Channel } from '@/lib/calendar';
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
  type PaymentRecord,
} from '@/lib/closing';
import { formatWon } from '@/lib/format';
import { pickableMenus } from '@/lib/menus';
import { detailsText } from '@/lib/reservationForm';
import { ConsentRequests, PhotoPicker, uploadPhotos } from './ClosingExtras';
import { PaymentList } from './PaymentList';
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
type Menu = { id: number; category: string; name: string; price: number; durationMinutes: number; predictionAttribute: string };
type Closing = {
  reservationId: number;
  status: 'CONFIRMED' | 'COMPLETED';
  servicesConfirmRequired: boolean;
  visitId: number | null;
  customer: { id: number; name: string | null } | null;
  services: { serviceMenuId: number; name: string; price: number; predictionAttribute: string; staff: StaffRef; cycleExcluded: boolean }[];
  photoAllowed: boolean;
  consentsToRequest: string[];
  /** 이 예약으로 등록한 결제 (F76). 확정 예약은 빈 목록 */
  payments: PaymentRecord[];
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
  const [choicesFailed, setChoicesFailed] = useState(false); // 스탭 · 메뉴 · 예약 요약 중 하나라도 못 받음
  const [choicesTry, setChoicesTry] = useState(0);
  const industries = useIndustries(reload + choicesTry); // 꺼 둔 업종의 메뉴는 시술 추가 선택지에서 뺀다 (이미 담긴 시술은 그대로 보인다)
  const [noCustomer, setNoCustomer] = useState(false); // 고객 없는 예약을 「고객 없음」으로 마감 (E3)
  const [ask, setAsk] = useState<{ menuId: number; error: string; busy: boolean } | null>(null); // 「앞으로 이 메뉴를 모두 제외할까요?」 (FR-VIS-05)
  const [agreed, setAgreed] = useState<string[]>([]); // 이 카드에서 방금 받은 동의 (목록에서 빼고, 사진 동의면 사진 칸을 연다)
  const [photos, setPhotos] = useState<File[]>([]);
  const [retry, setRetry] = useState<PhotoRetry | null>(null);

  // 시술별 담당 스탭 · 시술 추가 선택지 · 위쪽 요약용 예약 정보. 이것만 다시 받으면 입력하던 값은 그대로다
  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      api<StaffRef[]>('/api/staff', { silent: true }),
      api<Menu[]>('/api/service-menus', { silent: true }),
      validId ? api<Summary>(`/api/reservations/${id}`, { silent: true }) : Promise.resolve(null),
    ]).then(([s, m, d]) => {
      if (cancelled) return;
      if (s.status === 'fulfilled') setStaff(s.value);
      if (m.status === 'fulfilled') setMenus(m.value);
      if (d.status === 'fulfilled') setSummary(d.value);
      setChoicesFailed([s, m, d].some((r) => r.status === 'rejected'));
    });
    return () => {
      cancelled = true;
    };
  }, [id, validId, reload, choicesTry]);

  // 마감 카드 값 (예정 시술로 미리 채워진 상태)
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
      name: m?.name ?? c?.name ?? t('reservation.closing.serviceFallback', { id: menuId }),
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
      toast(t('reservation.closing.excludedToast', { name: info(ask.menuId).name }));
    } catch (err) {
      setAsk({ ...ask, busy: false, error: err instanceof ApiError ? err.message : t('common.pin.tryLater') });
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
      toast(t('reservation.linkedToast', { name: c.name }));
      if (res?.notice) toast(res.notice, 'warn');
      setNoCustomer(false);
      setReload((n) => n + 1);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t('common.pin.tryLater'));
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
      toast(completed ? t('reservation.closing.savedExtra') : t('reservation.closing.savedClosing'));
      const toUpload = photoAllowed ? photos : [];
      if (toUpload.length > 0 && res?.visitId) {
        const failed = await uploadPhotos(res.visitId, toUpload);
        if (failed.length > 0) {
          // 마감은 저장됐다. 마감을 다시 보내지 않고 실패한 사진만 다시 올리게 한다
          setRetry({ visitId: res.visitId, failed, busy: false });
          return;
        }
        toast(t('reservation.closing.uploaded', { n: toUpload.length }));
      }
      router.push(`/reservations/detail?id=${id}`);
    } catch (err) {
      setError(err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : t('common.pin.tryLater'));
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
      toast(t('reservation.closing.uploadedRest'));
      router.push(`/reservations/detail?id=${id}`);
    } else setRetry({ ...retry, failed, busy: false });
  }

  const at = summary ? seoulParts(summary.startAt) : null;
  const backHref = `/reservations/detail?id=${validId ? id : ''}`;
  const unchosen = pickableMenus(menus, industries).filter((m) => !rows.some((r) => r.serviceMenuId === m.id));
  const amountOk = parseAmount(shownAmount);

  return (
    <>
      <div className="wrap">
        <header className="head">
          <div>
            <h1 className="h1">{completed ? t('reservation.closing.extraTitle') : t('reservation.closing.title')}</h1>
            <p className="sub">
              <AppLink href={backHref} className="p-link">
                {t('reservation.closing.toDetail')}
              </AppLink>
            </p>
          </div>
        </header>

        <section className="card detail-card" aria-label={t('reservation.closing.cardLabel')}>
          {!validId || current?.error === 'notfound' ? (
            <p className="meta">{t('reservation.notFound')}</p>
          ) : current?.error === 'conflict' ? (
            <p className="meta">{current.message ?? t('reservation.closing.cannotClose')}</p>
          ) : current?.error === 'error' ? (
            <p className="meta" role="alert">
              {t('reservation.closing.cardFailed')}{' '}
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => {
                  setLoaded(null);
                  setReload((n) => n + 1);
                }}
              >
                {t('common.retry')}
              </button>
            </p>
          ) : !closing ? (
            <p className="meta">{t('common.loading')}</p>
          ) : retry ? (
            <div className="detail">
              <div className="box warn" role="alert">
                <p>
                  <b>{t('reservation.closing.savedBold', { what: completed ? t('reservation.closing.extraPaymentIs') : t('reservation.closing.closingIs') })}</b> {t('reservation.closing.photoFailed', { n: retry.failed.length })}
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
                  {t('reservation.closing.skipPhotos')}
                </AppLink>
                <button type="button" className="btn btn-p" disabled={retry.busy} onClick={() => void retryPhotos()}>
                  {retry.busy ? t('reservation.closing.uploading') : t('reservation.closing.retryUpload', { n: retry.failed.length })}
                </button>
              </div>
            </div>
          ) : (
            <form className="detail" onSubmit={submit}>
              {(choicesFailed || industries === null) && (
                <div className="box warn" role="alert">
                  <p>
                    {t('common.partialLoadFailed')}{' '}
                    <button type="button" className="btn btn-sm" onClick={() => setChoicesTry((n) => n + 1)}>
                      {t('common.retry')}
                    </button>
                  </p>
                </div>
              )}
              <dl className="d-dl">
                <dt>{t('common.customer')}</dt>
                <dd className="strong">
                  {closing.customer ? (closing.customer.name ?? t('reservation.noName')) : needsCustomer && noCustomer ? t('reservation.closing.customerNone') : t('reservation.closing.customerNoInfo')}
                </dd>
                {summary && at ? (
                  <>
                    <dt>{t('reservation.closing.booking')}</dt>
                    <dd className="num">
                      {monthDayText(at.date)} ({dowLabel(dayIndex(at.date))}) {formatMinutes(at.minutes)} –{' '}
                      {formatMinutes(at.minutes + summary.minutes)} · {CHANNEL_LABEL[summary.channel] ?? summary.channel}
                    </dd>
                  </>
                ) : null}
              </dl>

              {needsChoice ? (
                <>
                  <div className="box warn" role="note">
                    <p>{t('reservation.closing.noCustomerHelp')}</p>
                  </div>
                  <div>
                    <span className="lbl">{t('reservation.linkCustomer')}</span>
                    <CustomerSearch onPick={(c) => void linkCustomer(c)} />
                  </div>
                  <div className="box">
                    <p>
                      <b>{t('reservation.closing.noCustomerClose')}</b> {t('reservation.closing.noCustomerCloseDesc')}
                    </p>
                    <button type="button" className="btn btn-sm" style={{ marginTop: 8 }} onClick={() => setNoCustomer(true)}>
                      {t('reservation.closing.closeWithout')}
                    </button>
                  </div>
                </>
              ) : (
                <>
                  {needsCustomer && noCustomer ? (
                    <div className="box" role="note">
                      <p>
                        {t('reservation.closing.closingWithout')}{' '}
                        <button type="button" className="lk" onClick={() => setNoCustomer(false)}>
                          {t('reservation.closing.linkIt')}
                        </button>
                      </p>
                    </div>
                  ) : null}
                  {completed ? (
                    <div className="box" role="note">
                      <p>
                        {t('reservation.closing.alreadyClosed')}{' '}
                        {closing.customer ? (
                          <>
                            {t('reservation.closing.fixInHistoryBefore')}{' '}
                            <AppLink href={`/customers/detail?id=${closing.customer.id}`} className="lk">
                              {t('reservation.closing.fixInHistoryLink')}
                            </AppLink>
                            {t('reservation.closing.fixInHistoryAfter')}
                          </>
                        ) : (
                          t('reservation.closing.noCustomerNoFix')
                        )}
                      </p>
                    </div>
                  ) : closing.servicesConfirmRequired ? (
                    <div className="box warn" role="note">
                      <p>{t('reservation.closing.consultNote')}</p>
                    </div>
                  ) : null}

                  <div>
                    <span className="lbl">{t('reservation.closing.services')}{completed ? '' : t('reservation.closing.servicesRule')}</span>
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
                                <select className="inp" aria-label={t('reservation.closing.staffOf', { name })} value={r.staffId} onChange={(e) => changeStaff(r.serviceMenuId, Number(e.target.value))}>
                                  {staff.map((s) => (
                                    <option key={s.id} value={s.id}>
                                      {s.name}
                                    </option>
                                  ))}
                                </select>
                                <button type="button" className="ib" aria-label={t('reservation.closing.removeService', { name })} onClick={() => removeService(r.serviceMenuId)}>
                                  ×
                                </button>
                              </>
                            )}
                            {/* 주기 계산 제외 (FR-VIS-05): 고객이 있는 마감에서만. 이미 항상 제외인 메뉴는 체크가 필요 없다 */}
                            {excludable && canExcludeFromCycle(attribute) ? (
                              <label className="chk svc-opt">
                                <input type="checkbox" checked={!!r.cycleExcluded} onChange={(e) => toggleExcluded(r.serviceMenuId, e.target.checked)} />
                                <span>{t('reservation.closing.excludeThis')}</span>
                              </label>
                            ) : null}
                            {hasCustomer && !completed && !canExcludeFromCycle(attribute) ? <span className="meta svc-opt">{t('reservation.closing.alwaysExcluded')}</span> : null}
                            {completed && r.cycleExcluded ? <span className="meta svc-opt">{t('reservation.closing.wasExcluded')}</span> : null}
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

                  {completed ? (
                    <div>
                      <span className="lbl">{t('reservation.closing.payments')}</span>
                      <PaymentList payments={closing.payments} onChanged={() => setReload((n) => n + 1)} empty={t('reservation.closing.paymentsEmpty')} />
                    </div>
                  ) : null}

                  <div>
                    <span className="lbl">{completed ? t('reservation.closing.extraAmount') : t('reservation.closing.amount')}</span>
                    <input
                      className="inp"
                      inputMode="numeric"
                      aria-label={t('reservation.closing.amount')}
                      placeholder={completed ? t('reservation.closing.extraPlaceholder') : '0'}
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
                    <span className="lbl">{t('reservation.closing.method')}</span>
                    <div className="pills">
                      {PAYMENT_METHODS.map(([value, label]) => (
                        <button key={value} type="button" className="pill" aria-pressed={method === value} onClick={() => setMethod(value)}>
                          {t(label)}
                        </button>
                      ))}
                    </div>
                  </div>

                  {hasCustomer ? (
                    <div>
                      <span className="lbl">{t('reservation.closing.visitMemo')}</span>
                      <textarea className="inp" maxLength={1000} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder={t('reservation.closing.memoPlaceholder')} />
                    </div>
                  ) : null}

                  {closing.customer ? (
                    <ConsentRequests customerId={closing.customer.id} items={consentItems} onAgreed={(item) => setAgreed((cur) => [...cur, item])} />
                  ) : null}

                  {photoAllowed ? (
                    <PhotoPicker files={photos} onChange={setPhotos} disabled={busy} />
                  ) : hasCustomer ? (
                    <p className="meta">{t('reservation.closing.needPhotoConsent')}</p>
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
                  {t('common.back')}
                </AppLink>
                {!needsChoice ? (
                  <button type="submit" className="btn btn-p" disabled={busy}>
                    {busy ? t('common.saving') : completed ? t('reservation.closing.saveExtra') : t('reservation.closing.saveClosing')}
                  </button>
                ) : null}
              </div>
            </form>
          )}
        </section>
      </div>

      {ask ? (
        <div className="overlay">
          <div className="card modal" role="dialog" aria-modal="true" aria-label={t('reservation.closing.excludeAria')}>
            <h2 className="h2" style={{ fontSize: '19px' }}>
              {t('reservation.closing.askExclude', { name: info(ask.menuId).name })}
            </h2>
            <p className="meta">
              {t('reservation.closing.askBody')}
            </p>
            {ask.error ? (
              <span className="err" role="alert">
                {ask.error}
              </span>
            ) : null}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
              <button type="button" className="btn" disabled={ask.busy} onClick={excludeThisVisitOnly}>
                {t('reservation.closing.no')}
              </button>
              <button type="button" className="btn btn-p" disabled={ask.busy} onClick={() => void excludeWholeMenu()}>
                {ask.busy ? t('reservation.closing.changing') : t('reservation.closing.yes')}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
