import { useEffect, useRef, useState, type FormEvent } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import { toast } from '@/components/common/Toast';
import { api, ApiError, type Page } from '@/lib/api';
import {
  allowedVariables,
  countBytes,
  disallowedVariables,
  MAX_BODY_BYTES,
  messageKind,
  PURPOSES,
  type MessageKind,
  type TemplatePurpose,
} from '@/lib/messageTemplate';
import styles from './TemplatesPage.module.css';

// 템플릿 관리 (FR-CMP-03). GET · POST · PATCH · DELETE /api/message-templates.
// 보기는 CAMPAIGN_VIEW, 작성 · 수정 · 삭제는 CAMPAIGN_SEND 권한을 서버가 검사한다 (NFR-04).

type Template = { id: number; name: string; body: string; purpose: TemplatePurpose; messageKind: MessageKind };
type Field = 'name' | 'body' | 'purpose';

const PURPOSE_LABEL = Object.fromEntries(PURPOSES) as Record<TemplatePurpose, string>;
const KIND_LABEL: Record<MessageKind, string> = { AD: '광고', INFO: '안내' };
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
      const next = page.items.find((t) => t.id === select) ?? page.items[0];
      if (next) open(next);
      else startNew();
    } catch (err) {
      setLoadError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
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
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
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
        toast(`'${body.name}' 템플릿을 만들었어요.`);
        await load(id);
      } else {
        await api(`/api/message-templates/${selected}`, { method: 'PATCH', body, silent: true });
        toast(`'${body.name}' 템플릿을 저장했어요.`);
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
      toast(`'${form.name}' 템플릿을 지웠어요.`);
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
    <div style={{ display: 'flex', flexWrap: 'wrap', minHeight: '1020px' }}>
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="campaigns" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: '0', display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb="Campaign / 템플릿 관리" />
        <div className={styles.wrap}>
          <div className={styles.head}>
            <div>
              <h1 className={styles.title}>템플릿 관리</h1>
              <p className={styles.sub}>캠페인 문구의 틀을 만들어 두고, 캠페인을 만들 때 골라 써요.</p>
            </div>
            <div className={styles.headActions}>
              <AppLink href="/campaigns" className={styles.secondary}>
                캠페인으로
              </AppLink>
              <button type="button" className={styles.primary} onClick={startNew}>
                새 템플릿
              </button>
            </div>
          </div>

          {loadError ? (
            <p className={styles.error} role="alert">
              {loadError}
            </p>
          ) : (
            <div className={styles.layout}>
              <section className={styles.list} aria-label="템플릿 목록">
                {items === null ? (
                  <p className={styles.empty}>불러오는 중…</p>
                ) : items.length === 0 ? (
                  <p className={styles.empty}>아직 템플릿이 없어요.</p>
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
                      <span className={styles.purpose}>{PURPOSE_LABEL[t.purpose]}</span>
                      <span className={styles.preview}>{t.body}</span>
                    </button>
                  ))
                )}
              </section>

              {selected !== null && (
                <form className={styles.editor} onSubmit={onSubmit}>
                  <h2 className={styles.cardTitle}>{selected === 'new' ? '새 템플릿' : '템플릿 수정'}</h2>

                  <label className={styles.field}>
                    <span className={styles.label}>
                      이름 <em>필수</em>
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
                      용도 <em>필수</em>
                    </span>
                    <div className={styles.segment} role="radiogroup" aria-label="용도">
                      {PURPOSES.map(([value, label]) => (
                        <button
                          key={value}
                          type="button"
                          role="radio"
                          aria-checked={form.purpose === value}
                          onClick={() => setForm({ ...form, purpose: value })}
                        >
                          {label}
                        </button>
                      ))}
                    </div>
                    <span className={styles.hint}>
                      메시지 종류: <b>{KIND_LABEL[kind]}</b>
                      {kind === 'AD'
                        ? ' — 보낼 때 (광고) 표기 같은 광고 문구가 붙고, 마케팅 수신 동의 고객에게만 가요.'
                        : ' — 동의 재확인 안내라 광고 표기 없이 보내요.'}
                    </span>
                    {errors.purpose && <span className={styles.fieldError}>{errors.purpose}</span>}
                  </div>

                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="template-body">
                      본문 <em>필수</em>
                    </label>
                    <div className={styles.vars} aria-label="변수 넣기">
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
                          `이 용도에서 쓸 수 없는 변수: ${wrongVars.map((v) => `{${v}}`).join(' ')}`}
                      </span>
                      <span className={bytes > MAX_BODY_BYTES ? styles.over : undefined}>
                        {bytes.toLocaleString()} / {MAX_BODY_BYTES.toLocaleString()}바이트
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
                          이 템플릿을 지울까요?
                          <button type="button" className={styles.danger} onClick={onDelete} disabled={pending}>
                            지우기
                          </button>
                          <button type="button" className={styles.secondary} onClick={() => setConfirmDelete(false)}>
                            그대로 두기
                          </button>
                        </span>
                      ) : (
                        <button type="button" className={styles.textButton} onClick={() => setConfirmDelete(true)}>
                          삭제
                        </button>
                      ))}
                    <button
                      className={styles.primary}
                      type="submit"
                      disabled={pending || wrongVars.length > 0 || bytes > MAX_BODY_BYTES}
                    >
                      {pending ? '저장 중…' : selected === 'new' ? '만들기' : '저장'}
                    </button>
                  </div>
                </form>
              )}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
