import { useEffect, useRef, useState, type FormEvent } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { toast } from '@/components/common/Toast';
import { api, ApiError, type Page } from '@/lib/api';
import {
  allowedVariables,
  countBytes,
  disallowedVariables,
  MAX_BODY_BYTES,
  messageKind,
  PURPOSES,
  purposeLabel,
  type MessageKind,
  type TemplatePurpose,
} from '@/lib/messageTemplate';
import styles from './TemplatesPage.module.css';
import { t as tr } from '@/lib/i18n';

// 템플릿 관리 (FR-CMP-03). GET · POST · PATCH · DELETE /api/message-templates.
// 보기는 CAMPAIGN_VIEW, 작성 · 수정 · 삭제는 CAMPAIGN_SEND 권한을 서버가 검사한다 (NFR-04).

type Template = { id: number; name: string; body: string; purpose: TemplatePurpose; messageKind: MessageKind };
type Field = 'name' | 'body' | 'purpose';

const KIND_LABEL: Record<MessageKind, string> = {
  get AD() {
    return tr('campaign.kind.ad');
  },
  get INFO() {
    return tr('campaign.kind.info');
  },
};
const EMPTY = { name: '', body: '', purpose: 'REVISIT' as TemplatePurpose };

export default function TemplatesPage() {
  const [items, setItems] = useState<Template[] | null>(null);
  const [loadError, setLoadError] = useState('');
  const [selected, setSelected] = useState<number | 'new' | null>(null);
  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const bodyRef = useRef<HTMLTextAreaElement>(null);

  /** 목록을 다시 받고, select 템플릿(없으면 첫 템플릿)을 연다 */
  async function load(select?: number) {
    try {
      const page = await api<Page<Template>>('/api/message-templates?page=0&size=100', { silent: true });
      setItems(page.items);
      setLoadError('');
      const next = page.items.find((t) => t.id === select) ?? page.items[0];
      if (next) open(next);
      else startNew();
    } catch (err) {
      setLoadError(err instanceof ApiError ? err.message : tr('common.pin.tryLater'));
    }
  }
  const loadRef = useRef(load);

  useEffect(() => {
    loadRef.current();
  }, []);

  function reset() {
    setErrors({});
    setError('');
    setConfirmDelete(false);
  }

  function open(t: Template) {
    reset();
    setSelected(t.id);
    setForm({ name: t.name, body: t.body, purpose: t.purpose });
  }

  function startNew() {
    reset();
    setSelected('new');
    setForm(EMPTY);
  }

  /** 커서 자리에 변수를 넣는다 */
  function insert(variable: string) {
    const el = bodyRef.current;
    const token = `{${variable}}`;
    const start = el?.selectionStart ?? form.body.length;
    const end = el?.selectionEnd ?? form.body.length;
    setForm((f) => ({ ...f, body: f.body.slice(0, start) + token + f.body.slice(end) }));
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(start + token.length, start + token.length);
    });
  }

  function fail(err: unknown) {
    if (err instanceof ApiError && err.code === 'invalid' && Array.isArray(err.details)) {
      const byField: Partial<Record<Field, string>> = {};
      for (const d of err.details as { field: string; reason: string }[]) byField[d.field as Field] ??= d.reason;
      setErrors(byField);
    } else {
      setError(err instanceof ApiError ? err.message : tr('common.pin.tryLater'));
    }
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (pending || selected === null || wrongVars.length > 0 || bytes > MAX_BODY_BYTES) return;
    reset();
    setPending(true);
    const body = { name: form.name.trim(), body: form.body, purpose: form.purpose };
    try {
      if (selected === 'new') {
        const { id } = await api<{ id: number }>('/api/message-templates', { method: 'POST', body, silent: true });
        toast(tr('campaign.tpl.created', { name: body.name }));
        await load(id);
      } else {
        await api(`/api/message-templates/${selected}`, { method: 'PATCH', body, silent: true });
        toast(tr('campaign.tpl.saved', { name: body.name }));
        await load(selected);
      }
    } catch (err) {
      fail(err);
    } finally {
      setPending(false);
    }
  }

  async function onDelete() {
    if (typeof selected !== 'number') return;
    setPending(true);
    try {
      await api(`/api/message-templates/${selected}`, { method: 'DELETE', silent: true });
      toast(tr('campaign.tpl.deleted', { name: form.name }));
      await load();
    } catch (err) {
      setConfirmDelete(false);
      fail(err);
    } finally {
      setPending(false);
    }
  }

  const bytes = countBytes(form.body);
  const wrongVars = disallowedVariables(form.body, form.purpose);
  const kind = messageKind(form.purpose);

  return (
    <>
      <div className={styles.wrap}>
        <div className={styles.head}>
          <div>
            <h1 className={styles.title}>{tr('campaign.tpl.title')}</h1>
            <p className={styles.sub}>{tr('campaign.tpl.sub')}</p>
          </div>
          <div className={styles.headActions}>
            <AppLink href="/campaigns" className={styles.secondary}>
              {tr('campaign.tpl.toCampaigns')}
            </AppLink>
            <button type="button" className={styles.primary} onClick={startNew}>
              {tr('campaign.tpl.new')}
            </button>
          </div>
        </div>

        {loadError && (
          <p className={styles.error} role="alert">
            {loadError}{' '}
            <button type="button" className="btn btn-sm" onClick={() => load(typeof selected === 'number' ? selected : undefined)}>
              {tr('common.retry')}
            </button>
          </p>
        )}
        {/* 저장 · 삭제 뒤 재조회만 실패하면 받아 둔 목록 · 편집 중인 내용은 그대로 둔다 */}
        {loadError && items === null ? null : (
          <div className={styles.layout}>
            <section className={styles.list} aria-label={tr('campaign.tpl.listLabel')}>
              {items === null ? (
                <p className={styles.empty}>{tr('common.loading')}</p>
              ) : items.length === 0 ? (
                <p className={styles.empty}>{tr('campaign.tpl.empty')}</p>
              ) : (
                items.map((t) => (
                  <button
                    key={t.id}
                    type="button"
                    className={styles.item}
                    aria-current={selected === t.id ? 'true' : undefined}
                    onClick={() => open(t)}
                  >
                    <span className={styles.itemHead}>
                      <b>{t.name}</b>
                      <span className={styles.kind} data-kind={t.messageKind}>
                        {KIND_LABEL[t.messageKind]}
                      </span>
                    </span>
                    <span className={styles.purpose}>{purposeLabel(t.purpose)}</span>
                    <span className={styles.preview}>{t.body}</span>
                  </button>
                ))
              )}
            </section>

            {selected !== null && (
              <form className={styles.editor} onSubmit={onSubmit}>
                <h2 className={styles.cardTitle}>{selected === 'new' ? tr('campaign.tpl.new') : tr('campaign.tpl.edit')}</h2>

                <label className={styles.field}>
                  <span className={styles.label}>
                    {tr('campaign.tpl.name')} <em>{tr('customer.required')}</em>
                  </span>
                  <input
                    className={styles.input}
                    value={form.name}
                    maxLength={100}
                    required
                    onChange={(e) => setForm({ ...form, name: e.target.value })}
                    aria-invalid={Boolean(errors.name)}
                  />
                  {errors.name && <span className={styles.fieldError}>{errors.name}</span>}
                </label>

                <div className={styles.field}>
                  <span className={styles.label}>
                    {tr('campaign.tpl.purpose')} <em>{tr('customer.required')}</em>
                  </span>
                  <div className={styles.segment} role="radiogroup" aria-label={tr('campaign.tpl.purpose')}>
                    {PURPOSES.map(([value, label]) => (
                      <button
                        key={value}
                        type="button"
                        role="radio"
                        aria-checked={form.purpose === value}
                        onClick={() => setForm({ ...form, purpose: value })}
                      >
                        {tr(label)}
                      </button>
                    ))}
                  </div>
                  <span className={styles.hint}>
                    {tr('campaign.tpl.kindLabel')}<b>{KIND_LABEL[kind]}</b>
                    {kind === 'AD'
                      ? tr('campaign.tpl.kindAd')
                      : tr('campaign.tpl.kindInfo')}
                  </span>
                  {errors.purpose && <span className={styles.fieldError}>{errors.purpose}</span>}
                </div>

                <div className={styles.field}>
                  <label className={styles.label} htmlFor="template-body">
                    {tr('campaign.tpl.body')} <em>{tr('customer.required')}</em>
                  </label>
                  <div className={styles.vars} aria-label={tr('campaign.tpl.vars')}>
                    {allowedVariables(form.purpose).map((v) => (
                      <button key={v} type="button" onClick={() => insert(v)}>
                        {`{${v}}`}
                      </button>
                    ))}
                  </div>
                  <textarea
                    id="template-body"
                    ref={bodyRef}
                    className={`${styles.input} ${styles.textarea}`}
                    value={form.body}
                    required
                    rows={7}
                    onChange={(e) => setForm({ ...form, body: e.target.value })}
                    aria-invalid={Boolean(errors.body) || wrongVars.length > 0 || bytes > MAX_BODY_BYTES}
                  />
                  <div className={styles.bodyMeta}>
                    <span>
                      {wrongVars.length > 0 &&
                        tr('campaign.tpl.wrongVars', { vars: wrongVars.map((v) => `{${v}}`).join(' ') })}
                    </span>
                    <span className={bytes > MAX_BODY_BYTES ? styles.over : undefined}>
                      {tr('campaign.tpl.bytes', { n: bytes.toLocaleString(), max: MAX_BODY_BYTES.toLocaleString() })}
                    </span>
                  </div>
                  {errors.body && <span className={styles.fieldError}>{errors.body}</span>}
                </div>

                {error && (
                  <p className={styles.error} role="alert">
                    {error}
                  </p>
                )}

                <div className={styles.actions}>
                  {typeof selected === 'number' &&
                    (confirmDelete ? (
                      <span className={styles.confirm}>
                        {tr('campaign.tpl.askDelete')}
                        <button type="button" className={styles.danger} onClick={onDelete} disabled={pending}>
                          {tr('campaign.tpl.doDelete')}
                        </button>
                        <button type="button" className={styles.secondary} onClick={() => setConfirmDelete(false)}>
                          {tr('campaign.tpl.keep')}
                        </button>
                      </span>
                    ) : (
                      <button type="button" className={styles.textButton} onClick={() => setConfirmDelete(true)}>
                        {tr('campaign.tpl.deleteBtn')}
                      </button>
                    ))}
                  <button
                    className={styles.primary}
                    type="submit"
                    disabled={pending || wrongVars.length > 0 || bytes > MAX_BODY_BYTES}
                  >
                    {pending ? tr('common.saving') : selected === 'new' ? tr('campaign.tpl.create') : tr('campaign.tpl.save')}
                  </button>
                </div>
              </form>
            )}
          </div>
        )}
      </div>
    </>
  );
}
