'use client'; // error boundary는 클라이언트 컴포넌트여야 한다

import { ErrorView } from '@/components/common/ErrorView';

// 셸 밖 화면(소개 · 로그인 · 가입 · 근무자 전환 등)에서 오류가 나면 보이는 화면 (#407)
export default function Error(props: { error: unknown; retry: () => void }) {
  return <ErrorView error={props.error} />;
}
