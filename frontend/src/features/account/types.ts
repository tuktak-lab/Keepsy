// 계정(ACC) API 타입. 이름은 docs CONTRACTS 4장 그대로. 세션(GET /api/session) 타입은 lib/session.ts에 있다.
import type { StaffRef } from '@/lib/session';

/** POST /api/shops 요청 (매장 가입). 매장 연락처는 필수로 저장하고(F73), 업종은 여러 개 고른다(F72) */
export type SignUpRequest = {
  email: string;
  password: string;
  shopName: string;
  ownerName: string;
  ownerPin: string;
  phone: string;
  industries: string[];
};

/** POST /api/shops 응답 */
export type SignUpResponse = { shopId: number };

/** POST /api/session 요청 */
export type LoginRequest = { email: string; password: string };

/** POST /api/session 응답 */
export type LoginResponse = { shopId: number; shopName: string };

/** POST /api/password-reset 요청 (재설정 메일) */
export type PasswordResetRequest = { email: string };

/** PUT /api/password-reset 요청 (새 비밀번호) */
export type PasswordResetConfirmRequest = { token: string; newPassword: string };

/** PUT /api/session/worker 요청 */
export type WorkerRequest = { pin: string };

/** PUT /api/session/worker 응답 */
export type WorkerResponse = StaffRef;
