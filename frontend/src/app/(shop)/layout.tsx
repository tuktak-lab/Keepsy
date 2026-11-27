import type { ReactNode } from 'react';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';

// 매장 화면 공통 셸 (docs ARCHITECTURE 4장 「모든 화면에 공통」). 사이드바 · 상단바는 한 번만 그려지고 화면(children)만 바뀐다.
// 로그인 · 가입 · 근무자 전환 · 비밀번호 재설정 · 가상 메일함 · 동의 태블릿 · 디자인 시스템은 셸이 없어 이 그룹 밖에 있다. URL은 그룹 이름이 들어가지 않는다.
export default function ShopLayout({ children }: { children: ReactNode }) {
  return (
    <div className="kp-shell">
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Topbar />
        {children}
      </main>
    </div>
  );
}
