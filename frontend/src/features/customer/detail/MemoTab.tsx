import { useState } from 'react';
import { useLoad } from '@/hooks/useLoad';
import { toast } from '@/components/common/Toast';
import { api, type Page } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import type { ConsultNote } from '../types';
import { AreaError, errorText } from './common';
import { t } from '@/lib/i18n';

// 상담 메모 (FR-CUS-06). 작성은 CUSTOMER_WRITE(작성자 · 시각은 서버가 기록), 수정 · 삭제는 CUSTOMER_EDIT(기본 PIN).

export function MemoTab(props: { customerId: number }) {
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState<number | null>(null);
  const [editText, setEditText] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState('');

  const list = useLoad(String(props.customerId), () =>
    api<Page<ConsultNote>>(`/api/customers/${props.customerId}/consult-notes?size=100`, { silent: true }),
  );
  const notes = list.data?.items ?? null;
  const load = list.reload;

  async function run(action: () => Promise<unknown>, done: string) {
    setBusy(true);
    setActionError('');
    try {
      await action();
      toast(done);
      await load();
      return true;
    } catch (err) {
      setActionError(errorText(err));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function create() {
    if (!draft.trim()) return;
    if (await run(() => api(`/api/customers/${props.customerId}/consult-notes`, { method: 'POST', body: { content: draft }, silent: true }), t('customer.memo.saved')))
      setDraft('');
  }

  async function update(noteId: number) {
    if (await run(() => api(`/api/consult-notes/${noteId}`, { method: 'PATCH', body: { content: editText }, silent: true }), t('customer.memo.edited')))
      setEditing(null);
  }

  return (
    <section className="card col" style={{ gap: 12 }}>
      <h2 className="h2">{t('customer.memo.title')}</h2>
      <label className="col" style={{ gap: 6 }}>
        <span className="lbl">{t('customer.memo.new')}</span>
        <textarea
          className="fld"
          rows={3}
          maxLength={2000}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={t('customer.memo.placeholder')}
        />
      </label>
      <div>
        <button type="button" className="btn btn-p" onClick={create} disabled={busy || !draft.trim()}>
          {t('customer.memo.saveBtn')}
        </button>
      </div>
      <AreaError text={actionError} />
      <AreaError text={list.error ? errorText(list.error) : ''} onRetry={load} />
      {notes?.length === 0 && <p className="meta">{t('customer.memo.empty')}</p>}
      {notes?.map((n) => (
        <div className="consent col" key={n.noteId} style={{ gap: 6 }}>
          <span className="row between">
            <span className="meta num">
              {formatDateTime(n.writtenAt)} · {n.staff.name}
            </span>
            {editing !== n.noteId && (
              <span className="row" style={{ gap: 10 }}>
                <button
                  type="button"
                  className="lk"
                  onClick={() => {
                    setEditing(n.noteId);
                    setEditText(n.content);
                  }}
                >
                  {t('customer.edit')}
                </button>
                <button
                  type="button"
                  className="lk"
                  style={{ color: '#8A2F3F' }}
                  disabled={busy}
                  onClick={() => run(() => api(`/api/consult-notes/${n.noteId}`, { method: 'DELETE', silent: true }), t('customer.memo.deleted'))}
                >
                  {t('customer.delete')}
                </button>
              </span>
            )}
          </span>
          {editing === n.noteId ? (
            <>
              <textarea className="fld" rows={3} maxLength={2000} value={editText} onChange={(e) => setEditText(e.target.value)} aria-label={t('customer.memo.editAria')} />
              <span className="row" style={{ gap: 8 }}>
                <button type="button" className="btn btn-p" onClick={() => update(n.noteId)} disabled={busy || !editText.trim()}>
                  {t('customer.save')}
                </button>
                <button type="button" className="btn" onClick={() => setEditing(null)}>
                  {t('common.cancel')}
                </button>
              </span>
            </>
          ) : (
            <span style={{ whiteSpace: 'pre-wrap' }}>{n.content}</span>
          )}
        </div>
      ))}
      <p className="meta">{t('customer.memo.healthNote')}</p>
    </section>
  );
}
