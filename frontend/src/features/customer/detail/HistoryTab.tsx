import { useCallback, useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { api, type Page } from '@/lib/api';
import { CATEGORY_LABEL, dotDate } from '@/lib/customerDetail';
import { formatDateTime } from '@/lib/format';
import type { HistoryVisit } from '../types';
import { AreaError, errorText } from './common';

// 시술 히스토리북 (FR-CUS-07 · 08, C6, A13). 방문별 시술 · 담당 · 사진 · 메모를 최신순으로, 사진만 모아 보는 갤러리 보기.
// 사진은 사진 동의 고객만 올린다 (서버가 consent_required). JPG · PNG, 장당 10MB, 방문당 10장은 서버가 다시 검사한다.

const PAGE = 10;
const MAX_BYTES = 10 * 1024 * 1024;

/**
 * 공용 utils/motion.ts는 src가 바뀐 이미지에 kp-img-wait(투명)를 붙이고 다음 프레임에 load 감시를 단다.
 * 로컬처럼 한 프레임 안에 다 받으면 load를 놓쳐 투명으로 남으므로, 사진은 직접 load에서 뗀다.
 */
const shown = (e: React.SyntheticEvent<HTMLImageElement>) => e.currentTarget.classList.remove('kp-img-wait');

export function HistoryTab(props: { customerId: number; photoAgreed: boolean }) {
  const [visits, setVisits] = useState<HistoryVisit[]>([]);
  const [total, setTotal] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [gallery, setGallery] = useState(false);
  const [uploading, setUploading] = useState<number | null>(null);

  // 서명 URL이 5분 뒤 만료되므로 올린 뒤에는 처음부터 다시 받는다
  const load = useCallback(
    async (pages: number) => {
      try {
        const r = await api<Page<HistoryVisit>>(`/api/customers/${props.customerId}/history?page=0&size=${pages * PAGE}`, { silent: true });
        setVisits(r.items);
        setTotal(r.totalElements);
        setError('');
      } catch (err) {
        setError(errorText(err));
      } finally {
        setLoaded(true);
      }
    },
    [props.customerId],
  );

  useEffect(() => {
    api<Page<HistoryVisit>>(`/api/customers/${props.customerId}/history?page=0&size=${PAGE}`, { silent: true })
      .then((r) => {
        setVisits(r.items);
        setTotal(r.totalElements);
      })
      .catch((err) => setError(errorText(err)))
      .finally(() => setLoaded(true));
  }, [props.customerId]);

  async function upload(visitId: number, files: FileList | null) {
    if (!files?.length) return;
    const list = [...files];
    const wrong = list.find((f) => !/\.(jpe?g|png)$/i.test(f.name) || f.size > MAX_BYTES);
    if (wrong) return setError(`${wrong.name}: JPG · PNG, 장당 10MB 이하만 올릴 수 있어요`);
    setUploading(visitId);
    setError('');
    let done = 0;
    try {
      for (const f of list) {
        const form = new FormData();
        form.append('file', f);
        await api(`/api/visits/${visitId}/photos`, { method: 'POST', body: form, silent: true });
        done++;
      }
      toast(`사진 ${done}장을 올렸어요`);
    } catch (err) {
      setError(`${done ? `${done}장 올린 뒤 ` : ''}${errorText(err)}`);
    } finally {
      setUploading(null);
      await load(Math.ceil(visits.length / PAGE) || 1);
    }
  }

  const photos = visits.flatMap((v) => v.photos.map((p) => ({ ...p, visitDate: v.visitDate })));

  return (
    <section className="card col">
      <div className="row between" style={{ paddingBottom: 8 }}>
        <h2 className="h2">시술 히스토리북 · 방문 {total}회</h2>
        <div className="tabs" role="tablist" aria-label="히스토리 보기">
          <button type="button" role="tab" aria-selected={!gallery} onClick={() => setGallery(false)}>
            방문별
          </button>
          <button type="button" role="tab" aria-selected={gallery} onClick={() => setGallery(true)}>
            사진 갤러리
          </button>
        </div>
      </div>
      {!props.photoAgreed && <p className="meta">사진 동의를 받지 않은 고객이라 사진은 보이지 않고 올릴 수 없어요.</p>}
      <AreaError text={error} />
      {loaded && visits.length === 0 && !error && <p className="meta">아직 방문 기록이 없어요.</p>}

      {gallery ? (
        photos.length === 0 ? (
          loaded && <p className="meta">올린 사진이 없어요.</p>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(140px,1fr))', gap: 12 }}>
            {photos.map((p) => (
              <figure key={p.photoId} style={{ margin: 0 }} className="col">
                {/* eslint-disable-next-line @next/next/no-img-element -- 서명 URL을 그대로 쓴다 (CONTRACTS 6장) */}
                <img src={p.url} onLoad={shown} alt={`${p.visitDate} 시술 사진`} style={{ width: '100%', aspectRatio: '1', objectFit: 'cover', borderRadius: 12 }} />
                <figcaption className="meta num" style={{ paddingTop: 6 }}>
                  {dotDate(p.visitDate)}
                </figcaption>
              </figure>
            ))}
          </div>
        )
      ) : (
        visits.map((v) => (
          <div className="trow" key={v.visitId} style={{ gridTemplateColumns: '96px 1fr' }}>
            <strong className="num">{dotDate(v.visitDate)}</strong>
            <span className="col" style={{ gap: 8 }}>
              <span className="meta">방문 1회 · 시술 {v.serviceCount}건</span>
              {v.services.map((s, i) => (
                <span key={i}>
                  <strong style={{ fontSize: 14.5 }}>{s.menuName ?? '메뉴 미상'}</strong>{' '}
                  <span className="meta">
                    · {CATEGORY_LABEL[s.category]}
                    {s.staff && ` · ${s.staff.name}`}
                  </span>
                </span>
              ))}
              {v.notes.map((n) => (
                <span className="sub" key={n.noteId}>
                  {n.content} <span className="meta">— {n.staff.name}, {formatDateTime(n.createdAt)}</span>
                </span>
              ))}
              {v.photos.length > 0 && (
                <span className="row" style={{ gap: 8 }}>
                  {v.photos.map((p) => (
                    // eslint-disable-next-line @next/next/no-img-element -- 서명 URL을 그대로 쓴다 (CONTRACTS 6장)
                    <img key={p.photoId} src={p.url} onLoad={shown} alt={`${v.visitDate} 시술 사진`} style={{ width: 88, height: 88, objectFit: 'cover', borderRadius: 10 }} />
                  ))}
                </span>
              )}
              {props.photoAgreed && (
                <label className="lk" style={{ alignSelf: 'flex-start' }}>
                  {uploading === v.visitId ? '올리는 중…' : '+ 사진 올리기'}
                  <input
                    type="file"
                    accept="image/jpeg,image/png"
                    multiple
                    hidden
                    disabled={uploading !== null}
                    onChange={(e) => {
                      upload(v.visitId, e.target.files);
                      e.target.value = '';
                    }}
                  />
                </label>
              )}
            </span>
          </div>
        ))
      )}
      {visits.length < total && (
        <button type="button" className="btn" style={{ alignSelf: 'center', marginTop: 12 }} onClick={() => load(Math.ceil(visits.length / PAGE) + 1)}>
          더 보기
        </button>
      )}
    </section>
  );
}
