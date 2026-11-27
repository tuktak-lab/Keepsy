import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

/** 화면 선택지 · 고지 문구에 쓰는 GET /api/shop의 일부 (CONTRACTS 6장). 근무자면 읽을 수 있다 */
export type ShopBasics = { industries?: string[]; retentionYears?: number };

/**
 * GET /api/shop. undefined는 아직 모름(불러오는 중), null은 불러오지 못함.
 * 화면이 열릴 때마다 다시 읽으므로 매장 설정에서 바꾼 값이 바로 반영된다.
 * `reloadKey`를 바꾸면 다시 읽고, 읽는 동안은 undefined다 (불러오지 못했을 때 「다시 시도」).
 */
export function useShop(reloadKey = 0): ShopBasics | null | undefined {
  const [loaded, setLoaded] = useState<{ key: number; value: ShopBasics | null } | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<ShopBasics>('/api/shop', { silent: true })
      .then((shop) => !cancelled && setLoaded({ key: reloadKey, value: shop }))
      .catch(() => !cancelled && setLoaded({ key: reloadKey, value: null }));
    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  return loaded !== null && loaded.key === reloadKey ? loaded.value : undefined;
}

/**
 * 켜 둔 매장 업종 (GET /api/shop의 `industries`, CONTRACTS 3절 「매장 업종」). 예약 · 캠페인 화면이 선택지를 거를 때 쓴다.
 * undefined는 아직 모름(불러오는 중), null은 불러오지 못해 모름(`lib/menus`의 `enabledCategories` · `pickableMenus`가 모두 보여 준다).
 */
export function useIndustries(reloadKey = 0): string[] | null | undefined {
  const shop = useShop(reloadKey);
  return shop === undefined ? undefined : (shop?.industries ?? null);
}
