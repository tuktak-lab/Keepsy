import { useSyncExternalStore } from 'react';

// "지금"을 분 단위로 돌려주는 훅. 정적 빌드(서버 렌더)에는 지금이 없어 0이고, 브라우저에서 1분 단위로 맞춘다.
// 마감 미완료 · 노쇼 가능 여부처럼 시각에 따라 표시가 달라지는 화면이 함께 쓴다.
const subscribe = (onChange: () => void) => {
  const timer = setInterval(onChange, 30_000);
  return () => clearInterval(timer);
};
const minute = () => Math.floor(Date.now() / 60_000) * 60_000;

export const useNowMs = (): number => useSyncExternalStore(subscribe, minute, () => 0);
