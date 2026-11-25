// 캠페인 결과 화면 계산 (순수 함수). 값은 docs CONTRACTS 3절 「캠페인 상태」 · 「발송 상태」, 4장 퍼널 · 대조군 비교

export const CAMPAIGN_STATUS_LABEL: Record<string, string> = {
  DRAFT: '작성 중',
  SCHEDULED: '예약 발송 대기',
  SENDING: '발송 중',
  SENT: '발송 완료',
  CANCELED: '취소',
};

export const SEND_STATUS_LABEL: Record<string, string> = { PENDING: '대기', SENT: '발송 성공', FAILED: '실패' };

/** 전환율(%) 글자. 분모가 0이면 보이지 않는다 (CONTRACTS 퍼널 · 대조군 비교) */
export const rateText = (count: number, base: number): string | null => (base > 0 ? `${Math.round((count / base) * 1000) / 10}%` : null);
