'use client';

import { Suspense } from 'react';
import ReservationDetailPage from '@/features/reservation/ReservationDetailPage';

// `?id=`를 읽는 화면은 정적 빌드에서 Suspense 경계가 필요하다 (docs ARCHITECTURE 3장 「정적 빌드의 제약」)
export default function Page() {
  return (
    <Suspense fallback={null}>
      <ReservationDetailPage />
    </Suspense>
  );
}
