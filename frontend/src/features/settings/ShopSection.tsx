import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import type { OpeningHour } from '@/lib/calendar';
import { detailsText } from '@/lib/reservationForm';
import { CATEGORIES, CATEGORY_LABEL } from '@/lib/menus';
import { DAY_LABEL, DEFAULT_CLOSE, DEFAULT_OPEN, toRows, industryChange, toShopRequest, toggleIndustry, validateShop, type ShopValues } from '@/lib/settings';
import { PushedReservations, type PushedReservation } from './PushedReservations';

// 매장 정보 · 영업시간 (FR-SET-01). GET · PUT /api/shop (docs CONTRACTS 6장 「매장 설정 (SET)」). 변경은 원장 PIN이 필요하다 (api.ts가 PIN 창을 띄움).
// 매장 업종 켜기 · 끄기(CONTRACTS 3절 「매장 업종」)도 이 저장에 같이 보낸다. 켜 둔 업종은 `onIndustries`로 알려 시술 메뉴 · 기본 주기 구역이 그것만 보여 준다.

type ShopResponse = {
  name: string;
  phone: string | null;
  senderNumber: string | null;
  unsubscribeNumber: string | null;
  openingHours: OpeningHour[];
  industries: string[];
};

const toValues = (s: ShopResponse): ShopValues => ({
  name: s.name,
  phone: s.phone ?? '',
  senderNumber: s.senderNumber ?? '',
  unsubscribeNumber: s.unsubscribeNumber ?? '',
  hours: toRows(s.openingHours),
  industries: s.industries,
});

/** @param onIndustries 켜 둔 업종을 불러오거나 저장했을 때 알린다. 불러오지 못하면 null (모르는 값) */
export function ShopSection({ onIndustries }: { onIndustries?: (list: string[] | null) => void }) {
  const [saved, setSaved] = useState<ShopValues | null>(null); // 서버에 있는 값
  const [values, setValues] = useState<ShopValues | null>(null); // 화면에서 고치는 값
  const [loadFailed, setLoadFailed] = useState(false);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [pushed, setPushed] = useState<PushedReservation[]>([]);
  const [confirming, setConfirming] = useState(false); // 업종을 바꾼 저장은 한 번 더 확인한다

  useEffect(() => {
    let cancelled = false;
    api<ShopResponse>('/api/shop', { silent: true })
      .then((shop) => {
        if (cancelled) return;
        const v = toValues(shop);
        setSaved(v);
        setValues(v);
        onIndustries?.(v.industries);
      })
      .catch(() => {
        if (cancelled) return;
        setLoadFailed(true);
        onIndustries?.(null);
      });
    return () => {
      cancelled = true;
    };
  }, [reload, onIndustries]);

  if (loadFailed && !values) {
    return (
      <section id="st-shop" className="st-card">
        <h2>매장 정보 · 영업시간</h2>
        <p className="st-meta">
          불러오지 못했어요.{' '}
          <button
            type="button"
            className="st-btn"
            onClick={() => {
              setLoadFailed(false);
              setReload((n) => n + 1);
            }}
          >
            다시 시도
          </button>
        </p>
      </section>
    );
  }
  if (!values || !saved) {
    return (
      <section id="st-shop" className="st-card">
        <h2>매장 정보 · 영업시간</h2>
        <p className="st-meta">불러오는 중…</p>
      </section>
    );
  }

  const dirty = JSON.stringify(values) !== JSON.stringify(saved);
  const change = industryChange(CATEGORIES, saved.industries, values.industries);
  const industryChanged = change.off.length > 0 || change.on.length > 0;
  const edit = (patch: Partial<ShopValues>) => {
    setError('');
    setConfirming(false);
    setValues({ ...values, ...patch });
  };
  const editRow = (i: number, patch: Partial<ShopValues['hours'][number]>) => edit({ hours: values.hours.map((h, j) => (j === i ? { ...h, ...patch } : h)) });

  async function save() {
    if (!values) return;
    const problem = validateShop(values);
    if (problem) return setError(problem);
    // 업종이 바뀌었으면 처음 누를 때는 바뀌는 내용을 보여 주고, 「예, 저장」을 눌러야 저장한다
    if (industryChanged && !confirming) {
      setConfirming(true);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const res = await api<{ pushedReservations: PushedReservation[] }>('/api/shop', { method: 'PUT', body: toShopRequest(values), silent: true });
      setSaved(values);
      onIndustries?.(values.industries);
      setPushed(res?.pushedReservations ?? []);
      toast('매장 정보를 저장했어요');
    } catch (err) {
      setError(err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : '잠시 후 다시 시도해 주세요');
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  }

  return (
    <section id="st-shop" className="st-card">
      <h2>매장 정보 · 영업시간</h2>
      <div className="st-grid">
        <div className="st-field">
          <label className="st-lbl" htmlFor="shop-name">
            매장명
          </label>
          <input id="shop-name" className="st-fld" maxLength={100} value={values.name} onChange={(e) => edit({ name: e.target.value })} />
        </div>
        <div className="st-field">
          <label className="st-lbl" htmlFor="shop-phone">
            매장 연락처
          </label>
          <input id="shop-phone" className="st-fld num" inputMode="tel" placeholder="02-000-0000" value={values.phone} onChange={(e) => edit({ phone: e.target.value })} />
        </div>
        <div className="st-field">
          <label className="st-lbl" htmlFor="shop-sender">
            발신번호
          </label>
          <input id="shop-sender" className="st-fld num" inputMode="tel" placeholder="010-0000-0000" value={values.senderNumber} onChange={(e) => edit({ senderNumber: e.target.value })} />
        </div>
        <div className="st-field">
          <label className="st-lbl" htmlFor="shop-unsub">
            수신 거부 번호
          </label>
          <input id="shop-unsub" className="st-fld num" inputMode="tel" placeholder="080-000-0000" value={values.unsubscribeNumber} onChange={(e) => edit({ unsubscribeNumber: e.target.value })} />
        </div>
      </div>
      <p className="st-meta">연락처 · 발신번호 · 수신 거부 번호는 광고 메시지 표기에 쓰여요. 비워 둘 수 있어요.</p>

      <div>
        <div className="st-lbl" id="shop-industries-label">
          매장 업종
        </div>
        <div className="st-chips" role="group" aria-labelledby="shop-industries-label">
          {CATEGORIES.map((c) => {
            const on = values.industries.includes(c);
            return (
              <label className="st-chk" key={c}>
                <input
                  type="checkbox"
                  checked={on}
                  disabled={on && values.industries.length === 1}
                  onChange={() => edit({ industries: toggleIndustry(CATEGORIES, values.industries, c) })}
                />
                {CATEGORY_LABEL[c]}
              </label>
            );
          })}
        </div>
      </div>
      <p className="st-meta">켜 둔 업종만 아래 「시술 메뉴 · 기본 주기」, 예약 · 시술 마감의 시술 선택, 새 캠페인의 시술 카테고리에 보여요. 꺼도 기존 메뉴 · 예약 · 고객 기록은 그대로이고, 다시 켜면 돌아와요. 하나는 켜 두어야 해요.</p>

      <div>
        <div className="st-lbl">요일별 영업시간 (30분 단위)</div>
        {values.hours.map((h, i) => (
          <div className="st-row" key={h.dayOfWeek}>
            <span className="nm" style={{ width: 28, fontWeight: 600 }}>
              {DAY_LABEL[i]}
            </span>
            <input
              type="time"
              step={1800}
              className="st-time num"
              value={h.off ? '' : h.open}
              disabled={h.off}
              aria-label={`${DAY_LABEL[i]}요일 여는 시각`}
              onChange={(e) => editRow(i, { open: e.target.value })}
            />
            <span>~</span>
            <input
              type="time"
              step={1800}
              className="st-time num"
              value={h.off ? '' : h.close}
              disabled={h.off}
              aria-label={`${DAY_LABEL[i]}요일 닫는 시각`}
              onChange={(e) => editRow(i, { close: e.target.value })}
            />
            <label className="st-chk">
              <input
                type="checkbox"
                checked={h.off}
                onChange={(e) => editRow(i, e.target.checked ? { off: true } : { off: false, open: h.open || DEFAULT_OPEN, close: h.close || DEFAULT_CLOSE })}
              />
              정기 휴무
            </label>
          </div>
        ))}
      </div>
      <p className="st-meta">바꾼 영업시간은 바로 예약 가능 시간에 반영돼요. 이미 잡힌 확정 예약이 영업시간 밖으로 밀리면 저장 뒤에 목록을 보여 드려요.</p>

      {error ? (
        <p className="st-err" role="alert">
          {error}
        </p>
      ) : null}
      {confirming && industryChanged ? (
        <div className="st-note" role="alert">
          <b>매장 업종을 바꿀까요?</b>
          {change.off.length > 0 ? <> 꺼지는 업종: {change.off.map((c) => CATEGORY_LABEL[c as keyof typeof CATEGORY_LABEL]).join(' · ')}.</> : null}
          {change.on.length > 0 ? <> 켜지는 업종: {change.on.map((c) => CATEGORY_LABEL[c as keyof typeof CATEGORY_LABEL]).join(' · ')}.</> : null} 꺼 둔 업종은 시술 메뉴 · 기본 주기, 예약 · 시술 마감의 시술 선택, 새 캠페인의 시술 카테고리에서 숨겨져요. 기존 메뉴 · 예약 · 고객 기록은 그대로이고 다시 켜면 돌아와요.
        </div>
      ) : null}
      <PushedReservations items={pushed} onDismiss={() => setPushed([])} />
      <div className="st-savebar">
        <span className="st-meta">{dirty ? '저장되지 않은 변경 사항이 있어요' : '변경 사항 없음'}</span>
        {confirming ? (
          <button type="button" className="st-btn" disabled={busy} onClick={() => setConfirming(false)}>
            취소
          </button>
        ) : null}
        <button type="button" className="st-btn p" disabled={!dirty || busy} onClick={() => void save()}>
          {busy ? '저장 중…' : confirming ? '예, 저장' : '매장 정보 저장'}
        </button>
      </div>
    </section>
  );
}
