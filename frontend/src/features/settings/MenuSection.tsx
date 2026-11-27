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

// 시술 메뉴 · 카테고리 기본 주기 (FR-SET-02). 변경은 원장 PIN이 필요하다 (api.ts가 PIN 창을 띄움).
// API: GET · POST · PATCH /api/service-menus, GET · PUT /api/category-cycles (docs CONTRACTS 6장 「매장 설정 (SET)」)
// 메뉴의 기본 주기나 예측 속성을 바꾸면 그 메뉴를 받은 고객 전체의 예측이 다시 계산되므로 저장 전에 한 번 더 확인한다.
// 매장 업종(CONTRACTS 3절)에서 꺼 둔 카테고리는 이 구역에서만 숨긴다. 기존 메뉴 · 방문 · 예측 데이터는 그대로이고,
// 기본 주기는 저장할 때 5개를 모두 보낸다 (PUT /api/category-cycles). 숨긴 카테고리 값은 불러온 그대로다.

/** 편집 중인 메뉴 한 줄. `menuId`가 null이면 새 메뉴 */
type Editing = { menuId: number | null; category: Category; values: MenuValues; confirm: boolean; error: string; busy: boolean };

const errorText = (err: unknown): string =>
  err instanceof ApiError ? (err.code === 'invalid' ? (detailsText(err.details) ?? err.message) : err.message) : '잠시 후 다시 시도해 주세요';

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
        <h2 data-guide="settings-menu">시술 메뉴 · 기본 주기</h2>
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
  if (!menus || !cycles || !cycleValues || !savedCycleValues || industries === undefined) {
    return (
      <section id="st-menu" className="st-card">
        <h2 data-guide="settings-menu">시술 메뉴 · 기본 주기</h2>
        <p className="st-meta">불러오는 중…</p>
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
      toast(original ? '시술 메뉴를 수정했어요' : '시술 메뉴를 추가했어요');
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
      toast('카테고리 기본 주기를 저장했어요');
      setReload((n) => n + 1);
    } catch (err) {
      setCycleError(errorText(err));
    } finally {
      setCycleBusy(false);
    }
  }

  return (
    <section id="st-menu" className="st-card">
      <h2 data-guide="settings-menu">시술 메뉴 · 기본 주기</h2>
      <p className="st-meta">카테고리 기본 주기에서 시작하고, 메뉴마다 기본 주기(일)를 고칠 수 있어요. 고객 기록이 쌓이면 개인 평균 주기를 우선 적용해요.</p>
      <div className="st-note">
        <b>주기 계산 제외</b> : 반영구 리터치·무료 보수·아트 추가처럼 다음 방문 시점을 정하지 않는 메뉴예요. 방문으로 기록되지만 재방문 주기 계산에는 넣지 않아요.
        <br />
        <b>종료 신호</b> : &quot;젤 제거만&quot;처럼 그만두는 손님이 받는 메뉴예요. 이 메뉴만 받은 방문은 다음 방문을 예측하지 않아요.
      </div>

      {hidden.length > 0 ? (
        <p className="st-meta">
          꺼 둔 업종: {hidden.map((c) => CATEGORY_LABEL[c]).join(' · ')}. 위 「매장 정보 · 영업시간」의 매장 업종에서 켤 수 있어요. 숨겨도 메뉴와 기본 주기 값은 그대로예요.
        </p>
      ) : null}

      {shown.map((cat) => {
        const list = menus.filter((m) => m.category === cat);
        const cycle = cycleOf(cat);
        const newHere = editing !== null && editing.menuId === null && editing.category === cat;
        return (
          <div key={cat}>
            <div className="st-cat">
              {CATEGORY_LABEL[cat]} <span className="st-meta num">· 기본값 {cycle ? `${cycle.minDays}~${cycle.maxDays}일` : '-'}</span>
            </div>
            {list.length === 0 && !newHere ? <p className="st-meta">등록된 메뉴가 없어요.</p> : null}
            {list.map((m) =>
              editing?.menuId === m.id ? (
                <MenuEditor key={m.id} editing={editing} onChange={change} onSave={() => void saveMenu()} onCancel={() => setEditing(null)} />
              ) : (
                <div className="st-row" key={m.id}>
                  <span className="grow nm">{m.name}</span>
                  <span className="num">{formatWon(m.price)}</span>
                  <span className="num st-meta">{formatDuration(m.durationMinutes)}</span>
                  <span className="num st-meta">
                    주기 {m.cycleMinDays}~{m.cycleMaxDays}일
                  </span>
                  <span className={m.predictionAttribute === 'NORMAL' ? 'st-tag' : 'st-tag w'}>{ATTRIBUTE_LABEL[m.predictionAttribute]}</span>
                  <button type="button" className="st-btn" disabled={editing !== null} onClick={() => startEdit(cat, m)}>
                    수정
                  </button>
                </div>
              ),
            )}
            {newHere && editing ? (
              <MenuEditor editing={editing} onChange={change} onSave={() => void saveMenu()} onCancel={() => setEditing(null)} />
            ) : (
              <div className="st-row">
                <button type="button" className="st-btn" disabled={editing !== null} onClick={() => startEdit(cat)}>
                  + {CATEGORY_LABEL[cat]} 메뉴 추가
                </button>
              </div>
            )}
          </div>
        );
      })}

      <div>
        <div className="st-lbl">카테고리 기본 주기</div>
        <p className="st-meta">메뉴가 없는 가져온 방문의 예측에 쓰여요. 바꾸면 그 카테고리에 메뉴 없는 방문이 있는 고객의 예측이 다시 계산돼요.</p>
        {shown.map((cat) => (
          <div className="st-row" key={cat}>
            <span className="nm" style={{ width: 64, fontWeight: 600 }}>
              {CATEGORY_LABEL[cat]}
            </span>
            <input
              className="st-time num"
              inputMode="numeric"
              aria-label={`${CATEGORY_LABEL[cat]} 기본 주기 최소(일)`}
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
              aria-label={`${CATEGORY_LABEL[cat]} 기본 주기 최대(일)`}
              value={cycleValues[cat].maxDays}
              onChange={(e) => {
                setCycleError('');
                setCycleValues({ ...cycleValues, [cat]: { ...cycleValues[cat], maxDays: e.target.value } });
              }}
            />
            <span className="st-meta">일</span>
          </div>
        ))}
        {cycleError ? (
          <p className="st-err" role="alert">
            {cycleError}
          </p>
        ) : null}
        <div className="st-savebar">
          <span className="st-meta">{cyclesDirty ? '저장되지 않은 변경 사항이 있어요' : '변경 사항 없음'}</span>
          <button type="button" className="st-btn p" disabled={!cyclesDirty || cycleBusy} onClick={() => void saveCycles()}>
            {cycleBusy ? '저장 중…' : '기본 주기 저장'}
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
  const label = (k: string) => `${isNew ? '새 메뉴' : v.name} ${k}`;
  return (
    <div className="st-row st-edit">
      <input className="st-fld grow" placeholder="시술명" aria-label={label('시술명')} maxLength={100} value={v.name} onChange={(e) => onChange({ name: e.target.value })} autoFocus />
      <input className="st-time num" inputMode="numeric" placeholder="가격(원)" aria-label={label('가격')} value={v.price} onChange={(e) => onChange({ price: e.target.value })} />
      <select className="st-time" aria-label={label('소요 시간')} value={v.durationMinutes} onChange={(e) => onChange({ durationMinutes: Number(e.target.value) })}>
        {DURATION_STEPS.map((m) => (
          <option key={m} value={m}>
            {formatDuration(m)}
          </option>
        ))}
      </select>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        <input className="st-time num" inputMode="numeric" aria-label={label('기본 주기 최소(일)')} value={v.cycleMinDays} onChange={(e) => onChange({ cycleMinDays: e.target.value })} />~
        <input className="st-time num" inputMode="numeric" aria-label={label('기본 주기 최대(일)')} value={v.cycleMaxDays} onChange={(e) => onChange({ cycleMaxDays: e.target.value })} />일
      </span>
      <select
        className="st-time"
        aria-label={label('예측 속성')}
        value={v.predictionAttribute}
        onChange={(e) => onChange({ predictionAttribute: e.target.value as PredictionAttribute })}
      >
        {ATTRIBUTES.map(([value, text]) => (
          <option key={value} value={value}>
            {text}
          </option>
        ))}
      </select>
      {editing.confirm ? (
        <div className="st-note" style={{ flexBasis: '100%' }} role="alert">
          기본 주기나 예측 속성을 바꾸면 <b>이 메뉴를 받은 고객 전체의 예측이 다시 계산돼요.</b> 저장할까요?
        </div>
      ) : null}
      {editing.error ? (
        <p className="st-err" style={{ flexBasis: '100%' }} role="alert">
          {editing.error}
        </p>
      ) : null}
      <span style={{ display: 'inline-flex', gap: 8 }}>
        <button type="button" className="st-btn" disabled={editing.busy} onClick={onCancel}>
          취소
        </button>
        <button type="button" className="st-btn p" disabled={editing.busy} onClick={onSave}>
          {editing.busy ? '저장 중…' : editing.confirm ? '예, 저장' : isNew ? '추가' : '저장'}
        </button>
      </span>
    </div>
  );
}
