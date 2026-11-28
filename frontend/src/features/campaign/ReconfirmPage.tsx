import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { AppLink } from '@/components/common/AppLink';
import { PinGate } from '@/components/common/PinGate';
import { toast } from '@/components/common/Toast';
import { ApiError, api, type Page } from '@/lib/api';
import { dueText } from '@/lib/consentReconfirm';
import { intlLocale } from '@/lib/i18n';
import { dotDate, seoulToday } from '@/lib/customerDetail';
import { formatDateTime } from '@/lib/format';
import type { TemplatePurpose } from '@/lib/messageTemplate';
import styleText from './CampaignsPage.css?inline';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { t as tr } from '@/lib/i18n';

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
const errorText = (err: unknown) => (err instanceof ApiError ? err.message : tr('common.pin.tryLater'));

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
  // 「다시 시도」를 누를 때마다 늘려 처음 불러오기를 다시 한다
  const [rowsTry, setRowsTry] = useState(0);
  const [templatesTry, setTemplatesTry] = useState(0);
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
        setError('');
        // 처음에는 기한이 됐거나 지난 고객을 골라 둔다 (목록은 기한이 이른 순)
        const opened = seoulToday();
        setPicked(new Set(page.items.filter((r) => dueText(r.dueDate, opened).overdue).map((r) => r.customerId)));
      })
      .catch((err) => setError(errorText(err)));
  }, [rowsTry]);

  useEffect(() => {
    api<Page<Template>>('/api/message-templates?page=0&size=100', { silent: true })
      .then((p) => (setTemplates(p.items.filter((t) => t.purpose === 'CONSENT_RECONFIRM')), setTemplateError('')))
      .catch((err) => setTemplateError(errorText(err)));
  }, [templatesTry]);

  const template = templates?.find((t) => t.id === templateId) ?? templates?.[0] ?? null;
  const allPicked = Boolean(rows?.length) && rows!.every((r) => picked.has(r.customerId));
  const defaultName = tr('campaign.reconf.defaultName', { date: today });

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
      toast(tr('campaign.reconf.toast'));
      router.push(`/campaigns/new?id=${r.id}`);
    } catch (err) {
      setCreateError(errorText(err));
      setCreating(false);
    }
  }

  return (
    <>
      <section className="cp-card cp-main" aria-label={tr('campaign.reconf.listLabel')}>
        <div className="cp-row2">
          <h2 style={{ margin: 0, fontSize: 16 }}>
            {tr('campaign.reconf.targets')} <span className="num">{total}</span>{tr('campaign.unit.persons')}
          </h2>
          <span className="cp-meta">{tr('campaign.reconf.sortNote')}</span>
        </div>

        {error && (
          <div className="cp-warn" role="alert">
            {error}
            {/* 더 보기 실패는 남은 「더 보기」 버튼으로 다시 받는다 */}
            {rows === null && (
              <>
                {' '}
                <button type="button" className="cp-lk" onClick={() => setRowsTry((n) => n + 1)}>{tr('common.retry')}</button>
              </>
            )}
          </div>
        )}
        {rows === null && !error && <p className="cp-note">{tr('common.loading')}</p>}
        {rows?.length === 0 && <div className="cp-ok">{tr('campaign.reconf.empty')}</div>}

        {rows && rows.length > 0 && (
          <div style={{ overflowX: 'auto' }}>
            <table className="cp-tbl">
              <thead>
                <tr>
                  <th style={{ width: 32 }}>
                    <input type="checkbox" checked={allPicked} onChange={toggleAll} aria-label={tr('campaign.reconf.pickAll')} />
                  </th>
                  <th>{tr('common.customer')}</th>
                  <th>{tr('campaign.reconf.colConsentDate')}</th>
                  <th>{tr('campaign.reconf.colLast')}</th>
                  <th>{tr('campaign.reconf.colDue')}</th>
                  <th>{tr('campaign.reconf.colNight')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const due = dueText(r.dueDate, today);
                  return (
                    <tr key={r.customerId}>
                      <td>
                        <input type="checkbox" checked={picked.has(r.customerId)} onChange={() => toggle(r.customerId)} aria-label={tr('campaign.reconf.pickOne', { name: r.name })} />
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
                      <td>{r.nightAdConsent ? tr('customer.agreed') : tr('customer.notAgreed')}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {rows && rows.length < total && (
          <button type="button" className="cp-btn" style={{ alignSelf: 'center' }} disabled={loading} onClick={() => load(rows.length + SIZE)}>
            {loading ? tr('common.loading') : tr('customer.moreBtn')}
          </button>
        )}
        <span className="cp-note">
          {tr('campaign.reconf.afterSend')}
        </span>
      </section>

      <section className="cp-card cp-main" aria-label={tr('campaign.reconf.createLabel')}>
        <div className="cp-main-in">
          <div className="cp-step">
            <h3><i>1</i>{tr('campaign.reconf.stepTemplate')}</h3>
            {templateError && (
              <div className="cp-warn" role="alert">
                {templateError}{' '}
                <button type="button" className="cp-lk" onClick={() => setTemplatesTry((n) => n + 1)}>{tr('common.retry')}</button>
              </div>
            )}
            {templates?.length === 0 && (
              <div className="cp-warn">
                {tr('campaign.reconf.noTemplate')}<AppLink href="/campaigns/templates" style={{ color: '#8F2F2F', fontWeight: 600 }}>{tr('campaign.new.createTemplate')}</AppLink>
              </div>
            )}
            <div className="cp-tpls">
              {templates?.map((t) => (
                <button type="button" className="cp-tpl" key={t.id} aria-pressed={template?.id === t.id} onClick={() => setTemplateId(t.id)}>
                  <b>{t.name}</b>
                  <span className="cp-note">{tr('campaign.purpose.reconfirm')}</span>
                </button>
              ))}
            </div>
            {template && (
              <div className="cp-box">
                <span className="cp-note">{tr('campaign.reconf.bodyNote')}</span>
                <span style={{ fontSize: 13.5, whiteSpace: 'pre-line' }}>{template.body}</span>
              </div>
            )}
          </div>

          <div className="cp-step">
            <h3><i>2</i>{tr('campaign.reconf.stepName')}</h3>
            <input className="cp-fld" value={name} maxLength={100} placeholder={defaultName} onChange={(e) => setName(e.target.value)} aria-label={tr('campaign.reconf.stepName')} />
          </div>

          <div className="cp-step">
            <div className="cp-box">
              <span className="cp-note">
                {tr('campaign.reconf.rule1')}
                {tr('campaign.reconf.rule2')}
              </span>
            </div>
            {picked.size > MAX_TARGETS && <div className="cp-warn">{tr('campaign.reconf.maxTargets', { max: MAX_TARGETS.toLocaleString(intlLocale()) })}</div>}
            {createError && <div className="cp-warn" role="alert">{createError}</div>}
            <div className="cp-btns">
              <span className="cp-note">{tr('campaign.new.afterCreate')}</span>
              <button
                type="button"
                className="cp-btn p"
                disabled={creating || !template || picked.size === 0 || picked.size > MAX_TARGETS}
                onClick={create}
              >
                {creating ? tr('campaign.new.creating') : tr('campaign.reconf.createBtn', { n: picked.size })}
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
      <PinGate id="campaigns" title={tr('campaign.reconf.title')}>
        <div className="cp-wrap">
          <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
            <div>
              <h1 className="cp-h1">{tr('campaign.reconf.title')}</h1>
              <p className="cp-sub">
                {tr('campaign.reconf.sub1')}
                <br />
                {tr('campaign.reconf.sub2')}
              </p>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              <AppLink href="/campaigns/templates" className="cp-btn">{tr('campaign.list.templates')}</AppLink>
              <AppLink href="/campaigns" className="cp-btn">{tr('campaign.new.toList')}</AppLink>
            </div>
          </div>
          <Body />
          <p className="cp-foot">{tr('campaign.reconf.foot')}</p>
        </div>
      </PinGate>
    </>
  );
}
