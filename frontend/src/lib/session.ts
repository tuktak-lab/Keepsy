// 매장 세션 상태 (GET /api/session, docs CONTRACTS 6장 ACC). 상단 근무자 · PIN 인증 표시와 PinGate가 같은 값을 본다.
// 근무자 · PIN 인증을 바꾸는 화면(예: /shift)은 성공한 뒤 refreshSession()을 불러 상단 표시를 맞춘다.
import { useEffect, useSyncExternalStore } from 'react';
import { api, navigate } from './api';

export type StaffRef = { id: number; name: string };
/** 기능 키별 설정 (CONTRACTS 3장 「기능 키」) */
export type Access = 'PUBLIC' | 'PIN' | 'OWNER_PIN';

export type Session = {
  shopId: number;
  shopName: string;
  currentStaff: StaffRef | null;
  pinStaff: StaffRef | null;
  pinExpiresAt: string | null;
  features: Record<string, Access>;
};

let current: Session | null = null;
let loading: Promise<Session | null> | null = null;
let expiryTimer: ReturnType<typeof setTimeout> | undefined;
const listeners = new Set<() => void>();

function set(next: Session | null): void {
  current = next;
  scheduleExpiryCheck(next);
  listeners.forEach((l) => l());
}

// PIN 인증은 서버에서 5분 무입력이면 풀리고, 잠긴 기능을 쓸 때마다 서버가 연장한다 (FR-ACC-08, C23).
// 만료 시각이 되면 다시 읽어서, 그 사이 연장됐으면 표시를 유지하고 아니면 내린다. 조회는 만료를 연장하지 않는다.
function scheduleExpiryCheck(s: Session | null): void {
  clearTimeout(expiryTimer);
  if (!s?.pinExpiresAt) return;
  const wait = new Date(s.pinExpiresAt).getTime() - Date.now() + 500;
  expiryTimer = setTimeout(() => void refreshSession(), Math.max(wait, 0));
}

/** 서버에서 세션을 다시 읽는다. 로그인이 안 됐으면 api.ts 규칙대로 /login으로 가고 null */
export function refreshSession(): Promise<Session | null> {
  loading ??= api<Session>('/api/session')
    .then((s) => {
      set(s);
      return s;
    })
    .catch(() => null)
    .finally(() => {
      loading = null;
    });
  return loading;
}

/** 잠긴 기능 PIN 인증 (POST /api/session/pin). 실패하면 ApiError를 던진다 (pin_invalid · pin_locked · invalid) */
export async function authenticatePin(pin: string): Promise<void> {
  await api('/api/session/pin', { method: 'POST', body: { pin }, silent: true });
  await refreshSession();
}

/** [잠금]: PIN 인증만 해제하고 현재 근무자는 그대로 둔다 */
export async function lockPin(): Promise<void> {
  await api('/api/session/pin', { method: 'DELETE' });
  await refreshSession();
}

/** 로그아웃: 근무자 · PIN 인증도 함께 해제된다 (FR-ACC-02 · 10) */
export async function logout(): Promise<void> {
  await api('/api/session', { method: 'DELETE' });
  set(null);
  navigate('/login');
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** 현재 세션. 처음 읽는 중이거나 정적 빌드(서버)에서는 null */
export function useSession(): Session | null {
  const session = useSyncExternalStore(
    subscribe,
    () => current,
    () => null,
  );
  useEffect(() => {
    if (!current) void refreshSession();
  }, []);
  return session;
}

/** 공통 레이아웃용: 세션을 읽었는데 현재 근무자가 없으면 근무자 PIN 화면으로 보낸다 (FR-ACC-10) */
export function useWorkerRequired(session: Session | null): void {
  useEffect(() => {
    if (session && !session.currentStaff) navigate('/shift');
  }, [session]);
}
