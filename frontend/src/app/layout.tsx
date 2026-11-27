import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import '@/styles/base.css';
import { ClientEffects } from '@/components/common/ClientEffects';
import { LocaleRoot } from '@/components/common/LocaleRoot';
import { LOCALE_PENDING_ATTR, LOCALE_STORAGE_KEY, LOCALES } from '@/lib/i18n';

export const metadata: Metadata = {
  title: 'Keepsy',
};

// 저장된 화면 언어가 한국어가 아니면, LocaleRoot가 적용할 때까지 본문을 숨겨 한국어 HTML이 잠깐 보이지 않게 한다 (F74)
const localePendingScript = `try{var l=localStorage.getItem(${JSON.stringify(LOCALE_STORAGE_KEY)});if(l!=='ko'&&${JSON.stringify(LOCALES)}.indexOf(l)>=0)document.documentElement.setAttribute(${JSON.stringify(LOCALE_PENDING_ATTR)},'')}catch(e){}`;

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    // 위 스크립트와 LocaleRoot가 <html>의 속성을 hydration 전후에 바꾼다
    <html lang="ko" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: localePendingScript }} />
        <style>{`html[${LOCALE_PENDING_ATTR}] body{visibility:hidden}`}</style>
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
        <LocaleRoot>{children}</LocaleRoot>
        <ClientEffects />
      </body>
    </html>
  );
}
