import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import '@/styles/base.css';
import { ClientEffects } from '@/components/common/ClientEffects';

export const metadata: Metadata = {
  title: 'Keepsy',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko">
      <head>
        {/* 데모(index.html)에서 옮긴 글꼴 */}
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
        {children}
        <ClientEffects />
      </body>
    </html>
  );
}
