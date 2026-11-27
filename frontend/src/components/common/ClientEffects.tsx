'use client';

import { useEffect } from 'react';
import { PinDialogHost, openPinDialog } from '@/components/common/PinDialog';
import { ScrollTopButton } from '@/components/common/ScrollTopButton';
import { ToastHost, toast } from '@/components/common/Toast';
import { configureApi } from '@/lib/api';
import { startMotion } from '@/utils/motion';

// 모든 화면 공통: 전역 토스트 · PIN 입력 창 · 「맨 위로」 버튼 자리 + 탭 인디케이터 · 이미지 페이드 보조 (데모 Vite의 App · main.tsx 역할)
// api.ts의 화면 동작(PIN 입력 창, 오류 표시)을 여기서 끼운다 (docs ARCHITECTURE 4장 「API 호출 규칙」)
export function ClientEffects() {
  useEffect(() => startMotion(), []);
  useEffect(() => {
    configureApi({ promptPin: openPinDialog, showError: (message) => toast(message, 'error') });
  }, []);
  return (
    <>
      <ToastHost />
      <PinDialogHost />
      <ScrollTopButton />
    </>
  );
}
