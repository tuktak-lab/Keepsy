import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

/**
 * 켜 둔 매장 업종 (GET /api/shop의 `industries`, CONTRACTS 3절 「매장 업종」). 예약 · 캠페인 화면이 선택지를 거를 때 쓴다.
 * undefined는 아직 모름(불러오는 중), null은 불러오지 못해 모름(`lib/menus`의 `enabledCategories` · `pickableMenus`가 모두 보여 준다).
 * 화면이 열릴 때마다 다시 읽으므로 매장 설정에서 바꾼 값이 바로 반영된다.
 * `reloadKey`를 바꾸면 다시 읽고, 읽는 동안은 undefined다 (불러오지 못했을 때 「다시 시도」).
 */
export function useIndustries(reloadKey = 0): string[] | null | undefined {
  const [loaded, setLoaded] = useState<{ key: number; value: string[] | null } | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<{ industries?: string[] }>('/api/shop', { silent: true })
      .then((shop) => !cancelled && setLoaded({ key: reloadKey, value: shop.industries ?? null }))
      .catch(() => !cancelled && setLoaded({ key: reloadKey, value: null }));
    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  return loaded !== null && loaded.key === reloadKey ? loaded.value : undefined;
}
