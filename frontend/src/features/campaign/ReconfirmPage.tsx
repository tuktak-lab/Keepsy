import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { PinGate } from '@/components/common/PinGate';
import { toast } from '@/components/common/Toast';
import { ApiError, api, type Page } from '@/lib/api';
import { dueText } from '@/lib/consentReconfirm';
import { dotDate, seoulToday } from '@/lib/customerDetail';
import { formatDateTime } from '@/lib/format';
import type { TemplatePurpose } from '@/lib/messageTemplate';
import styleText from './CampaignsPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';

// 동의 재확인 대상 (FR-CMP-13, D1 · F45). 경로: /campaigns/reconfirm. GET /api/consent-reconfirm-targets (CAMPAIGN_VIEW).
// 목록에서 고른 고객으로 "동의 재확인" 용도 템플릿의 안내 캠페인을 만들고(POST /api/campaigns, CAMPAIGN_SEND),
// 미리보기 · 검토 · 발송은 캠페인 작성 2단계(/campaigns/new?id=)에서 한다. 권한 · 야간 · 일 발송 한도는 서버가 판정한다 (NFR-04).

type Row = {
  customerId: number;
  name: string;
  phone: string;
  consentDate: string;
  lastReconfirmedAt: string | null;
  dueDate: string;
  nightAdConsent: boolean;
};
type Template = { id: number; name: string; body: string; purpose: TemplatePurpose };

const SIZE = 50;
const MAX_TARGETS = 3000;
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');

function Body() {
  const router = useRouter();
  const [rows, setRows] = useState<Row[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [templates, setTemplates] = useState<Template[] | null>(null);
  const [templateError, setTemplateError] = useState('');
  const [templateId, setTemplateId] = useState<number | null>(null);
  const [name, setName] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const today = seoulToday();

  async function load(count: number) {
    setLoading(true);
    try {
      const page = await api<Page<Row>>(`/api/consent-reconfirm-targets?page=0&size=${Math.min(count, 100)}`, { silent: true });
      setRows(page.items);
      setTotal(page.totalElements);
      setError('');
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    api<Page<Row>>(`/api/consent-reconfirm-targets?page=0&size=${SIZE}`, { silent: true })
      .then((page) => {
        setRows(page.items);
        setTotal(page.totalElements);
        // 처음에는 기한이 됐거나 지난 고객을 골라 둔다 (목록은 기한이 이른 순)
        const opened = seoulToday();
        setPicked(new Set(page.items.filter((r) => dueText(r.dueDate, opened).overdue).map((r) => r.customerId)));
      })
      .catch((err) => setError(errorText(err)));
    api<Page<Template>>('/api/message-templates?page=0&size=100', { silent: true })
      .then((p) => setTemplates(p.items.filter((t) => t.purpose === 'CONSENT_RECONFIRM')))
      .catch((err) => setTemplateError(errorText(err)));
  }, []);

  const template = templates?.find((t) => t.id === templateId) ?? templates?.[0] ?? null;
  const allPicked = Boolean(rows?.length) && rows!.every((r) => picked.has(r.customerId));
  const defaultName = `동의 재확인 안내 ${today}`;

  function toggle(id: number) {
    setPicked((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAll() {
    setPicked(allPicked ? new Set() : new Set(rows?.map((r) => r.customerId)));
  }

  async function create() {
    if (!template || picked.size === 0) return;
    setCreating(true);
    setCreateError('');
    try {
      const r = await api<{ id: number }>('/api/campaigns', {
        method: 'POST',
        body: { name: name.trim() || defaultName, templateId: template.id, customerIds: [...picked] },
        silent: true,
      });
      toast('안내 캠페인을 만들었어요. 미리보기와 검토 뒤 발송해요');
      router.push(`/campaigns/new?id=${r.id}`);
    } catch (err) {
      setCreateError(errorText(err));
      setCreating(false);
    }
  }

  return (
    <>
      <section className="cp-card cp-main" aria-label="동의 재확인 대상 목록">
        <div className="cp-row2">
          <h2 style={{ margin: 0, fontSize: 16 }}>
            대상 <span className="num">{total}</span>명
          </h2>
          <span className="cp-meta">재확인 기한이 이른 순 · 기한 30일 전부터 보여요</span>
        </div>

        {error && <div className="cp-warn" role="alert">{error}</div>}
        {rows === null && !error && <p className="cp-note">불러오는 중…</p>}
        {rows?.length === 0 && <div className="cp-ok">지금 재확인할 고객이 없어요. 동의일(또는 마지막 재확인)부터 2년이 되기 30일 전에 여기에 나타나요.</div>}

        {rows && rows.length > 0 && (
          <div style={{ overflowX: 'auto' }}>
            <table className="cp-tbl">
              <thead>
                <tr>
                  <th style={{ width: 32 }}>
                    <input type="checkbox" checked={allPicked} onChange={toggleAll} aria-label="불러온 고객 모두 고르기" />
                  </th>
                  <th>고객</th>
                  <th>마케팅 동의일</th>
                  <th>마지막 재확인</th>
                  <th>재확인 기한</th>
                  <th>야간 광고 동의</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const due = dueText(r.dueDate, today);
                  return (
                    <tr key={r.customerId}>
                      <td>
                        <input type="checkbox" checked={picked.has(r.customerId)} onChange={() => toggle(r.customerId)} aria-label={`${r.name} 고르기`} />
                      </td>
                      <td>
                        <AppLink href={`/customers/detail?id=${r.customerId}`} style={{ color: 'inherit' }}>
                          <b>{r.name}</b>
                        </AppLink>{' '}
                        <span className="cp-meta num">{r.phone}</span>
                      </td>
                      <td className="num">{dotDate(r.consentDate)}</td>
                      <td className="num">{r.lastReconfirmedAt ? dotDate(formatDateTime(r.lastReconfirmedAt).slice(0, 10)) : '—'}</td>
                      <td className="num">
                        {dotDate(r.dueDate)}{' '}
                        <span className="cp-chip" style={due.overdue ? { background: '#F6E1DF', color: '#8F2F2F' } : { background: '#F5EBD3', color: '#6B4509' }}>
                          {due.text}
                        </span>
                      </td>
                      <td>{r.nightAdConsent ? '동의' : '미동의'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {rows && rows.length < total && (
          <button type="button" className="cp-btn" style={{ alignSelf: 'center' }} disabled={loading} onClick={() => load(rows.length + SIZE)}>
            {loading ? '불러오는 중…' : '더 보기'}
          </button>
        )}
        <span className="cp-note">
          안내 발송에 성공한 고객만 목록에서 빠지고, 그날부터 다음 2년을 세요. 발송에 실패한 고객은 목록에 남아요.
        </span>
      </section>

      <section className="cp-card cp-main" aria-label="재확인 안내 캠페인 만들기">
        <div className="cp-main-in">
          <div className="cp-step">
            <h3><i>1</i>안내 템플릿</h3>
            {templateError && <div className="cp-warn">{templateError}</div>}
            {templates?.length === 0 && (
              <div className="cp-warn">
                동의 재확인 용도 템플릿이 없어요. <AppLink href="/campaigns/templates" style={{ color: '#8F2F2F', fontWeight: 600 }}>템플릿 만들기 →</AppLink>
              </div>
            )}
            <div className="cp-tpls">
              {templates?.map((t) => (
                <button type="button" className="cp-tpl" key={t.id} aria-pressed={template?.id === t.id} onClick={() => setTemplateId(t.id)}>
                  <b>{t.name}</b>
                  <span className="cp-note">동의 재확인</span>
                </button>
              ))}
            </div>
            {template && (
              <div className="cp-box">
                <span className="cp-note">본문 (변수는 고객마다 바뀌어요. 광고가 아니라 (광고) 표기는 붙지 않아요)</span>
                <span style={{ fontSize: 13.5, whiteSpace: 'pre-line' }}>{template.body}</span>
              </div>
            )}
          </div>

          <div className="cp-step">
            <h3><i>2</i>캠페인 이름</h3>
            <input className="cp-fld" value={name} maxLength={100} placeholder={defaultName} onChange={(e) => setName(e.target.value)} aria-label="캠페인 이름" />
          </div>

          <div className="cp-step">
            <div className="cp-box">
              <span className="cp-note">
                재확인 안내도 캠페인이라 검토 체크 · 하루 발송 한도를 그대로 적용해요. 야간(21시~다음 날 08시)에는 보낼 수 없어 낮 시각으로 예약해 주세요.
                최근 광고를 받은 고객도 법정 안내는 받아야 해서 재발송 제한은 적용하지 않아요.
              </span>
            </div>
            {picked.size > MAX_TARGETS && <div className="cp-warn">한 번에 {MAX_TARGETS.toLocaleString('ko-KR')}명까지 보낼 수 있어요.</div>}
            {createError && <div className="cp-warn" role="alert">{createError}</div>}
            <div className="cp-btns">
              <span className="cp-note">만든 뒤 고객별 문구를 미리 보고, 검토 체크를 해야 발송할 수 있어요.</span>
              <button
                type="button"
                className="cp-btn p"
                disabled={creating || !template || picked.size === 0 || picked.size > MAX_TARGETS}
                onClick={create}
              >
                {creating ? '만드는 중…' : `고른 ${picked.size}명으로 안내 캠페인 만들기`}
              </button>
            </div>
          </div>
        </div>
      </section>
    </>
  );
}

export default function ReconfirmPage() {
  useStyleSheet('CampaignsPage', styleText);
  return (
    <>
      <PinGate id="campaigns" title="동의 재확인 대상">
        <div className="cp-wrap">
          <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
            <div>
              <h1 className="cp-h1">동의 재확인 대상</h1>
              <p className="cp-sub">
                광고 수신 동의를 받은 고객에게는 2년마다 동의 사실과 철회 방법을 다시 알려야 해요 (정보통신망법 시행령 제62조의3).
                <br />
                기한이 다가온 고객을 골라 재확인 안내를 보내요.
              </p>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              <AppLink href="/campaigns/templates" className="cp-btn">템플릿</AppLink>
              <AppLink href="/campaigns" className="cp-btn">캠페인 목록</AppLink>
            </div>
          </div>
          <Body />
          <p className="cp-foot">MVP는 가상 발송이라 실제 문자는 나가지 않아요.</p>
        </div>
      </PinGate>
    </>
  );
}
