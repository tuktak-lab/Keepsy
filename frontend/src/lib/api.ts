// 모든 화면의 API 호출 래퍼. 화면 코드는 fetch('/api/…')를 직접 부르지 않고 이 파일의 api()를 쓴다.
// 오류 code별 화면 동작은 docs ARCHITECTURE 4장 「API 호출 규칙」, 오류 모양은 CONTRACTS 2장을 따른다.

import { errorText } from './i18n.ts';
import { trackLoading } from './loading.ts';

// CSRF: Spring Security SPA 방식(csrf.spa()) 기본값으로 가정한다. 백엔드 #3에서 확정되면 이 두 값만 맞춘다.
export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';

const FALLBACK_MESSAGE = '잠시 후 다시 시도해 주세요';
const NETWORK_MESSAGE = '서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요';

/** 목록 API의 페이지 응답 (CONTRACTS 3장). 요청은 `?page=0&size=20` */
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number };

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: unknown;

  constructor(status: number, code: string, message: string, details?: unknown) {
    // 한국어가 아니면 code로 번역한 안내를 message로 쓴다 (F74 ③). 화면은 지금처럼 message를 보여 주면 된다
    super(errorText(code, message));
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
 * - 서버에 닿지 못하면 `network_error`(status 0), 성공 응답인데 JSON이 아니면 `invalid_response`. 둘 다 자동으로 다시 보내지 않는다
 * - `csrf_invalid`는 한 번만 다시 보낸다: 처음 접속해 CSRF 쿠키가 없으면 첫 POST가 거부되고, 그 응답에 쿠키가 내려온다 (서버가 처리 전에 거부한 요청이라 다시 보내도 안전)
 */
export function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const result = request<T>(path, options, false);
  return (options.method ?? 'GET') === 'GET' ? trackLoading(result) : result; // 로딩 화면이 조회 수를 읽는다
}

/** 목록의 앞에서부터 count건 (silent). size는 최대 100이라 넘으면 다음 페이지를 이어 받는다. 「더 보기」 · 다시 받기용 */
export async function apiFirst<T>(path: string, count: number): Promise<Page<T>> {
  const size = Math.min(count, 100);
  const sep = path.includes('?') ? '&' : '?';
  const items: T[] = [];
  // shortcut: 페이지를 받는 사이 목록이 바뀌면 경계에서 한 건이 겹치거나 빠질 수 있다. 서버에 커서 페이징이 생기면 바꾼다
  for (let page = 0; ; page++) {
    const res = await api<Page<T>>(`${path}${sep}page=${page}&size=${size}`, { silent: true });
    items.push(...res.items);
    if (res.items.length === 0 || items.length >= Math.min(count, res.totalElements)) return { ...res, items: items.slice(0, count) };
  }
}

async function request<T>(path: string, options: ApiOptions, csrfRetried: boolean): Promise<T> {
  const method = options.method ?? 'GET';
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers: buildHeaders(method, options.body),
      body: encodeBody(options.body),
      credentials: 'same-origin',
    });
  } catch {
    return fail(new ApiError(0, 'network_error', NETWORK_MESSAGE), options);
  }

  if (res.ok) {
    const text = await res.text();
    if (!text) return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      return fail(new ApiError(res.status, 'invalid_response', FALLBACK_MESSAGE), options);
    }
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
        return request<T>(path, options, csrfRetried);
      }
      throw error;
    case 'csrf_invalid':
      if (!csrfRetried) return request<T>(path, options, true);
      return fail(error, options);
    default:
      return fail(error, options);
  }
}

/** 「그 밖의 code」: silent가 아니면 message를 보여 주고 던진다 */
function fail(error: ApiError, options: ApiOptions): never {
  if (!options.silent) handlers.showError(error.message);
  throw error;
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
