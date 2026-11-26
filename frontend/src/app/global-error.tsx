'use client'; // error boundary는 클라이언트 컴포넌트여야 한다

import '@/styles/base.css';
import { ErrorView } from '@/components/common/ErrorView';

// 루트 layout까지 실패했을 때의 화면 (#407). 루트 layout을 대신하므로 <html> · <body> · 글꼴 · 공통 CSS를 직접 넣는다.
export default function GlobalError(props: { error: unknown; retry: () => void }) {
  return (
    <html lang="ko">
      <head>
        <title>Keepsy</title>
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;500;600;700&display=swap"
        />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=Noto+Serif+KR:wght@400;500;600&family=Playfair+Display:wght@400;500;600&display=swap"
        />
      </head>
      <body>
        <ErrorView error={props.error} retry={props.retry} />
      </body>
    </html>
  );
}
