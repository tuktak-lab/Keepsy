'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

// `/`는 `/dashboard`로 보낸다 (docs ARCHITECTURE 4장 「화면 경로」).
// 정적 빌드(output: 'export')라 서버 redirect를 쓸 수 없어 브라우저에서 이동한다.
export default function RootPage() {
  const router = useRouter();

  useEffect(() => {
    router.replace('/dashboard');
  }, [router]);

  return null;
}
