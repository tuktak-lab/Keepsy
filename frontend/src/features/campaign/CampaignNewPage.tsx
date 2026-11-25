import { useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { PinGate } from '@/components/common/PinGate';
import { CountUp } from '@/components/common/CountUp';
import { toast } from '@/components/common/Toast';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import { ApiError, api, type Page } from '@/lib/api';
import { CAMPAIGN_STATUS_LABEL } from '@/lib/campaignResult';
import {
  EXCLUSION_LABEL,
  RECOMMENDED_PURPOSE,
  TARGET_STATUSES,
  isSeoulNight,
  parseIds,
  seoulLocalInput,
  seoulLocalToIso,
  type TargetStatus,
} from '@/lib/campaignNew';
import { formatWon } from '@/lib/format';
import { CATEGORIES, CATEGORY_LABEL, type Category } from '@/lib/menus';
import { PURPOSES, type TemplatePurpose } from '@/lib/messageTemplate';
import styleText from './CampaignsPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';

// 캠페인 작성 (FR-CMP-01~09 · 12 · 18). 경로: /campaigns/new (만든 뒤에는 ?id=로 이어서 발송)
// 1) 대상 · 템플릿 · 대조군을 정해 캠페인을 만든다: POST /api/campaigns/targets(미리 계산) → POST /api/campaigns (DRAFT)
// 2) 미리보기 → 검토 체크 → 즉시 발송 · 예약 발송: GET …/preview · POST …/review · POST …/send · PUT …/schedule · POST …/cancel
// 야간 · 일 발송 한도 · 중복 발송은 서버가 판정한다 (NFR-04). 실제 문자는 나가지 않는다 (A6).

type Template = { id: number; name: string; body: string; purpose: TemplatePurpose; messageKind: 'AD' | 'INFO' };
type Targets = { targetCount: number; excludedCounts: Record<string, number> };
type Campaign = {
  id: number;
  name: string;
  category: Category | null;
  messageKind: 'AD' | 'INFO';
  status: string;
  targetCount: number;
  controlCount: number;
  templateId: number;
  controlPercent: number;
  reviewedBy: { id: number; name: string } | null;
  reviewedAt: string | null;
};
type PreviewItem = { customerId: number; customerName: string | null; text: string | null; bytes: number | null; messageType: string | null; missingVariables: string[] };
type Preview = { sendCount: number; controlCount: number; estimatedCost: number; unsendableCount: number; items: Page<PreviewItem> };

const PURPOSE_LABEL = Object.fromEntries(PURPOSES) as Record<TemplatePurpose, string>;
const MAX_CONTROL = 50;
const unit = (u: string) => (n: number) => n + u;
/** 예약 입력값(서울 'YYYY-MM-DDTHH:mm')이 야간 시각인지 (FR-CMP-07 · 09) */
const nightHour = (local: string) => {
  const h = Number(local.slice(11, 13));
  return local.length >= 13 && (h >= 21 || h < 8);
};
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
const fieldReasons = (err: unknown): Record<string, string> =>
  err instanceof ApiError && Array.isArray(err.details)
    ? Object.fromEntries((err.details as { field: string; reason: string }[]).map((d) => [d.field, d.reason]))
    : {};

function Body() {
  const params = useSearchParams();
  const id = Number(params.get('id'));
  return Number.isInteger(id) && id > 0 ? <SendStep id={id} /> : <SetupStep presetIds={parseIds(params.get('customerIds'))} presetCategory={params.get('category')} />;
}

/** 1단계: 대상 · 템플릿 · 대조군을 정해 캠페인을 만든다 (FR-CMP-01 · 02 · 03 · 18) */
function SetupStep(props: { presetIds: number[]; presetCategory: string | null }) {
  const router = useRouter();
  const [category, setCategory] = useState<Category>(
    (CATEGORIES as readonly string[]).includes(props.presetCategory ?? '') ? (props.presetCategory as Category) : 'NAIL',
  );
  const [status, setStatus] = useState<TargetStatus | null>(props.presetIds.length ? null : 'DUE');
  const [customerIds, setCustomerIds] = useState(props.presetIds);
  const [more, setMore] = useState(false);
  const [visitMin, setVisitMin] = useState('');
  const [visitMax, setVisitMax] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [targets, setTargets] = useState<Targets | null>(null);
  const [targetsError, setTargetsError] = useState('');
  const [templates, setTemplates] = useState<Template[] | null>(null);
  const [templateError, setTemplateError] = useState('');
  const [picked, setPicked] = useState<number | null>(null);
  const [control, setControl] = useState(0);
  const [name, setName] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  // 대상 조건 (POST /api/campaigns/targets · POST /api/campaigns 같은 칸). 빈 칸은 보내지 않는다
  const conditions = useMemo(() => {
    const c: Record<string, unknown> = { category };
    if (status) c.predictionStatus = status;
    if (customerIds.length) c.customerIds = customerIds;
    if (visitMin !== '') c.visitCountMin = Number(visitMin);
    if (visitMax !== '') c.visitCountMax = Number(visitMax);
    if (from) c.lastServiceFrom = from;
    if (to) c.lastServiceTo = to;
    return c;
  }, [category, status, customerIds, visitMin, visitMax, from, to]);

  useEffect(() => {
    api<Page<Template>>('/api/message-templates?page=0&size=100', { silent: true })
      .then((p) => setTemplates(p.items.filter((t) => t.messageKind === 'AD')))
      .catch((err) => setTemplateError(errorText(err)));
  }, []);

  // 조건을 바꿀 때마다 잠시 뒤 대상 수를 다시 센다 (저장하지 않음)
  useEffect(() => {
    let alive = true;
    const timer = setTimeout(() => {
      api<Targets>('/api/campaigns/targets', { method: 'POST', body: conditions, silent: true })
        .then((t) => alive && (setTargets(t), setTargetsError('')))
        .catch((err) => alive && (setTargets(null), setTargetsError(errorText(err))));
    }, 300);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [conditions]);

  const recommended = templates?.find((t) => status && t.purpose === RECOMMENDED_PURPOSE[status]) ?? null;
  const template = templates?.find((t) => t.id === picked) ?? recommended ?? templates?.[0] ?? null;
  const statusInfo = TARGET_STATUSES.find((s) => s.status === status);
  const defaultName = `${CATEGORY_LABEL[category]} ${statusInfo?.label ?? (customerIds.length ? '선택 고객' : '전체')} ${template ? PURPOSE_LABEL[template.purpose] : ''}`.trim();
  const controlCount = targets ? Math.floor((targets.targetCount * control) / 100) : 0;
  const sendCount = targets ? targets.targetCount - controlCount : 0;

  async function create() {
    if (!template) return;
    setCreating(true);
    setCreateError('');
    setFieldErrors({});
    try {
      const r = await api<{ id: number }>('/api/campaigns', {
        method: 'POST',
        body: { ...conditions, name: name.trim() || defaultName, templateId: template.id, controlPercent: control },
        silent: true,
      });
      toast('캠페인을 만들었어요. 미리보기와 검토 뒤 발송해요');
      router.replace(`/campaigns/new?id=${r.id}`);
    } catch (err) {
      setFieldErrors(fieldReasons(err));
      setCreateError(errorText(err));
      setCreating(false);
    }
  }

  return (
    <>
      <div className="cp-step">
        <h3><i>1</i>시술 카테고리와 대상</h3>
        <div className="cp-seg" role="group" aria-label="시술 카테고리">
          {CATEGORIES.map((k) => (
            <button type="button" key={k} aria-pressed={category === k} onClick={() => setCategory(k)}>{CATEGORY_LABEL[k]}</button>
          ))}
        </div>
        {customerIds.length > 0 ? (
          <div className="cp-ok">
            재방문 관리에서 고른 고객 <b className="num">{customerIds.length}명</b>으로 시작했어요. 그중 {CATEGORY_LABEL[category]} 시술 기록이 있는 고객만 남아요.{' '}
            <button type="button" className="cp-lk" onClick={() => { setCustomerIds([]); setStatus('DUE'); }}>조건으로 고르기</button>
          </div>
        ) : (
          <>
            <div className="cp-seg" role="group" aria-label="예측 상태">
              {TARGET_STATUSES.map((s) => (
                <button type="button" key={s.status} aria-pressed={status === s.status} onClick={() => setStatus(s.status)}>{s.label}</button>
              ))}
              <button type="button" aria-pressed={status === null} onClick={() => setStatus(null)}>상태 무관</button>
            </div>
            <span className="cp-note kp-fade" key={status ?? 'all'}>
              {statusInfo ? (
                <>
                  <b>{statusInfo.label}</b>: {statusInfo.desc}
                </>
              ) : (
                <>예측 상태와 상관없이 {CATEGORY_LABEL[category]} 시술 기록이 있는 고객 전체예요.</>
              )}
            </span>
          </>
        )}

        <button type="button" className="cp-lk" style={{ alignSelf: 'flex-start' }} onClick={() => setMore((m) => !m)} aria-expanded={more}>
          {more ? '조건 접기' : '조건 더 보기 (방문 횟수 · 마지막 시술일)'}
        </button>
        {more && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span className="cp-lbl">총 방문 횟수</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input className="cp-fld num" type="number" min={0} value={visitMin} onChange={(e) => setVisitMin(e.target.value)} style={{ width: 80 }} aria-label="방문 횟수 최소" />
                <span className="cp-note">~</span>
                <input className="cp-fld num" type="number" min={0} value={visitMax} onChange={(e) => setVisitMax(e.target.value)} style={{ width: 80 }} aria-label="방문 횟수 최대" />
                <span className="cp-note">회</span>
              </span>
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span className="cp-lbl">{CATEGORY_LABEL[category]} 마지막 시술일</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input className="cp-fld num" type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="마지막 시술일 시작" />
                <span className="cp-note">~</span>
                <input className="cp-fld num" type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="마지막 시술일 끝" />
              </span>
            </label>
          </div>
        )}

        <div className="cp-box" aria-live="polite">
          {targetsError ? (
            <span style={{ color: '#8F2F2F', fontSize: 13 }}>{targetsError}</span>
          ) : (
            <>
              <span style={{ fontWeight: 600 }} className="num">
                발송할 수 있는 고객 <CountUp value={targets?.targetCount ?? 0} duration={300} format={unit('명')} />
              </span>
              <div className="cp-excl">
                {EXCLUSION_LABEL.map(([code, label]) => (
                  <span className="cp-ex" key={code}>
                    {label} 제외 <b className="num">{targets?.excludedCounts[code] ?? 0}</b>
                  </span>
                ))}
              </div>
              <span className="cp-note">수신 동의가 없거나 수신 거부했거나, 재발송 제한 기간 안에 광고를 받은 고객은 자동으로 빠져요.</span>
            </>
          )}
        </div>
      </div>

      <div className="cp-step">
        <h3><i>2</i>템플릿</h3>
        {templateError && <div className="cp-warn">{templateError}</div>}
        {templates?.length === 0 && (
          <div className="cp-warn">
            광고용 템플릿이 없어요. <AppLink href="/campaigns/templates" style={{ color: '#8F2F2F', fontWeight: 600 }}>템플릿 만들기 →</AppLink>
          </div>
        )}
        <div className="cp-tpls">
          {templates?.map((t) => (
            <button type="button" className="cp-tpl" key={t.id} aria-pressed={template?.id === t.id} onClick={() => setPicked(t.id)}>
              <b>
                {t.name}
                {recommended?.id === t.id && <span className="cp-chip" style={{ background: '#F1E4E0', color: '#7A4450', marginLeft: 6 }}>추천</span>}
              </b>
              <span className="cp-note">{PURPOSE_LABEL[t.purpose]}</span>
            </button>
          ))}
        </div>
        {template && (
          <div className="cp-box">
            <span className="cp-note">본문 (변수는 고객마다 바뀌고, (광고) 표기와 수신 거부 안내는 발송할 때 자동으로 붙어요)</span>
            <span style={{ fontSize: 13.5, whiteSpace: 'pre-line' }}>{template.body}</span>
          </div>
        )}
        <AppLink href="/campaigns/templates" className="cp-lk" style={{ alignSelf: 'flex-start' }}>템플릿 관리</AppLink>
      </div>

      <div className="cp-step">
        <h3><i>3</i>대조군과 이름</h3>
        <label style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
          <span className="cp-lbl">대조군 비율</span>
          <input type="range" min={0} max={MAX_CONTROL} step={5} value={control} onChange={(e) => setControl(Number(e.target.value))} style={{ accentColor: '#7A4450' }} />
          <input className="cp-fld num" type="number" min={0} max={MAX_CONTROL} value={control} onChange={(e) => setControl(Math.max(0, Math.min(MAX_CONTROL, Number(e.target.value) || 0)))} style={{ width: 80 }} aria-label="대조군 비율(%)" />
          <span className="cp-note">% · 일부 고객에게는 보내지 않고, 성과 분석에서 보낸 고객과 비교해요.</span>
        </label>
        <div className="cp-stats">
          <div className="cp-stat"><b className="num">{sendCount}명</b><span>발송 (예상)</span></div>
          <div className="cp-stat"><b className="num">{controlCount}명</b><span>대조군 (예상)</span></div>
        </div>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span className="cp-lbl">캠페인 이름</span>
          <input className="cp-fld" value={name} maxLength={100} placeholder={defaultName} onChange={(e) => setName(e.target.value)} aria-invalid={Boolean(fieldErrors.name)} />
          {fieldErrors.name && <span style={{ color: '#8F2F2F', fontSize: 12 }}>{fieldErrors.name}</span>}
        </label>
      </div>

      <div className="cp-step">
        {createError && <div className="cp-warn" role="alert">{createError}</div>}
        <div className="cp-btns">
          <span className="cp-note">만든 뒤 고객별 문구를 미리 보고, 검토 체크를 해야 발송할 수 있어요.</span>
          <button type="button" className="cp-btn p" disabled={creating || !template || !targets || targets.targetCount === 0} onClick={create}>
            {creating ? '만드는 중…' : '캠페인 만들고 미리보기'}
          </button>
        </div>
      </div>
    </>
  );
}

/** 2단계: 미리보기 → 검토 체크 → 즉시 발송 · 예약 발송 (FR-CMP-04~09 · 12) */
function SendStep({ id }: { id: number }) {
  const router = useRouter();
  const [campaign, setCampaign] = useState<Campaign | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [loadError, setLoadError] = useState('');
  const [previewError, setPreviewError] = useState('');
  const [pv, setPv] = useState(0);
  const [reviewed, setReviewed] = useState(false);
  const [mode, setMode] = useState<'now' | 'schedule'>('now');
  const [at, setAt] = useState(() => seoulLocalInput(new Date(Date.now() + 24 * 3600 * 1000)).slice(0, 11) + '11:00');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [confirmCancel, setConfirmCancel] = useState(false);

  useEffect(() => {
    api<Campaign>(`/api/campaigns/${id}?page=0&size=1`, { silent: true })
      .then((c) => {
        setCampaign(c);
        setReviewed(Boolean(c.reviewedAt));
      })
      .catch((err) => setLoadError(errorText(err)));
    api<Preview>(`/api/campaigns/${id}/preview?page=0&size=50`, { silent: true })
      .then(setPreview)
      .catch((err) => setPreviewError(errorText(err)));
  }, [id]);

  if (loadError) return <div className="cp-warn" role="alert">{loadError}</div>;
  if (!campaign) return <p className="cp-note">불러오는 중…</p>;
  if (campaign.status !== 'DRAFT') {
    return (
      <div className="cp-ok">
        이 캠페인은 이미 {CAMPAIGN_STATUS_LABEL[campaign.status] ?? campaign.status} 상태예요.{' '}
        <AppLink href="/campaigns" style={{ color: '#7A4450', fontWeight: 600 }}>캠페인 목록으로 →</AppLink>
      </div>
    );
  }

  const item = preview?.items.items[Math.min(pv, Math.max((preview?.items.items.length ?? 1) - 1, 0))];
  const nightNow = mode === 'now' && isSeoulNight(new Date());

  async function review(checked: boolean) {
    setError('');
    if (!checked) return setReviewed(false);
    try {
      await api(`/api/campaigns/${id}/review`, { method: 'POST', silent: true });
      setReviewed(true);
    } catch (err) {
      setError(errorText(err));
    }
  }

  async function send() {
    setBusy(true);
    setError('');
    try {
      if (mode === 'now') {
        const r = await api<{ sendCount: number; skippedCount: number }>(`/api/campaigns/${id}/send`, { method: 'POST', silent: true });
        toast(`가상 발송했어요 · ${r.sendCount}건${r.skippedCount ? ` (발송 시점에 ${r.skippedCount}명 제외)` : ''}`);
      } else {
        const iso = seoulLocalToIso(at);
        if (!iso) throw new ApiError(400, 'invalid', '예약 시각을 골라 주세요');
        await api(`/api/campaigns/${id}/schedule`, { method: 'PUT', body: { scheduledAt: iso }, silent: true });
        toast(`${at.replace('T', ' ')}에 예약 발송해요`);
      }
      router.push('/campaigns');
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  }

  async function cancel() {
    setBusy(true);
    setError('');
    try {
      await api(`/api/campaigns/${id}/cancel`, { method: 'POST', silent: true });
      toast('캠페인을 취소했어요', 'warn');
      router.push('/campaigns');
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  }

  return (
    <>
      <div className="cp-step">
        <div className="cp-row2">
          <h3 style={{ fontSize: 18 }}>{campaign.name}</h3>
          <span className="cp-chip" style={{ background: '#F5EBD3', color: '#6B4509' }}>작성 중</span>
        </div>
        <span className="cp-meta">
          {campaign.category ? CATEGORY_LABEL[campaign.category] : '동의 재확인 안내'} · 대상 {campaign.targetCount}명 · 대조군 {campaign.controlCount}명 ({campaign.controlPercent}%)
        </span>
      </div>

      <div className="cp-step">
        <h3><i>4</i>미리보기</h3>
        {previewError ? (
          <div className="cp-warn" role="alert">
            {previewError}
            {previewError.includes('매장 정보') && (
              <>
                {' '}
                <AppLink href="/settings" style={{ color: '#8F2F2F', fontWeight: 600 }}>매장 설정으로 →</AppLink>
              </>
            )}
          </div>
        ) : !preview ? (
          <p className="cp-note">불러오는 중…</p>
        ) : (
          <>
            <div className="cp-stats">
              <div className="cp-stat"><b className="num">{preview.sendCount}명</b><span>발송</span></div>
              <div className="cp-stat"><b className="num">{preview.controlCount}명</b><span>대조군 (보내지 않음)</span></div>
              <div className="cp-stat"><b className="num">{formatWon(preview.estimatedCost)}</b><span>예상 비용 (가상 단가)</span></div>
              <div className="cp-stat"><b className="num" style={preview.unsendableCount ? { color: '#8F2F2F' } : undefined}>{preview.unsendableCount}명</b><span>발송 불가</span></div>
            </div>
            {preview.unsendableCount > 0 && (
              <div className="cp-warn">발송 불가 고객은 보내지 않아요. 변수 값이 없거나 2,000바이트를 넘거나 삭제된 고객이에요.</div>
            )}
            <div className="cp-pv">
              <div className="cp-phone" aria-label="가상 휴대폰 미리보기">
                <div className="top">가상 휴대폰 · 문자 메시지</div>
                <div className="cp-bubble kp-fade" key={item?.customerId ?? 0}>{item?.text ?? '이 고객에게는 보낼 수 없어요.'}</div>
              </div>
              <div style={{ flex: '1 1 240px', display: 'flex', flexDirection: 'column', gap: 10 }}>
                <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <span className="cp-lbl">미리 볼 고객</span>
                  <select className="cp-fld" value={pv} onChange={(e) => setPv(Number(e.target.value))}>
                    {preview.items.items.map((x, i) => (
                      <option key={x.customerId} value={i}>
                        {x.customerName ?? '삭제된 고객'}
                        {x.messageType ? '' : ' (발송 불가)'}
                      </option>
                    ))}
                  </select>
                </label>
                {item && (
                  <div className="cp-box">
                    <span style={{ fontSize: 13 }} className="num">
                      {item.messageType ?? '발송 불가'} · {item.bytes ?? '—'}바이트
                    </span>
                    {item.missingVariables.length > 0 && <span className="cp-note">값이 없는 변수: {item.missingVariables.join(', ')}</span>}
                    <span className="cp-note">90바이트 이하는 SMS, 2,000바이트 이하는 LMS예요. (광고) 표기 · 매장 정보 · 무료 수신거부 안내는 자동으로 붙고 지울 수 없어요.</span>
                  </div>
                )}
                {preview.items.totalElements > preview.items.items.length && <span className="cp-note">앞 {preview.items.items.length}명만 미리 볼 수 있어요.</span>}
              </div>
            </div>
          </>
        )}
      </div>

      <div className="cp-step">
        <h3><i>5</i>발송 시점</h3>
        <div className="cp-seg" role="group" aria-label="발송 방식">
          <button type="button" aria-pressed={mode === 'now'} onClick={() => setMode('now')}>즉시 발송</button>
          <button type="button" aria-pressed={mode === 'schedule'} onClick={() => setMode('schedule')}>예약 발송</button>
        </div>
        {mode === 'schedule' && (
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span className="cp-lbl">예약 시각</span>
            <input className="cp-fld num" type="datetime-local" value={at} min={seoulLocalInput(new Date())} onChange={(e) => setAt(e.target.value)} />
          </label>
        )}
        {(nightNow || (mode === 'schedule' && nightHour(at))) && (
          <div className="cp-warn">
            야간(21시~다음 날 08시)에는 보낼 고객 전원이 야간 광고 수신에 동의했을 때만 보낼 수 있어요. 아니면 낮 시각으로 예약해 주세요.
          </div>
        )}
        <span className="cp-note">발송할 때 수신 동의와 하루 발송 한도를 다시 확인해요. 그사이 동의를 철회한 고객은 빠져요.</span>
      </div>

      <div className="cp-step">
        <h3><i>6</i>검토 후 발송</h3>
        <label className="cp-check">
          <input type="checkbox" checked={reviewed} onChange={(e) => review(e.target.checked)} />
          <span>대상과 문구를 검토했어요. 다른 앱 예약 목록과 대조해 이미 예약한 고객이 없음을 확인했어요.</span>
        </label>
        {campaign.reviewedBy && reviewed && <span className="cp-note">검토 기록: {campaign.reviewedBy.name}</span>}
        {error && <div className="cp-warn" role="alert">{error}</div>}
        <div className="cp-btns">
          {confirmCancel ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="cp-note">이 캠페인을 취소할까요?</span>
              <button type="button" className="cp-btn" style={{ color: '#8F2F2F' }} disabled={busy} onClick={cancel}>취소하기</button>
              <button type="button" className="cp-btn" disabled={busy} onClick={() => setConfirmCancel(false)}>그대로 두기</button>
            </span>
          ) : (
            <button type="button" className="cp-btn" disabled={busy} onClick={() => setConfirmCancel(true)}>캠페인 취소</button>
          )}
          <button type="button" className="cp-btn p" disabled={busy || !reviewed || !preview || preview.sendCount === 0} onClick={send}>
            {busy ? '처리 중…' : mode === 'now' ? `${preview?.sendCount ?? 0}명에게 가상 발송` : '예약 발송 등록'}
          </button>
        </div>
        {!reviewed && <span className="cp-note">검토 체크를 해야 발송할 수 있어요. MVP는 가상 발송이라 실제 문자는 나가지 않아요.</span>}
      </div>
    </>
  );
}

export default function CampaignNewPage() {
  useStyleSheet('CampaignsPage', styleText);
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', minHeight: '1100px' }}>
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="campaigns" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb="파트너 센터 / 캠페인 관리 / 새 캠페인" />
        <PinGate id="campaigns" title="새 캠페인 만들기">
          <div className="cp-wrap">
            <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
              <div>
                <h1 className="cp-h1">새 캠페인 만들기</h1>
                <p className="cp-sub">시술 카테고리 하나를 정해 대상을 고르고, 미리보기와 검토를 거쳐 발송해요.</p>
              </div>
              <AppLink href="/campaigns" className="cp-btn">캠페인 목록</AppLink>
            </div>
            <section className="cp-card cp-main" aria-label="캠페인 작성">
              <div className="cp-main-in">
                <Body />
              </div>
            </section>
            <p className="cp-foot">마케팅 수신 동의 고객에게만 발송해요. 문구는 템플릿으로 만들고 자동 발송되지 않으며, 검토 체크가 필요해요. MVP는 가상 발송이에요.</p>
          </div>
        </PinGate>
      </main>
    </div>
  );
}
