// 계정(ACC) API 타입. 이름은 docs CONTRACTS 4장 그대로.

/** POST /api/session 요청 */
export type LoginRequest = { email: string; password: string };

/** POST /api/session 응답 */
export type LoginResponse = { shopId: number; shopName: string };
