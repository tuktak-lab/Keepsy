// 모든 화면의 API 호출 래퍼. 화면 코드는 fetch('/api/…')를 직접 부르지 않고 이 파일의 api()를 쓴다.
// 오류 code별 화면 동작은 docs ARCHITECTURE 4장 「API 호출 규칙」, 오류 모양은 CONTRACTS 2장을 따른다.

// CSRF: Spring Security SPA 방식(csrf.spa()) 기본값으로 가정한다. 백엔드 #3에서 확정되면 이 두 값만 맞춘다.
export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';

const FALLBACK_MESSAGE = '잠시 후 다시 시도해 주세요';

/** 목록 API의 페이지 응답 (CONTRACTS 3장). 요청은 `?page=0&size=20` */
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number };

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: unknown;

  constructor(status: number, code: string, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export type PinPrompt = { owner: boolean; message: string };

/** 화면 쪽 동작. UI(PIN 입력 창, 오류 표시)는 디자인이 정해지면 configureApi()로 바꿔 끼운다. */
export type ApiHandlers = {
  /** 다른 화면으로 이동 */
  navigate: (path: string) => void;
  /** PIN 입력 창을 띄우고 POST /api/session/pin 인증까지 마친 뒤, 인증되면 true. 취소하면 false */
  promptPin: (prompt: PinPrompt) => Promise<boolean>;
  /** 오류 message를 사용자에게 보여 줌 */
  showError: (message: string) => void;
};

let handlers: ApiHandlers = {
  navigate: (path) => {
    // 이미 그 화면이면 다시 열지 않는다 (로그인 화면에서 unauthorized를 받아 새로고침이 반복되는 것 방지)
    if (window.location.pathname !== path) window.location.assign(path);
  },
  promptPin: async () => false,
  showError: (message) => window.alert(message),
};

export function configureApi(next: Partial<ApiHandlers>): void {
  handlers = { ...handlers, ...next };
}

/** 오류 code 처리와 같은 방식으로 화면을 옮긴다 (예: 근무자가 없으면 /shift) */
export function navigate(path: string): void {
  handlers.navigate(path);
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
/** silent: 「그 밖의 code」를 showError로 띄우지 않는다. 화면이 오류를 직접 보여 줄 때 (예: PIN 입력 창 안의 pin_invalid) */
export type ApiOptions = { method?: Method; body?: unknown; silent?: boolean };

/**
 * 같은 출처 `/api` 호출. path는 `/api/…` 전체를 쓴다 (CONTRACTS 4장 경로 그대로).
 * 실패하면 code별 동작을 한 뒤 ApiError를 던진다.
 */
export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const res = await fetch(path, {
    method,
    headers: buildHeaders(method, options.body),
    body: encodeBody(options.body),
    credentials: 'same-origin',
  });

  if (res.ok) {
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  const error = await readError(res);
  switch (error.code) {
    case 'unauthorized':
      handlers.navigate('/login');
      throw error;
    case 'worker_required':
      handlers.navigate('/shift');
      throw error;
    case 'pin_required':
    case 'owner_pin_required':
      if (await askPin({ owner: error.code === 'owner_pin_required', message: error.message })) {
        return api<T>(path, options);
      }
      throw error;
    default:
      if (!options.silent) handlers.showError(error.message);
      throw error;
  }
}

function buildHeaders(method: Method, body: unknown): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') {
    const token = readCookie(CSRF_COOKIE);
    if (token) headers[CSRF_HEADER] = token;
  }
  return headers;
}

function encodeBody(body: unknown): BodyInit | undefined {
  if (body === undefined) return undefined;
  return body instanceof FormData ? body : JSON.stringify(body);
}

function readCookie(name: string): string | undefined {
  const prefix = name + '=';
  const found = document.cookie.split('; ').find((c) => c.startsWith(prefix));
  return found ? decodeURIComponent(found.slice(prefix.length)) : undefined;
}

async function readError(res: Response): Promise<ApiError> {
  try {
    const { error } = (await res.json()) as { error: { code: string; message: string; details?: unknown } };
    return new ApiError(res.status, error.code, error.message, error.details);
  } catch {
    // 서버 오류 모양이 아닌 응답 (개발 서버 프록시 실패 등)
    return new ApiError(res.status, 'internal_error', FALLBACK_MESSAGE);
  }
}

// 한 화면에서 여러 API가 동시에 PIN을 요구하면 PIN 입력 창은 한 번만 띄운다 (ARCHITECTURE 6장: 영역별 API)
const pendingPin = new Map<boolean, Promise<boolean>>();

function askPin(prompt: PinPrompt): Promise<boolean> {
  let pending = pendingPin.get(prompt.owner);
  if (!pending) {
    pending = handlers.promptPin(prompt).finally(() => pendingPin.delete(prompt.owner));
    pendingPin.set(prompt.owner, pending);
  }
  return pending;
}
