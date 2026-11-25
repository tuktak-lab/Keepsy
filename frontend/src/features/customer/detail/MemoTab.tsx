import { useCallback, useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { api, type Page } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import type { ConsultNote } from '../types';
import { AreaError, errorText } from './common';

// 상담 메모 (FR-CUS-06). 작성은 CUSTOMER_WRITE(작성자 · 시각은 서버가 기록), 수정 · 삭제는 CUSTOMER_EDIT(기본 PIN).

export function MemoTab(props: { customerId: number }) {
  const [notes, setNotes] = useState<ConsultNote[] | null>(null);
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState<number | null>(null);
  const [editText, setEditText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      setNotes((await api<Page<ConsultNote>>(`/api/customers/${props.customerId}/consult-notes?size=100`, { silent: true })).items);
    } catch (err) {
      setError(errorText(err));
    }
  }, [props.customerId]);

  useEffect(() => {
    api<Page<ConsultNote>>(`/api/customers/${props.customerId}/consult-notes?size=100`, { silent: true })
      .then((r) => setNotes(r.items))
      .catch((err) => setError(errorText(err)));
  }, [props.customerId]);

  async function run(action: () => Promise<unknown>, done: string) {
    setBusy(true);
    setError('');
    try {
      await action();
      toast(done);
      await load();
      return true;
    } catch (err) {
      setError(errorText(err));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function create() {
    if (!draft.trim()) return;
    if (await run(() => api(`/api/customers/${props.customerId}/consult-notes`, { method: 'POST', body: { content: draft }, silent: true }), '상담 메모를 저장했어요'))
      setDraft('');
  }

  async function update(noteId: number) {
    if (await run(() => api(`/api/consult-notes/${noteId}`, { method: 'PATCH', body: { content: editText }, silent: true }), '상담 메모를 고쳤어요'))
      setEditing(null);
  }

  return (
    <section className="card col" style={{ gap: 12 }}>
      <h2 className="h2">상담 메모</h2>
      <label className="col" style={{ gap: 6 }}>
        <span className="lbl">새 메모</span>
        <textarea
          className="fld"
          rows={3}
          maxLength={2000}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="상담 내용과 다음에 해보고 싶은 디자인을 남겨 주세요."
        />
      </label>
      <div>
        <button type="button" className="btn btn-p" onClick={create} disabled={busy || !draft.trim()}>
          메모 저장
        </button>
      </div>
      <AreaError text={error} />
      {notes?.length === 0 && <p className="meta">아직 상담 메모가 없어요.</p>}
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
                  수정
                </button>
                <button
                  type="button"
                  className="lk"
                  style={{ color: '#8A2F3F' }}
                  disabled={busy}
                  onClick={() => run(() => api(`/api/consult-notes/${n.noteId}`, { method: 'DELETE', silent: true }), '상담 메모를 지웠어요')}
                >
                  삭제
                </button>
              </span>
            )}
          </span>
          {editing === n.noteId ? (
            <>
              <textarea className="fld" rows={3} maxLength={2000} value={editText} onChange={(e) => setEditText(e.target.value)} aria-label="메모 수정" />
              <span className="row" style={{ gap: 8 }}>
                <button type="button" className="btn btn-p" onClick={() => update(n.noteId)} disabled={busy || !editText.trim()}>
                  저장
                </button>
                <button type="button" className="btn" onClick={() => setEditing(null)}>
                  취소
                </button>
              </span>
            </>
          ) : (
            <span style={{ whiteSpace: 'pre-wrap' }}>{n.content}</span>
          )}
        </div>
      ))}
      <p className="meta">건강 관련 내용은 주의사항의 건강 주의사항 칸에 따로 적어 주세요. 수정 · 삭제는 PIN이 필요할 수 있어요.</p>
    </section>
  );
}
