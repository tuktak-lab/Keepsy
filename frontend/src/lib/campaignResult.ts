// 캠페인 결과 화면 계산 (순수 함수). 값은 docs CONTRACTS 3절 「캠페인 상태」 · 「발송 상태」, 4장 퍼널 · 대조군 비교

import { t } from './i18n.ts';

export const CAMPAIGN_STATUS_LABEL: Record<string, string> = {
  get DRAFT() {
    return t('campaign.status.draft');
  },
  get SCHEDULED() {
    return t('campaign.status.scheduled');
  },
  get SENDING() {
    return t('campaign.status.sending');
  },
  get SENT() {
    return t('campaign.status.sent');
  },
  get CANCELED() {
    return t('campaign.status.canceled');
  },
};

export const SEND_STATUS_LABEL: Record<string, string> = {
  get PENDING() {
    return t('campaign.send.pending');
  },
  get SENT() {
    return t('campaign.send.sent');
  },
  get FAILED() {
    return t('campaign.send.failed');
  },
};

/** 전환율(%) 글자. 분모가 0이면 보이지 않는다 (CONTRACTS 퍼널 · 대조군 비교) */
export const rateText = (count: number, base: number): string | null => (base > 0 ? `${Math.round((count / base) * 1000) / 10}%` : null);
