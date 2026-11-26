'use client'; // error boundary는 클라이언트 컴포넌트여야 한다

import { ErrorView } from '@/components/common/ErrorView';

// 매장 화면 본문에서 오류가 나면 보이는 화면 (#407). (shop)/layout.tsx 안쪽이라 사이드바 · 상단바는 그대로 남는다.
export default function ShopError(props: { error: unknown; retry: () => void }) {
  return <ErrorView error={props.error} retry={props.retry} inShell />;
}
