import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import type { OpeningHour } from '@/lib/calendar';
import { detailsText } from '@/lib/reservationForm';
import { CATEGORIES, CATEGORY_LABEL } from '@/lib/menus';
import { DEFAULT_CLOSE, DEFAULT_OPEN, toRows, industryChange, toShopRequest, toggleIndustry, validateShop, type ShopValues } from '@/lib/settings';
import { PushedReservations, type PushedReservation } from './PushedReservations';
import { t } from '@/lib/i18n';
import { dowLabel } from '@/lib/calendar';

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
  const [confirming, setConfirming] = useState(false); // 「변경사항을 저장하시겠습니까?」 팝업

  // 저장 확인 팝업은 Esc로 닫는다 (저장하는 중에는 닫지 않는다)
  useEffect(() => {
    if (!confirming) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !busy && setConfirming(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [confirming, busy]);

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
        <h2 data-guide="settings-shop">{t('settings.shop.title')}</h2>
        <p className="st-meta">
          {t('settings.loadFailedDot')}{' '}
          <button
            type="button"
            className="st-btn"
            onClick={() => {
              setLoadFailed(false);
              setReload((n) => n + 1);
            }}
          >
            {t('common.retry')}
          </button>
        </p>
      </section>
    );
  }
  if (!values || !saved) {
    return (
      <section id="st-shop" className="st-card">
        <h2 data-guide="settings-shop">{t('settings.shop.title')}</h2>
        <p className="st-meta">{t('common.loading')}</p>
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
    // 처음 누를 때는 확인 팝업을 띄우고, 팝업에서 「저장」을 눌러야 저장한다
    if (!confirming) {
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
      toast(t('settings.shop.saved'));
    } catch (err) {
      setError(err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : t('common.pin.tryLater'));
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  }

  return (
    <section id="st-shop" className="st-card">
      <h2 data-guide="settings-shop">{t('settings.shop.title')}</h2>
      {/* 왼쪽 매장 정보(세로), 오른쪽 요일별 영업시간(세로). 좁은 화면에서는 위아래로 (SettingsPage.css .st-shop-cols) */}
      <div className="st-shop-cols">
        <div className="st-shop-col">
          <div className="st-grid">
            <div className="st-field">
              <label className="st-lbl" htmlFor="shop-name">
                {t('settings.shop.name')}
              </label>
              <input id="shop-name" className="st-fld" maxLength={100} value={values.name} onChange={(e) => edit({ name: e.target.value })} />
            </div>
            <div className="st-field">
              <label className="st-lbl" htmlFor="shop-phone">
                {t('settings.shop.phone')}
              </label>
              <input id="shop-phone" className="st-fld num" inputMode="tel" placeholder="02-000-0000" value={values.phone} onChange={(e) => edit({ phone: e.target.value })} />
            </div>
            <div className="st-field">
              <label className="st-lbl" htmlFor="shop-sender">
                {t('settings.shop.sender')}
              </label>
              <input id="shop-sender" className="st-fld num" inputMode="tel" placeholder="010-0000-0000" value={values.senderNumber} onChange={(e) => edit({ senderNumber: e.target.value })} />
            </div>
            <div className="st-field">
              <label className="st-lbl" htmlFor="shop-unsub">
                {t('settings.shop.unsub')}
              </label>
              <input id="shop-unsub" className="st-fld num" inputMode="tel" placeholder="080-000-0000" value={values.unsubscribeNumber} onChange={(e) => edit({ unsubscribeNumber: e.target.value })} />
            </div>
          </div>
          <p className="st-meta">{t('settings.shop.numbersNote')}</p>

          <div>
            <div className="st-lbl" id="shop-industries-label">
              {t('settings.shop.industries')}
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
          <p className="st-meta st-lines">{t('settings.shop.industryNote')}</p>
        </div>
        <div className="st-shop-col">
          <div>
            <div className="st-lbl">{t('settings.shop.hoursLabel')}</div>
            <div className="st-hours">
              {values.hours.map((h, i) => (
                <div className="st-row" key={h.dayOfWeek}>
                  <span className="nm" style={{ width: 28, fontWeight: 600 }}>
                    {dowLabel(i)}
                  </span>
                  <input
                    type="time"
                    step={1800}
                    className="st-time num"
                    value={h.off ? '' : h.open}
                    disabled={h.off}
                    aria-label={t('settings.shop.openAria', { day: dowLabel(i) })}
                    onChange={(e) => editRow(i, { open: e.target.value })}
                  />
                  <span>~</span>
                  <input
                    type="time"
                    step={1800}
                    className="st-time num"
                    value={h.off ? '' : h.close}
                    disabled={h.off}
                    aria-label={t('settings.shop.closeAria', { day: dowLabel(i) })}
                    onChange={(e) => editRow(i, { close: e.target.value })}
                  />
                  <label className="st-chk">
                    <input
                      type="checkbox"
                      checked={h.off}
                      onChange={(e) => editRow(i, e.target.checked ? { off: true } : { off: false, open: h.open || DEFAULT_OPEN, close: h.close || DEFAULT_CLOSE })}
                    />
                    {t('settings.shop.regularOff')}
                  </label>
                </div>
              ))}
            </div>
          </div>
          <p className="st-meta">{t('settings.shop.hoursNote')}</p>
        </div>
      </div>

      {error ? (
        <p className="st-err" role="alert">
          {error}
        </p>
      ) : null}
      <PushedReservations items={pushed} onDismiss={() => setPushed([])} />
      <div className="st-savebar">
        <span className="st-meta">{dirty ? t('settings.unsaved') : t('settings.noChange')}</span>
        <button type="button" className="st-btn p" disabled={!dirty || busy} onClick={() => void save()}>
          {busy ? t('common.saving') : t('settings.shop.saveBtn')}
        </button>
      </div>

      {confirming ? (
        <div className="st-overlay" onClick={() => !busy && setConfirming(false)}>
          <div className="st-dialog" role="dialog" aria-modal="true" aria-labelledby="shop-confirm-title" onClick={(e) => e.stopPropagation()}>
            <h3 id="shop-confirm-title">{t('settings.shop.confirmTitle')}</h3>
            {industryChanged ? (
              <p>
                {change.off.length > 0 ? <>{t('settings.shop.confirmOff')}<b>{change.off.map((c) => CATEGORY_LABEL[c as keyof typeof CATEGORY_LABEL]).join(' · ')}</b>. </> : null}
                {change.on.length > 0 ? <>{t('settings.shop.confirmOn')}<b>{change.on.map((c) => CATEGORY_LABEL[c as keyof typeof CATEGORY_LABEL]).join(' · ')}</b>. </> : null}
                {t('settings.shop.confirmHidden')}
              </p>
            ) : (
              <p>{t('settings.shop.confirmPlain')}</p>
            )}
            <div className="st-dialog-acts">
              <button type="button" className="st-btn" disabled={busy} onClick={() => setConfirming(false)}>
                {t('common.cancel')}
              </button>
              <button type="button" className="st-btn p" disabled={busy} onClick={() => void save()} autoFocus>
                {busy ? t('common.saving') : t('customer.save')}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}
