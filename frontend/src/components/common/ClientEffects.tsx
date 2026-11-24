'use client';

import { useEffect } from 'react';
import { ToastHost } from '@/components/common/Toast';
import { startMotion } from '@/utils/motion';

// 모든 화면 공통: 전역 토스트 자리 + 탭 인디케이터 · 이미지 페이드 보조 (데모 Vite의 App · main.tsx 역할)
export function ClientEffects() {
  useEffect(() => startMotion(), []);
  return <ToastHost />;
}
