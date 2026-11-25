import { useCallback, useEffect, useRef, useState } from 'react';
import { latestOnly } from '@/lib/latest';

// 화면 하나가 「처음 불러오기 + 다시 불러오기」를 같은 요청으로 할 때 쓰는 작은 훅 (#344).
// - key가 바뀌면 이전 key의 응답은 버린다 (고객을 바꾸면 지난 고객 정보가 보이지 않음)
// - 화면을 떠난 뒤 도착한 응답으로 상태를 바꾸지 않는다
// - 같은 key로 reload가 겹치면 마지막 응답만 반영한다
// - fetcher가 매번 새 함수여도 다시 부르지 않는다 (key로만 다시 부른다)
// 이어 붙이는 「더 보기」나 폼 채우기에는 쓰지 않는다. 그 화면의 페이지 · 오류 규칙을 그대로 둔다.

type Settled<T> = { key: string; data: T | null; error: unknown };

export function useLoad<T>(key: string | null, fetcher: () => Promise<T>) {
  const fetcherRef = useRef(fetcher);
  useEffect(() => {
    fetcherRef.current = fetcher;
  });
  const [latest] = useState(latestOnly);
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const [reloading, setReloading] = useState(false);

  const start = useCallback(
    (k: string) =>
      latest.run(
        fetcherRef.current(),
        (data) => setSettled({ key: k, data, error: null }),
        (error) => setSettled((s) => ({ key: k, data: s?.key === k ? s.data : null, error })),
      ),
    [latest],
  );

  useEffect(() => {
    if (key === null) return;
    start(key);
    return () => latest.cancel();
  }, [key, start, latest]);

  /** 같은 key로 다시 받는다. 받는 동안 지금 값은 그대로 보인다 */
  const reload = useCallback(async () => {
    if (key === null) return;
    setReloading(true);
    try {
      await start(key);
    } finally {
      setReloading(false);
    }
  }, [key, start]);

  const current = settled?.key === key ? settled : null;
  return {
    data: current?.data ?? null,
    error: current?.error ?? null,
    loading: key !== null && (current === null || reloading),
    reload,
  };
}
