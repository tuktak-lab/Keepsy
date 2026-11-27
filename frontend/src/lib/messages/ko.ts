// 화면 문구 원본 사전 (FR-I18N-02, 결정 F74). 키를 여기에 먼저 추가하면 en.ts에 빠진 키는 typecheck가 잡는다.
// 키는 `영역.이름` (예: `customer.new.title`). 화면 문구 이전은 기능 폴더 단위로 한다 (F74 ④).
export const ko = {
  'locale.label': '언어',
  'format.won': '{amount}원',
} as const;

export type MessageKey = keyof typeof ko;
