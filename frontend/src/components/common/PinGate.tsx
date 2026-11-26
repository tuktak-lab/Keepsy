'use client';

import type { ReactNode } from 'react';
import { PinForm } from '@/components/common/PinDialog';
import { useSession } from '@/lib/session';

// 잠긴 기능 화면에 들어갈 때 PIN 입력 창을 먼저 보여 준다 (FR-ACC-08).
// 화면을 가리는 것은 표시일 뿐이고, 권한 검사는 API가 한다 (NFR-04). 화면의 API가 pin_required ·
// owner_pin_required를 주면 api.ts가 PIN 입력 창을 다시 띄운다.

// 화면 id → 그 화면의 기본 기능 키 (docs ARCHITECTURE 4장 「화면 경로」, CONTRACTS 3장 「기능 키」)
const FEATURE: Record<string, string> = {
  performance: 'SALES_ANALYTICS',
  campaigns: 'CAMPAIGN_VIEW',
  'campaign-new': 'CAMPAIGN_SEND', // 캠페인 작성은 발송 권한 (CONTRACTS 3장)
  settings: 'OWNER_PIN',
};

export function PinGate(props: { id: string; title: string; children: ReactNode }) {
  const session = useSession();

  // 세션을 읽는 중에는 아무것도 열지 않는다
  if (!session) return null;

  const access = session.features[FEATURE[props.id] ?? props.id];
  // 원장 PIN 화면도 PIN 인증 여부까지만 본다: GET /api/session에 원장 여부가 없어서,
  // 원장이 아닌 스탭이면 화면의 API가 owner_pin_required를 주고 그때 원장 PIN을 받는다.
  const open = access === 'PUBLIC' || session.pinStaff !== null;

  if (open) return <>{props.children}</>;

  return (
    <div style={{ display: 'flex', justifyContent: 'center', padding: '72px 24px' }}>
      {/* 인증되면 refreshSession()으로 pinStaff가 채워져 다시 그려진다 */}
      <PinForm title={props.title} owner={access === 'OWNER_PIN'} onDone={() => {}} />
    </div>
  );
}
