import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { api } from '@/lib/api';
import { CATEGORIES, CATEGORY_LABEL } from '@/lib/customerDetail';
import type { Category, CustomerDetail, PreferenceTag } from '../types';
import { AreaError, errorText } from './common';
import { t as tr } from '@/lib/i18n';

// 취향 프로필 (FR-CUS-04) · 주의사항 (FR-CUS-05, D7). 둘 다 CUSTOMER_WRITE, 통째로 PUT.

/** 취향 태그 · 자유 메모. 태그는 매장에서 쓴 것을 추천한다 (GET /api/preference-tags) */
export function PreferenceCard(props: { customerId: number; detail: CustomerDetail; onSaved: () => Promise<void> }) {
  const [editing, setEditing] = useState(false);
  const [tags, setTags] = useState<PreferenceTag[]>(props.detail.preferenceTags);
  const [memo, setMemo] = useState(props.detail.preferenceMemo ?? '');
  const [category, setCategory] = useState<Category>('NAIL');
  const [tag, setTag] = useState('');
  const [suggest, setSuggest] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!editing) return;
    api<{ tags: string[] }>(`/api/preference-tags?category=${category}`, { silent: true })
      .then((r) => setSuggest(r.tags))
      .catch(() => setSuggest([]));
  }, [editing, category]);

  function start() {
    setTags(props.detail.preferenceTags);
    setMemo(props.detail.preferenceMemo ?? '');
    setError('');
    setEditing(true);
  }

  function add() {
    const t = tag.trim();
    if (!t || tags.some((x) => x.category === category && x.tag.toLowerCase() === t.toLowerCase())) return setTag('');
    setTags([...tags, { category, tag: t }]);
    setTag('');
  }

  async function save() {
    setBusy(true);
    setError('');
    try {
      await api(`/api/customers/${props.customerId}/preferences`, { method: 'PUT', body: { tags, memo: memo.trim() || null }, silent: true });
      toast(tr('customer.profile.saved'));
      setEditing(false);
      await props.onSaved();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  const shown = editing ? tags : props.detail.preferenceTags;
  const byCategory = CATEGORIES.map((c) => [c, shown.filter((t) => t.category === c)] as const).filter(([, list]) => list.length);

  return (
    <section className="card col" style={{ gap: '12px' }}>
      <div className="row between">
        <h2 className="h2">{tr('customer.profile.title')}</h2>
        {!editing && (
          <button type="button" className="lk" onClick={start}>
            {tr('customer.edit')}
          </button>
        )}
      </div>
      {byCategory.length === 0 && !editing && <p className="meta">{tr('customer.profile.empty')}</p>}
      {byCategory.map(([c, list]) => (
        <div className="row" style={{ gap: 6 }} key={c}>
          <span className="meta" style={{ minWidth: 48 }}>
            {CATEGORY_LABEL[c]}
          </span>
          {list.map((t) => (
            <span className="chip" style={{ background: '#F9EBE8', color: '#7A4450' }} key={t.tag}>
              {t.tag}
              {editing && (
                <button
                  type="button"
                  className="lk"
                  aria-label={tr('customer.profile.removeTag', { tag: t.tag })}
                  onClick={() => setTags(tags.filter((x) => x !== t))}
                  style={{ fontSize: 12 }}
                >
                  ×
                </button>
              )}
            </span>
          ))}
        </div>
      ))}
      {editing ? (
        <>
          <div className="row" style={{ gap: 8 }}>
            <select className="sel" value={category} onChange={(e) => setCategory(e.target.value as Category)} aria-label={tr('customer.profile.tagCategory')}>
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {CATEGORY_LABEL[c]}
                </option>
              ))}
            </select>
            <input
              className="fld"
              style={{ flex: 1, minWidth: 140, width: 'auto' }}
              list="pref-tags"
              value={tag}
              maxLength={50}
              placeholder={tr('customer.profile.tagPlaceholder')}
              onChange={(e) => setTag(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), add())}
              aria-label={tr('customer.profile.tagLabel')}
            />
            <datalist id="pref-tags">
              {suggest.map((s) => (
                <option key={s} value={s} />
              ))}
            </datalist>
            <button type="button" className="btn" onClick={add}>
              {tr('customer.profile.add')}
            </button>
          </div>
          <label className="col" style={{ gap: 6 }}>
            <span className="lbl">{tr('customer.profile.freeMemo')}</span>
            <textarea className="fld" rows={3} maxLength={1000} value={memo} onChange={(e) => setMemo(e.target.value)} />
          </label>
          <AreaError text={error} />
          <div className="row" style={{ gap: 8 }}>
            <button type="button" className="btn btn-p" onClick={save} disabled={busy}>
              {tr('customer.save')}
            </button>
            <button type="button" className="btn" onClick={() => setEditing(false)} disabled={busy}>
              {tr('common.cancel')}
            </button>
          </div>
        </>
      ) : (
        props.detail.preferenceMemo && <p style={{ fontSize: 13, whiteSpace: 'pre-wrap' }}>{props.detail.preferenceMemo}</p>
      )}
    </section>
  );
}

/** 시술 · 건강 주의사항. 건강 주의사항은 건강 정보 동의 고객만 쓸 수 있다 (서버가 consent_required) */
export function CautionCard(props: { customerId: number; detail: CustomerDetail; healthAgreed: boolean; onSaved: () => Promise<void> }) {
  const [editing, setEditing] = useState(false);
  const [service, setService] = useState('');
  const [health, setHealth] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  function start() {
    setService(props.detail.serviceCaution ?? '');
    setHealth(props.detail.healthCaution ?? '');
    setError('');
    setEditing(true);
  }

  async function save() {
    setBusy(true);
    setError('');
    try {
      await api(`/api/customers/${props.customerId}/cautions`, {
        method: 'PUT',
        body: { serviceCaution: service.trim() || null, healthCaution: props.healthAgreed ? health.trim() || null : null },
        silent: true,
      });
      toast(tr('customer.caution.saved'));
      setEditing(false);
      await props.onSaved();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card col" style={{ gap: '10px' }}>
      <div className="row between">
        <h2 className="h2">{tr('customer.caution.title')}</h2>
        {!editing && (
          <button type="button" className="lk" onClick={start}>
            {tr('customer.edit')}
          </button>
        )}
      </div>
      <div className="pcard">
        <strong style={{ fontSize: '13px' }}>{tr('customer.caution.service')}</strong>
        {editing ? (
          <textarea className="fld" rows={3} maxLength={1000} value={service} onChange={(e) => setService(e.target.value)} aria-label={tr('customer.caution.service')} />
        ) : (
          <span style={{ fontSize: '13px', whiteSpace: 'pre-wrap' }}>{props.detail.serviceCaution ?? <span className="meta">{tr('customer.none')}</span>}</span>
        )}
      </div>
      <div className="pcard">
        <strong style={{ fontSize: '13px' }}>{tr('customer.caution.health')}</strong>
        {!props.healthAgreed ? (
          <span className="meta">{tr('customer.caution.needConsent')}</span>
        ) : editing ? (
          <textarea className="fld" rows={3} maxLength={1000} value={health} onChange={(e) => setHealth(e.target.value)} aria-label={tr('customer.caution.health')} />
        ) : (
          <span style={{ fontSize: '13px', whiteSpace: 'pre-wrap' }}>{props.detail.healthCaution ?? <span className="meta">{tr('customer.none')}</span>}</span>
        )}
      </div>
      <AreaError text={error} />
      {editing && (
        <div className="row" style={{ gap: 8 }}>
          <button type="button" className="btn btn-p" onClick={save} disabled={busy}>
            {tr('customer.save')}
          </button>
          <button type="button" className="btn" onClick={() => setEditing(false)} disabled={busy}>
            {tr('common.cancel')}
          </button>
        </div>
      )}
    </section>
  );
}
