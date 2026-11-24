// 계정(ACC) API 타입. 이름은 docs CONTRACTS 4장 그대로. 세션(GET /api/session) 타입은 lib/session.ts에 있다.
import type { StaffRef } from '@/lib/session';

/** POST /api/session 요청 */
export type LoginRequest = { email: string; password: string };

/** POST /api/session 응답 */
export type LoginResponse = { shopId: number; shopName: string };

/** PUT /api/session/worker 요청 */
export type WorkerRequest = { pin: string };

/** PUT /api/session/worker 응답 */
export type WorkerResponse = StaffRef;
