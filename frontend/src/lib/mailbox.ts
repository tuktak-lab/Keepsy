// 가상 메일함 규칙 (FR-ACC-06, F49 · F59). 서버 GET /api/virtual-mailbox와 같은 이메일 규칙을 화면에서 먼저 본다.

/** 이메일 형식, 255자 이하. 맞으면 다듬은 값, 틀리면 null (POST /api/password-reset의 email과 같은 규칙) */
export function normalizeEmail(input: string): string | null {
  const v = input.trim();
  return v.length <= 255 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) ? v : null;
}

export type BodyPart = { text: string; href?: string };

// 서버가 만드는 재설정 링크: /password-reset?token=<URL-safe Base64: 영문 · 숫자 · - · _> (PasswordResetService)
const RESET_LINK = /\/password-reset\?token=[A-Za-z0-9_-]+/g;

/** 메일 본문을 글자와 재설정 링크로 나눈다. 링크는 이 형식만 만들고 나머지는 그냥 글자다 (NFR-08) */
export function splitBody(body: string): BodyPart[] {
  const parts: BodyPart[] = [];
  let last = 0;
  for (const m of body.matchAll(RESET_LINK)) {
    if (m.index > last) parts.push({ text: body.slice(last, m.index) });
    parts.push({ text: m[0], href: m[0] });
    last = m.index + m[0].length;
  }
  if (last < body.length) parts.push({ text: body.slice(last) });
  return parts;
}
