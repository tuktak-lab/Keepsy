import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { PinGate } from '@/components/common/PinGate';
import { CountUp } from '@/components/common/CountUp';
import { toast } from '@/components/common/Toast';
import { ApiError, api, type Page } from '@/lib/api';
import { CAMPAIGN_STATUS_LABEL } from '@/lib/campaignResult';
import {
  EXCLUSION_LABEL,
  RECOMMENDED_PURPOSE,
  TARGET_STATUSES,
  canCreate,
  currentTargets,
  isSeoulNight,
  parseIds,
  parsePurpose,
  resolveCategory,
  seoulLocalInput,
  seoulLocalToIso,
  type TargetStatus,
  type TargetsResult,
} from '@/lib/campaignNew';
import { formatWon } from '@/lib/format';
import { CATEGORIES, CATEGORY_LABEL, enabledCategories, type Category } from '@/lib/menus';
import { purposeLabel, type TemplatePurpose } from '@/lib/messageTemplate';
import styleText from './CampaignsPage.css?inline';
import { useIndustries } from '@/hooks/useIndustries';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { t as tr } from '@/lib/i18n';

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

const MAX_CONTROL = 50;
const unit = (u: string) => (n: number) => n + u;
/** 예약 입력값(서울 'YYYY-MM-DDTHH:mm')이 야간 시각인지 (FR-CMP-07 · 09) */
const nightHour = (local: string) => {
  const h = Number(local.slice(11, 13));
  return local.length >= 13 && (h >= 21 || h < 8);
};
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : tr('common.pin.tryLater'));
const fieldReasons = (err: unknown): Record<string, string> =>
  err instanceof ApiError && Array.isArray(err.details)
    ? Object.fromEntries((err.details as { field: string; reason: string }[]).map((d) => [d.field, d.reason]))
    : {};

function Body() {
  const params = useSearchParams();
  const id = Number(params.get('id'));
  // 캠페인이 바뀌면 2단계를 새로 만든다: 지난 캠페인의 미리보기 · 검토 체크가 남지 않게 (#344)
  return Number.isInteger(id) && id > 0 ? <SendStep key={id} id={id} /> : <SetupStep presetIds={parseIds(params.get('customerIds'))} presetCategory={params.get('category')} presetPurpose={parsePurpose(params.get('purpose'))} />;
}

/** 1단계: 대상 · 템플릿 · 대조군을 정해 캠페인을 만든다 (FR-CMP-01 · 02 · 03 · 18) */
function SetupStep(props: { presetIds: number[]; presetCategory: string | null; presetPurpose: string | null }) {
  const router = useRouter();
  const [industriesTry, setIndustriesTry] = useState(0);
  const industries = useIndustries(industriesTry);
  // 주소로 받은 카테고리(인사이트 등)는 꺼 둔 업종이어도 다른 업종으로 바꾸지 않는다 (F72). 직접 들어오면 기본 네일
  const explicitCategory = (CATEGORIES as readonly string[]).includes(props.presetCategory ?? '');
  const [wantedCategory, setCategory] = useState<Category>(explicitCategory ? (props.presetCategory as Category) : 'NAIL');
  // block이 있으면 만들 수 없다: 업종을 불러오는 중 · 불러오지 못함 · 꺼 둔 업종
  const { category, block } = resolveCategory(wantedCategory, explicitCategory, industries);
  const [status, setStatus] = useState<TargetStatus | null>(props.presetIds.length ? null : 'DUE');
  const [customerIds, setCustomerIds] = useState(props.presetIds);
  const [more, setMore] = useState(false);
  const [visitMin, setVisitMin] = useState('');
  const [visitMax, setVisitMax] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [targetsResult, setTargetsResult] = useState<TargetsResult<Targets> | null>(null);
  const [templates, setTemplates] = useState<Template[] | null>(null);
  const [templateError, setTemplateError] = useState('');
  const [picked, setPicked] = useState<number | null>(null);
  const [control, setControl] = useState(0);
  const [name, setName] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  // 대상 조건 (POST /api/campaigns/targets · POST /api/campaigns 같은 칸). 빈 칸은 보내지 않는다
  const conditions: Record<string, unknown> = { category };
  if (status) conditions.predictionStatus = status;
  if (customerIds.length) conditions.customerIds = customerIds;
  if (visitMin !== '') conditions.visitCountMin = Number(visitMin);
  if (visitMax !== '') conditions.visitCountMax = Number(visitMax);
  if (from) conditions.lastServiceFrom = from;
  if (to) conditions.lastServiceTo = to;
  // 대상 수는 이 조건(키)으로 센 것만 쓴다. 조건을 바꾸면 바로 이전 결과를 버리고(대기 · 조회 중에는 만들 수 없음), 늦게 온 이전 응답도 쓰지 않는다
  const conditionKey = JSON.stringify(conditions);
  const current = currentTargets(targetsResult, conditionKey);
  const targets = current?.data ?? null;

  useEffect(() => {
    api<Page<Template>>('/api/message-templates?page=0&size=100', { silent: true })
      .then((p) => setTemplates(p.items.filter((t) => t.messageKind === 'AD')))
      .catch((err) => setTemplateError(errorText(err)));
  }, []);

  // 조건을 바꿀 때마다 잠시 뒤 대상 수를 다시 센다 (저장하지 않음). 응답은 보낸 조건의 키와 함께 둔다
  useEffect(() => {
    let alive = true;
    const timer = setTimeout(() => {
      api<Targets>('/api/campaigns/targets', { method: 'POST', body: JSON.parse(conditionKey), silent: true })
        .then((t) => alive && setTargetsResult({ key: conditionKey, data: t, error: '' }))
        .catch((err) => alive && setTargetsResult({ key: conditionKey, data: null, error: errorText(err) }));
    }, 300);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [conditionKey]);

  // 추천 템플릿: 상태로 고르면 그 상태의 용도, 고객을 받아 시작하면 주소의 purpose(인사이트 다음 액션). 직접 고른 템플릿(picked)이 늘 먼저다
  const recommendedPurpose = status ? RECOMMENDED_PURPOSE[status] : props.presetPurpose;
  const recommended = templates?.find((t) => recommendedPurpose !== null && t.purpose === recommendedPurpose) ?? null;
  const template = templates?.find((t) => t.id === picked) ?? recommended ?? templates?.[0] ?? null;
  const statusInfo = TARGET_STATUSES.find((s) => s.status === status);
  const defaultName = `${CATEGORY_LABEL[category]} ${statusInfo ? tr(statusInfo.label) : customerIds.length ? tr('campaign.new.selectedCustomers') : tr('campaign.new.all')} ${template ? purposeLabel(template.purpose) : ''}`.trim();
  const controlCount = targets ? Math.floor((targets.targetCount * control) / 100) : 0;
  const sendCount = targets ? targets.targetCount - controlCount : 0;

  const createReady = canCreate({ creating, hasTemplate: template !== null, block, current });

  async function create() {
    if (!template || !createReady) return;
    setCreating(true);
    setCreateError('');
    setFieldErrors({});
    try {
      const r = await api<{ id: number }>('/api/campaigns', {
        method: 'POST',
        body: { ...conditions, name: name.trim() || defaultName, templateId: template.id, controlPercent: control },
        silent: true,
      });
      toast(tr('campaign.new.toast'));
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
        <h3><i>1</i>{tr('campaign.new.step1')}</h3>
        <div className="cp-seg" role="group" aria-label={tr('campaign.new.categoryLabel')}>
          {enabledCategories(industries).map((k) => (
            <button type="button" key={k} aria-pressed={category === k} onClick={() => setCategory(k)}>{CATEGORY_LABEL[k]}</button>
          ))}
        </div>
        {block === 'disabled' && (
          <div className="cp-warn" role="alert">
            {tr('campaign.new.industryOff', { category: CATEGORY_LABEL[category] })}{' '}
            <AppLink href="/settings" style={{ color: '#8F2F2F', fontWeight: 600 }}>{tr('campaign.new.industryOn')}</AppLink>
          </div>
        )}
        {block === 'failed' && (
          <div className="cp-warn" role="alert">
            {tr('campaign.new.industryFailed')}{' '}
            <button type="button" className="cp-lk" onClick={() => setIndustriesTry((n) => n + 1)}>{tr('common.retry')}</button>
          </div>
        )}
        {customerIds.length > 0 ? (
          <div className="cp-ok">
            {tr('campaign.new.pickedPre')}<b className="num">{tr('common.persons', { n: customerIds.length })}</b>{tr('campaign.new.pickedPost', { category: CATEGORY_LABEL[category] })}{' '}
            <button type="button" className="cp-lk" onClick={() => { setCustomerIds([]); setStatus('DUE'); }}>{tr('campaign.new.byCondition')}</button>
          </div>
        ) : (
          <>
            <div className="cp-seg" role="group" aria-label={tr('campaign.new.statusLabel')}>
              {TARGET_STATUSES.map((s) => (
                <button type="button" key={s.status} aria-pressed={status === s.status} onClick={() => setStatus(s.status)}>{tr(s.label)}</button>
              ))}
              <button type="button" aria-pressed={status === null} onClick={() => setStatus(null)}>{tr('campaign.new.anyStatus')}</button>
            </div>
            <span className="cp-note kp-fade" key={status ?? 'all'}>
              {statusInfo ? (
                <>
                  <b>{tr(statusInfo.label)}</b>: {tr(statusInfo.desc)}
                </>
              ) : (
                <>{tr('campaign.new.anyStatusNote', { category: CATEGORY_LABEL[category] })}</>
              )}
            </span>
          </>
        )}

        <button type="button" className="cp-lk" style={{ alignSelf: 'flex-start' }} onClick={() => setMore((m) => !m)} aria-expanded={more}>
          {more ? tr('campaign.new.moreClose') : tr('campaign.new.moreOpen')}
        </button>
        {more && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span className="cp-lbl">{tr('campaign.new.visitCount')}</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input className="cp-fld num" type="number" min={0} value={visitMin} onChange={(e) => setVisitMin(e.target.value)} style={{ width: 80 }} aria-label={tr('campaign.new.visitMin')} />
                <span className="cp-note">~</span>
                <input className="cp-fld num" type="number" min={0} value={visitMax} onChange={(e) => setVisitMax(e.target.value)} style={{ width: 80 }} aria-label={tr('campaign.new.visitMax')} />
                <span className="cp-note">{tr('campaign.new.times')}</span>
              </span>
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span className="cp-lbl">{tr('campaign.new.lastTreatment', { category: CATEGORY_LABEL[category] })}</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input className="cp-fld num" type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label={tr('campaign.new.lastFrom')} />
                <span className="cp-note">~</span>
                <input className="cp-fld num" type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label={tr('campaign.new.lastTo')} />
              </span>
            </label>
          </div>
        )}

        <div className="cp-box" aria-live="polite">
          {current?.error ? (
            <span style={{ color: '#8F2F2F', fontSize: 13 }}>{current.error}</span>
          ) : (
            <>
              <span style={{ fontWeight: 600 }} className="num">
                {tr('campaign.new.reachable')}<CountUp value={targets?.targetCount ?? 0} duration={300} format={unit(tr('campaign.unit.persons'))} />
                {!current && <span className="cp-note" style={{ fontWeight: 400, marginLeft: 8 }}>{tr('campaign.new.recounting')}</span>}
              </span>
              <div className="cp-excl">
                {EXCLUSION_LABEL.map(([code, label]) => (
                  <span className="cp-ex" key={code}>
                    {tr('campaign.new.excluded', { reason: tr(label) })} <b className="num">{targets?.excludedCounts[code] ?? 0}</b>
                  </span>
                ))}
              </div>
              <span className="cp-note">{tr('campaign.new.excludeNote')}</span>
            </>
          )}
        </div>
      </div>

      <div className="cp-step">
        <h3><i>2</i>{tr('campaign.new.step2')}</h3>
        {templateError && <div className="cp-warn">{templateError}</div>}
        {templates?.length === 0 && (
          <div className="cp-warn">
            {tr('campaign.new.noAdTemplates')}<AppLink href="/campaigns/templates" style={{ color: '#8F2F2F', fontWeight: 600 }}>{tr('campaign.new.createTemplate')}</AppLink>
          </div>
        )}
        <div className="cp-tpls">
          {templates?.map((t) => (
            <button type="button" className="cp-tpl" key={t.id} aria-pressed={template?.id === t.id} onClick={() => setPicked(t.id)}>
              <b>
                {t.name}
                {recommended?.id === t.id && <span className="cp-chip" style={{ background: '#F1E4E0', color: '#7A4450', marginLeft: 6 }}>{tr('campaign.new.recommended')}</span>}
              </b>
              <span className="cp-note">{purposeLabel(t.purpose)}</span>
            </button>
          ))}
        </div>
        {template && (
          <div className="cp-box">
            <span className="cp-note">{tr('campaign.new.bodyNote')}</span>
            <span style={{ fontSize: 13.5, whiteSpace: 'pre-line' }}>{template.body}</span>
          </div>
        )}
        <AppLink href="/campaigns/templates" className="cp-lk" style={{ alignSelf: 'flex-start' }}>{tr('campaign.new.manageTemplates')}</AppLink>
      </div>

      <div className="cp-step">
        <h3><i>3</i>{tr('campaign.new.step3')}</h3>
        <label style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
          <span className="cp-lbl">{tr('campaign.new.controlPercent')}</span>
          <input type="range" min={0} max={MAX_CONTROL} step={5} value={control} onChange={(e) => setControl(Number(e.target.value))} style={{ accentColor: '#7A4450' }} />
          <input className="cp-fld num" type="number" min={0} max={MAX_CONTROL} value={control} onChange={(e) => setControl(Math.max(0, Math.min(MAX_CONTROL, Number(e.target.value) || 0)))} style={{ width: 80 }} aria-label={tr('campaign.new.controlPercentAria')} />
          <span className="cp-note">{tr('campaign.new.controlNote')}</span>
        </label>
        <div className="cp-stats">
          <div className="cp-stat"><b className="num">{tr('common.persons', { n: sendCount })}</b><span>{tr('campaign.new.expectSend')}</span></div>
          <div className="cp-stat"><b className="num">{tr('common.persons', { n: controlCount })}</b><span>{tr('campaign.new.expectControl')}</span></div>
        </div>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span className="cp-lbl">{tr('campaign.new.nameLabel')}</span>
          <input className="cp-fld" value={name} maxLength={100} placeholder={defaultName} onChange={(e) => setName(e.target.value)} aria-invalid={Boolean(fieldErrors.name)} />
          {fieldErrors.name && <span style={{ color: '#8F2F2F', fontSize: 12 }}>{fieldErrors.name}</span>}
        </label>
      </div>

      <div className="cp-step">
        {createError && <div className="cp-warn" role="alert">{createError}</div>}
        <div className="cp-btns">
          <span className="cp-note">{tr('campaign.new.afterCreate')}</span>
          <button type="button" className="cp-btn p" disabled={!createReady} onClick={create}>
            {creating ? tr('campaign.new.creating') : tr('campaign.new.createBtn')}
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
    // 다른 캠페인으로 바뀌었거나 화면을 떠났으면 지난 응답은 버린다 (#344)
    let alive = true;
    api<Campaign>(`/api/campaigns/${id}?page=0&size=1`, { silent: true })
      .then((c) => {
        if (!alive) return;
        setCampaign(c);
        setReviewed(Boolean(c.reviewedAt));
      })
      .catch((err) => alive && setLoadError(errorText(err)));
    api<Preview>(`/api/campaigns/${id}/preview?page=0&size=50`, { silent: true })
      .then((p) => alive && setPreview(p))
      .catch((err) => alive && setPreviewError(errorText(err)));
    return () => {
      alive = false;
    };
  }, [id]);

  if (loadError) return <div className="cp-warn" role="alert">{loadError}</div>;
  if (!campaign) return <p className="cp-note">{tr('common.loading')}</p>;
  if (campaign.status !== 'DRAFT') {
    return (
      <div className="cp-ok">
        {tr('campaign.new.alreadyPre')}{CAMPAIGN_STATUS_LABEL[campaign.status] ?? campaign.status}{tr('campaign.new.alreadyPost')}{' '}
        <AppLink href="/campaigns" style={{ color: '#7A4450', fontWeight: 600 }}>{tr('campaign.new.toListLink')}</AppLink>
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
        toast(`${tr('campaign.new.sentToast', { n: r.sendCount })}${r.skippedCount ? tr('campaign.new.sentSkipped', { n: r.skippedCount }) : ''}`);
      } else {
        const iso = seoulLocalToIso(at);
        if (!iso) throw new ApiError(400, 'invalid', tr('campaign.new.needTime'));
        await api(`/api/campaigns/${id}/schedule`, { method: 'PUT', body: { scheduledAt: iso }, silent: true });
        toast(tr('campaign.new.scheduledToast', { at: at.replace('T', ' ') }));
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
      toast(tr('campaign.canceledToast'), 'warn');
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
          <span className="cp-chip" style={{ background: '#F5EBD3', color: '#6B4509' }}>{tr('campaign.new.draftChip')}</span>
        </div>
        <span className="cp-meta">
          {tr('campaign.new.metaLine', { category: campaign.category ? CATEGORY_LABEL[campaign.category] : tr('customer.msgKind.reconfirm'), n: campaign.targetCount, control: campaign.controlCount, percent: campaign.controlPercent })}
        </span>
      </div>

      <div className="cp-step">
        <h3><i>4</i>{tr('campaign.new.step4')}</h3>
        {previewError ? (
          <div className="cp-warn" role="alert">
            {previewError}
            {previewError.includes('매장 정보') && (
              <>
                {' '}
                <AppLink href="/settings" style={{ color: '#8F2F2F', fontWeight: 600 }}>{tr('campaign.new.toSettings')}</AppLink>
              </>
            )}
          </div>
        ) : !preview ? (
          <p className="cp-note">{tr('common.loading')}</p>
        ) : (
          <>
            <div className="cp-stats">
              <div className="cp-stat"><b className="num">{tr('common.persons', { n: preview.sendCount })}</b><span>{tr('campaign.new.statSend')}</span></div>
              <div className="cp-stat"><b className="num">{tr('common.persons', { n: preview.controlCount })}</b><span>{tr('campaign.new.statControl')}</span></div>
              <div className="cp-stat"><b className="num">{formatWon(preview.estimatedCost)}</b><span>{tr('campaign.new.statCost')}</span></div>
              <div className="cp-stat"><b className="num" style={preview.unsendableCount ? { color: '#8F2F2F' } : undefined}>{tr('common.persons', { n: preview.unsendableCount })}</b><span>{tr('campaign.new.statUnsendable')}</span></div>
            </div>
            {preview.unsendableCount > 0 && (
              <div className="cp-warn">{tr('campaign.new.unsendableWarn')}</div>
            )}
            <div className="cp-pv">
              <div className="cp-phone" aria-label={tr('campaign.new.phoneLabel')}>
                <div className="top">{tr('campaign.new.phoneTop')}</div>
                <div className="cp-bubble kp-fade" key={item?.customerId ?? 0}>{item?.text ?? tr('campaign.new.cannotSendTo')}</div>
              </div>
              <div style={{ flex: '1 1 240px', display: 'flex', flexDirection: 'column', gap: 10 }}>
                <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <span className="cp-lbl">{tr('campaign.new.previewCustomer')}</span>
                  <select className="cp-fld" value={pv} onChange={(e) => setPv(Number(e.target.value))}>
                    {preview.items.items.map((x, i) => (
                      <option key={x.customerId} value={i}>
                        {x.customerName ?? tr('campaign.list.deletedCustomer')}
                        {x.messageType ? '' : tr('campaign.new.cannotSendSuffix')}
                      </option>
                    ))}
                  </select>
                </label>
                {item && (
                  <div className="cp-box">
                    <span style={{ fontSize: 13 }} className="num">
                      {item.messageType ?? tr('campaign.new.statUnsendable')} · {tr('campaign.new.bytesUnit', { n: item.bytes ?? '—' })}
                    </span>
                    {item.missingVariables.length > 0 && <span className="cp-note">{tr('campaign.new.missingVars', { vars: item.missingVariables.join(', ') })}</span>}
                    <span className="cp-note">
                      {tr('campaign.new.smsNote')}
                      {campaign.messageKind === 'INFO'
                        ? tr('campaign.new.noAdLabel')
                        : tr('campaign.new.autoLabel')}
                    </span>
                  </div>
                )}
                {preview.items.totalElements > preview.items.items.length && <span className="cp-note">{tr('campaign.new.firstOnly', { n: preview.items.items.length })}</span>}
              </div>
            </div>
          </>
        )}
      </div>

      <div className="cp-step">
        <h3><i>5</i>{tr('campaign.new.step5')}</h3>
        <div className="cp-seg" role="group" aria-label={tr('campaign.new.modeLabel')}>
          <button type="button" aria-pressed={mode === 'now'} onClick={() => setMode('now')}>{tr('campaign.new.modeNow')}</button>
          <button type="button" aria-pressed={mode === 'schedule'} onClick={() => setMode('schedule')}>{tr('campaign.new.modeSchedule')}</button>
        </div>
        {mode === 'schedule' && (
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span className="cp-lbl">{tr('campaign.new.scheduleTime')}</span>
            <input className="cp-fld num" type="datetime-local" value={at} min={seoulLocalInput(new Date())} onChange={(e) => setAt(e.target.value)} />
          </label>
        )}
        {(nightNow || (mode === 'schedule' && nightHour(at))) && (
          <div className="cp-warn">
            {campaign.messageKind === 'INFO'
              ? tr('campaign.new.nightInfo')
              : tr('campaign.new.nightAd')}
          </div>
        )}
        <span className="cp-note">{tr('campaign.new.recheck')}</span>
      </div>

      <div className="cp-step">
        <h3><i>6</i>{tr('campaign.new.step6')}</h3>
        <label className="cp-check">
          <input type="checkbox" checked={reviewed} onChange={(e) => review(e.target.checked)} />
          <span>{tr('campaign.new.reviewCheck')}</span>
        </label>
        {campaign.reviewedBy && reviewed && <span className="cp-note">{tr('campaign.new.reviewRecord', { name: campaign.reviewedBy.name })}</span>}
        {error && <div className="cp-warn" role="alert">{error}</div>}
        <div className="cp-btns">
          {confirmCancel ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="cp-note">{tr('campaign.new.askCancel')}</span>
              <button type="button" className="cp-btn" style={{ color: '#8F2F2F' }} disabled={busy} onClick={cancel}>{tr('campaign.new.doCancel')}</button>
              <button type="button" className="cp-btn" disabled={busy} onClick={() => setConfirmCancel(false)}>{tr('campaign.new.keep')}</button>
            </span>
          ) : (
            <button type="button" className="cp-btn" disabled={busy} onClick={() => setConfirmCancel(true)}>{tr('campaign.new.cancelBtn')}</button>
          )}
          <button type="button" className="cp-btn p" disabled={busy || !reviewed || !preview || preview.sendCount === 0} onClick={send}>
            {busy ? tr('common.processing') : mode === 'now' ? tr('campaign.new.sendNow', { n: preview?.sendCount ?? 0 }) : tr('campaign.new.registerSchedule')}
          </button>
        </div>
        {!reviewed && <span className="cp-note">{tr('campaign.new.needReview')}</span>}
      </div>
    </>
  );
}

export default function CampaignNewPage() {
  useStyleSheet('CampaignsPage', styleText);
  return (
    <>
      <PinGate id="campaign-new" title={tr('campaign.new.pageTitle')}>
        <div className="cp-wrap">
          <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
            <div>
              <h1 className="cp-h1">{tr('campaign.new.pageTitle')}</h1>
              <p className="cp-sub">{tr('campaign.new.pageSub')}</p>
            </div>
            <AppLink href="/campaigns" className="cp-btn">{tr('campaign.new.toList')}</AppLink>
          </div>
          <section className="cp-card cp-main" aria-label={tr('campaign.new.formLabel')}>
            <div className="cp-main-in">
              <Body />
            </div>
          </section>
          <p className="cp-foot">{tr('campaign.new.foot')}</p>
        </div>
      </PinGate>
    </>
  );
}
