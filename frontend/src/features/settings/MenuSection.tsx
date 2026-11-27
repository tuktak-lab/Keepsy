import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { formatWon } from '@/lib/format';
import {
  ATTRIBUTES,
  ATTRIBUTE_LABEL,
  CATEGORIES,
  CATEGORY_LABEL,
  DURATION_STEPS,
  changesPrediction,
  emptyMenuValues,
  formatDuration,
  toCreateRequest,
  toCycleValues,
  toCyclesRequest,
  toMenuValues,
  toPatchRequest,
  validateCycles,
  validateMenu,
  type Category,
  type CategoryCycle,
  type CycleValues,
  type Menu,
  type MenuValues,
  type PredictionAttribute,
} from '@/lib/menus';
import { detailsText } from '@/lib/reservationForm';
import { t } from '@/lib/i18n';

// 시술 메뉴 · 카테고리 기본 주기 (FR-SET-02). 변경은 원장 PIN이 필요하다 (api.ts가 PIN 창을 띄움).
// API: GET · POST · PATCH /api/service-menus, GET · PUT /api/category-cycles (docs CONTRACTS 6장 「매장 설정 (SET)」)
// 메뉴의 기본 주기나 예측 속성을 바꾸면 그 메뉴를 받은 고객 전체의 예측이 다시 계산되므로 저장 전에 한 번 더 확인한다.
// 매장 업종(CONTRACTS 3절)에서 꺼 둔 카테고리는 이 구역에서만 숨긴다. 기존 메뉴 · 방문 · 예측 데이터는 그대로이고,
// 기본 주기는 저장할 때 5개를 모두 보낸다 (PUT /api/category-cycles). 숨긴 카테고리 값은 불러온 그대로다.

/** 편집 중인 메뉴 한 줄. `menuId`가 null이면 새 메뉴 */
type Editing = { menuId: number | null; category: Category; values: MenuValues; confirm: boolean; error: string; busy: boolean };

const errorText = (err: unknown): string =>
  err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : t('common.pin.tryLater');

/** @param industries 켜 둔 업종. undefined는 아직 모름(불러오는 중), null은 불러오지 못해 모름(모두 보여 줌) */
export function MenuSection({ industries }: { industries: string[] | null | undefined }) {
  const [menus, setMenus] = useState<Menu[] | null>(null);
  const [cycles, setCycles] = useState<CategoryCycle[] | null>(null);
  const [cycleValues, setCycleValues] = useState<CycleValues | null>(null);
  const [savedCycleValues, setSavedCycleValues] = useState<CycleValues | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [reload, setReload] = useState(0);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [cycleError, setCycleError] = useState('');
  const [cycleBusy, setCycleBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api<Menu[]>('/api/service-menus', { silent: true }), api<CategoryCycle[]>('/api/category-cycles', { silent: true })])
      .then(([m, c]) => {
        if (cancelled) return;
        setMenus(m);
        setCycles(c);
        const v = toCycleValues(c);
        setCycleValues(v);
        setSavedCycleValues(v);
      })
      .catch(() => !cancelled && setLoadFailed(true));
    return () => {
      cancelled = true;
    };
  }, [reload]);

  if (loadFailed && !menus) {
    return (
      <section id="st-menu" className="st-card">
        <h2 data-guide="settings-menu">{t('settings.menu.title')}</h2>
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
  if (!menus || !cycles || !cycleValues || !savedCycleValues || industries === undefined) {
    return (
      <section id="st-menu" className="st-card">
        <h2 data-guide="settings-menu">{t('settings.menu.title')}</h2>
        <p className="st-meta">{t('common.loading')}</p>
      </section>
    );
  }

  const shown = CATEGORIES.filter((c) => !industries || industries.includes(c));
  const hidden = CATEGORIES.filter((c) => !shown.includes(c));
  // 편집 중이던 카테고리가 꺼지면 편집을 닫는다 (안 닫으면 다른 버튼이 계속 잠긴다)
  if (editing && !shown.includes(editing.category)) setEditing(null);

  const cycleOf = (c: Category) => cycles.find((x) => x.category === c);

  const startEdit = (category: Category, menu?: Menu) =>
    setEditing({
      menuId: menu?.id ?? null,
      category,
      values: menu ? toMenuValues(menu) : emptyMenuValues(cycleOf(category)),
      confirm: false,
      error: '',
      busy: false,
    });

  const change = (patch: Partial<MenuValues>) => editing && setEditing({ ...editing, values: { ...editing.values, ...patch }, confirm: false, error: '' });

  async function saveMenu() {
    if (!editing || !menus) return;
    const problem = validateMenu(editing.values);
    if (problem) return setEditing({ ...editing, error: problem });
    const original = editing.menuId !== null ? menus.find((m) => m.id === editing.menuId) : undefined;
    const patch = original ? toPatchRequest(original, editing.values) : null;
    if (original && patch && Object.keys(patch).length === 0) return setEditing(null); // 바뀐 것이 없다
    if (patch && changesPrediction(patch) && !editing.confirm) return setEditing({ ...editing, confirm: true }); // 예측 재계산 확인
    setEditing({ ...editing, busy: true, error: '' });
    try {
      if (original && patch) await api(`/api/service-menus/${original.id}`, { method: 'PATCH', body: patch, silent: true });
      else await api('/api/service-menus', { method: 'POST', body: toCreateRequest(editing.category, editing.values), silent: true });
      toast(original ? t('settings.menu.editedToast') : t('settings.menu.addedToast'));
      setEditing(null);
      setReload((n) => n + 1);
    } catch (err) {
      setEditing({ ...editing, busy: false, confirm: false, error: errorText(err) });
    }
  }

  const cyclesDirty = JSON.stringify(cycleValues) !== JSON.stringify(savedCycleValues);

  async function saveCycles() {
    if (!cycleValues) return;
    const problem = validateCycles(cycleValues);
    if (problem) return setCycleError(problem);
    setCycleBusy(true);
    setCycleError('');
    try {
      await api('/api/category-cycles', { method: 'PUT', body: toCyclesRequest(cycleValues), silent: true });
      toast(t('settings.menu.cyclesToast'));
      setReload((n) => n + 1);
    } catch (err) {
      setCycleError(errorText(err));
    } finally {
      setCycleBusy(false);
    }
  }

  return (
    <section id="st-menu" className="st-card">
      <h2 data-guide="settings-menu">{t('settings.menu.title')}</h2>
      <p className="st-meta">{t('settings.menu.intro')}</p>
      <div className="st-note">
        <b>{t('settings.menu.excludePre')}</b>{t('settings.menu.excludeBody')}
        <br />
        <b>{t('settings.menu.endPre')}</b>{t('settings.menu.endBody')}
      </div>

      {hidden.length > 0 ? (
        <p className="st-meta">
          {t('settings.menu.hidden', { list: hidden.map((c) => CATEGORY_LABEL[c]).join(' · ') })}
        </p>
      ) : null}

      {/* 카테고리별 메뉴 묶음은 넓은 화면에서 두 열 (SettingsPage.css .st-catgrid) */}
      <div className="st-catgrid">
        {shown.map((cat) => {
          const list = menus.filter((m) => m.category === cat);
          const cycle = cycleOf(cat);
          const newHere = editing !== null && editing.menuId === null && editing.category === cat;
          return (
            <div key={cat}>
              <div className="st-cat">
                {CATEGORY_LABEL[cat]} <span className="st-meta num">{t('settings.menu.catDefault', { range: cycle ? t('settings.menu.daysRange', { min: cycle.minDays, max: cycle.maxDays }) : '-' })}</span>
              </div>
              {list.length === 0 && !newHere ? <p className="st-meta">{t('settings.menu.noMenus')}</p> : null}
              {list.map((m) =>
                editing?.menuId === m.id ? (
                  <MenuEditor key={m.id} editing={editing} onChange={change} onSave={() => void saveMenu()} onCancel={() => setEditing(null)} />
                ) : (
                  <div className="st-row" key={m.id}>
                    <span className="grow nm">{m.name}</span>
                    <span className="num">{formatWon(m.price)}</span>
                    <span className="num st-meta">{formatDuration(m.durationMinutes)}</span>
                    <span className="num st-meta">
                      {t('settings.menu.cycleOf', { min: m.cycleMinDays, max: m.cycleMaxDays })}
                    </span>
                    <span className={m.predictionAttribute === 'NORMAL' ? 'st-tag' : 'st-tag w'}>{ATTRIBUTE_LABEL[m.predictionAttribute]}</span>
                    <button type="button" className="st-btn" disabled={editing !== null} onClick={() => startEdit(cat, m)}>
                      {t('customer.edit')}
                    </button>
                  </div>
                ),
              )}
              {newHere && editing ? (
                <MenuEditor editing={editing} onChange={change} onSave={() => void saveMenu()} onCancel={() => setEditing(null)} />
              ) : (
                <div className="st-row">
                  <button type="button" className="st-btn" disabled={editing !== null} onClick={() => startEdit(cat)}>
                    {t('settings.menu.addMenu', { category: CATEGORY_LABEL[cat] })}
                  </button>
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div>
        <div className="st-lbl">{t('settings.menu.catCycles')}</div>
        <p className="st-meta">{t('settings.menu.catCyclesNote')}</p>
        <div className="st-rowgrid">
          {shown.map((cat) => (
            <div className="st-row" key={cat}>
              <span className="nm" style={{ width: 64, fontWeight: 600 }}>
                {CATEGORY_LABEL[cat]}
              </span>
              <input
                className="st-time num"
                inputMode="numeric"
                aria-label={t('settings.menu.catMinAria', { category: CATEGORY_LABEL[cat] })}
                value={cycleValues[cat].minDays}
                onChange={(e) => {
                  setCycleError('');
                  setCycleValues({ ...cycleValues, [cat]: { ...cycleValues[cat], minDays: e.target.value } });
                }}
              />
              <span>~</span>
              <input
                className="st-time num"
                inputMode="numeric"
                aria-label={t('settings.menu.catMaxAria', { category: CATEGORY_LABEL[cat] })}
                value={cycleValues[cat].maxDays}
                onChange={(e) => {
                  setCycleError('');
                  setCycleValues({ ...cycleValues, [cat]: { ...cycleValues[cat], maxDays: e.target.value } });
                }}
              />
              <span className="st-meta">{t('settings.menu.dayUnit')}</span>
            </div>
          ))}
        </div>
        {cycleError ? (
          <p className="st-err" role="alert">
            {cycleError}
          </p>
        ) : null}
        <div className="st-savebar">
          <span className="st-meta">{cyclesDirty ? t('settings.unsaved') : t('settings.noChange')}</span>
          <button type="button" className="st-btn p" disabled={!cyclesDirty || cycleBusy} onClick={() => void saveCycles()}>
            {cycleBusy ? t('common.saving') : t('settings.menu.cyclesSave')}
          </button>
        </div>
      </div>
    </section>
  );
}

/** 메뉴 한 줄 편집 (수정 · 추가 공용). 기본 주기나 예측 속성을 바꾸면 저장 전에 재계산 확인을 한 번 더 받는다 */
function MenuEditor(props: { editing: Editing; onChange: (patch: Partial<MenuValues>) => void; onSave: () => void; onCancel: () => void }) {
  const { editing, onChange, onSave, onCancel } = props;
  const v = editing.values;
  const isNew = editing.menuId === null;
  const label = (k: string) => t('settings.menu.fieldAria', { menu: isNew ? t('settings.menu.newMenu') : v.name, field: k });
  return (
    <div className="st-row st-edit">
      <input className="st-fld grow" placeholder={t('settings.menu.fName')} aria-label={label(t('settings.menu.fName'))} maxLength={100} value={v.name} onChange={(e) => onChange({ name: e.target.value })} autoFocus />
      <input className="st-time num" inputMode="numeric" placeholder={t('settings.menu.fPriceWon')} aria-label={label(t('settings.menu.fPrice'))} value={v.price} onChange={(e) => onChange({ price: e.target.value })} />
      <select className="st-time" aria-label={label(t('settings.menu.fDuration'))} value={v.durationMinutes} onChange={(e) => onChange({ durationMinutes: Number(e.target.value) })}>
        {DURATION_STEPS.map((m) => (
          <option key={m} value={m}>
            {formatDuration(m)}
          </option>
        ))}
      </select>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        <input className="st-time num" inputMode="numeric" aria-label={label(t('settings.menu.fCycleMin'))} value={v.cycleMinDays} onChange={(e) => onChange({ cycleMinDays: e.target.value })} />~
        <input className="st-time num" inputMode="numeric" aria-label={label(t('settings.menu.fCycleMax'))} value={v.cycleMaxDays} onChange={(e) => onChange({ cycleMaxDays: e.target.value })} />{t('settings.menu.dayUnit')}
      </span>
      <select
        className="st-time"
        aria-label={label(t('settings.menu.fAttr'))}
        value={v.predictionAttribute}
        onChange={(e) => onChange({ predictionAttribute: e.target.value as PredictionAttribute })}
      >
        {ATTRIBUTES.map(([value, text]) => (
          <option key={value} value={value}>
            {t(text)}
          </option>
        ))}
      </select>
      {editing.confirm ? (
        <div className="st-note" style={{ flexBasis: '100%' }} role="alert">
          {t('settings.menu.recalcPre')}<b>{t('settings.menu.recalcBold')}</b>{t('settings.menu.recalcPost')}
        </div>
      ) : null}
      {editing.error ? (
        <p className="st-err" style={{ flexBasis: '100%' }} role="alert">
          {editing.error}
        </p>
      ) : null}
      <span style={{ display: 'inline-flex', gap: 8 }}>
        <button type="button" className="st-btn" disabled={editing.busy} onClick={onCancel}>
          {t('common.cancel')}
        </button>
        <button type="button" className="st-btn p" disabled={editing.busy} onClick={onSave}>
          {editing.busy ? t('common.saving') : editing.confirm ? t('settings.menu.yesSave') : isNew ? t('settings.menu.add') : t('customer.save')}
        </button>
      </span>
    </div>
  );
}
