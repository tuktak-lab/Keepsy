'use client';

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

// 상단 경로 표시에 화면이 아는 이름(고객 상세의 고객 이름)을 넘기는 작은 context. 나머지 경로 표시는 pathname 표 (lib/shellNav.ts)
const Ctx = createContext<{ name: string; set: (name: string) => void }>({ name: '', set: () => {} });

export function ShellTitleProvider(props: { children: ReactNode }) {
  const [name, set] = useState('');
  return <Ctx.Provider value={{ name, set }}>{props.children}</Ctx.Provider>;
}

/** 상단 경로 표시가 쓸 이름 */
export const useShellTitleValue = () => useContext(Ctx).name;

/** 화면이 이름을 알려 준다. 화면을 떠나면 지운다 */
export function useShellTitle(name?: string | null) {
  const { set } = useContext(Ctx);
  useEffect(() => {
    set(name ?? '');
    return () => set('');
  }, [name, set]);
}
