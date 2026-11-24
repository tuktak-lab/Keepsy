import { useEffect, useMemo, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { ApiError, api } from '@/lib/api';
import { CONSENT_REQUEST_LABEL, PHOTO_MAX, validatePhotos } from '@/lib/closing';
import { CONSENT_METHODS, type ConsentMethod } from '@/lib/newCustomer';

// 시술 마감 카드의 동의 받기 · 사진 (FR-PAY-04, FR-CUS-08 · 09). 고객이 있는 마감에서만 쓴다 (고객 없음 마감은 없음, E3).
// - 동의 받기: 항목마다 바로 저장한다 (POST /api/customers/{customerId}/consents, CONSENT 공용 고정). 마감 저장과 따로다 (F9)
// - 사진: 여기서는 고르기 · 미리보기만 한다. 올리기는 마감 저장 뒤 방문 ID로 한다 (POST /api/visits/{visitId}/photos)

/** 아직 동의하지 않은 항목의 동의 받기. 받으면 onAgreed로 알린다 (사진 동의면 사진 칸이 열린다) */
export function ConsentRequests(props: { customerId: number; items: string[]; onAgreed: (item: string) => void }) {
  const [method, setMethod] = useState<ConsentMethod>('VERBAL');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');

  if (props.items.length === 0) return null;

  async function agree(item: string) {
    setBusy(item);
    setError('');
    try {
      await api(`/api/customers/${props.customerId}/consents`, { method: 'POST', body: { item, agreed: true, method }, silent: true });
      toast(`${CONSENT_REQUEST_LABEL[item]?.label ?? item} 동의를 받았어요`);
      props.onAgreed(item);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
    } finally {
      setBusy(null);
    }
  }

  return (
    <div>
      <span className="lbl">동의 받기 (아직 동의하지 않은 항목)</span>
      <div className="pills" role="group" aria-label="동의 받은 방법" style={{ marginBottom: 8 }}>
        {CONSENT_METHODS.map(([value, label]) => (
          <button key={value} type="button" className="pill" aria-pressed={method === value} onClick={() => setMethod(value)}>
            {label}
          </button>
        ))}
      </div>
      <ul className="svc-list">
        {props.items.map((item) => {
          const info = CONSENT_REQUEST_LABEL[item] ?? { label: item, desc: '' };
          return (
            <li key={item} className="svc-row">
              <span className="svc-name">
                <b>{info.label}</b>
                <span className="meta">{info.desc}</span>
              </span>
              <button type="button" className="btn btn-sm" disabled={busy !== null} onClick={() => void agree(item)}>
                {busy === item ? '저장 중…' : '동의 받음'}
              </button>
            </li>
          );
        })}
      </ul>
      <p className="meta" style={{ marginTop: 6 }}>
        손님에게 내용을 안내하고 동의를 받은 항목만 누르세요. 누르면 바로 저장돼요.
      </p>
      {error ? (
        <span className="err" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}

/** 사진 고르기 · 미리보기. 실제 올리기는 마감 저장 뒤에 한다 */
export function PhotoPicker(props: { files: File[]; onChange: (files: File[]) => void; disabled?: boolean }) {
  const [error, setError] = useState('');
  // 미리보기 주소: 파일 목록이 바뀌면 새로 만들고, 이전 주소는 정리한다
  const previews = useMemo(() => props.files.map((f) => URL.createObjectURL(f)), [props.files]);
  useEffect(() => () => previews.forEach((u) => URL.revokeObjectURL(u)), [previews]);

  const add = (picked: FileList | null) => {
    if (!picked || picked.length === 0) return;
    const next = [...props.files, ...Array.from(picked)];
    const problem = validatePhotos(next);
    if (problem) return setError(problem);
    setError('');
    props.onChange(next);
  };

  return (
    <div>
      <span className="lbl">
        시술 사진 (선택, JPG · PNG · {PHOTO_MAX}장까지)
      </span>
      {props.files.length > 0 ? (
        <ul style={{ display: 'flex', flexWrap: 'wrap', gap: 8, listStyle: 'none', margin: '0 0 8px', padding: 0 }}>
          {props.files.map((f, i) => (
            <li key={`${f.name}-${i}`} style={{ position: 'relative' }}>
              {/* eslint-disable-next-line @next/next/no-img-element -- 로컬 미리보기(blob URL)라 next/image를 쓰지 않는다 */}
              <img src={previews[i]} alt={`고른 사진 ${i + 1}`} style={{ width: 88, height: 88, objectFit: 'cover', borderRadius: 12, display: 'block', background: 'rgba(255,255,255,.55)', fontSize: 11, overflow: 'hidden' }}
              />
              <button
                type="button"
                className="ib"
                aria-label={`사진 ${i + 1} 빼기`}
                disabled={props.disabled}
                style={{ position: 'absolute', top: 4, right: 4, width: 24, height: 24, padding: 0 }}
                onClick={() => {
                  setError('');
                  props.onChange(props.files.filter((_, j) => j !== i));
                }}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <label className="btn btn-sm" style={{ cursor: props.disabled ? 'default' : 'pointer' }}>
        사진 고르기
        <input
          type="file"
          accept="image/jpeg,image/png"
          multiple
          disabled={props.disabled}
          style={{ display: 'none' }}
          onChange={(e) => {
            add(e.target.files);
            e.target.value = '';
          }}
        />
      </label>
      <p className="meta" style={{ marginTop: 6 }}>
        사진은 시술 마감을 저장한 뒤에 올라가요.
      </p>
      {error ? (
        <span className="err" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}

/** 방문에 사진을 한 장씩 올린다. 실패한 사진과 사유를 돌려준다 (성공한 사진은 다시 올리지 않게) */
export async function uploadPhotos(visitId: number, files: File[]): Promise<{ file: File; reason: string }[]> {
  const failed: { file: File; reason: string }[] = [];
  for (const file of files) {
    const form = new FormData();
    form.append('file', file);
    try {
      await api(`/api/visits/${visitId}/photos`, { method: 'POST', body: form, silent: true });
    } catch (err) {
      failed.push({ file, reason: err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요' });
    }
  }
  return failed;
}
