// PIN 입력 창 요청 줄 (#492). 직원 PIN · 원장 PIN 요청이 동시에 와도 하나씩 차례로 보여 주고, 모든 요청이 끝(true/false)나게 한다.
// api.ts는 같은 종류(owner 여부)끼리만 하나로 묶으므로, 다른 종류가 겹치면 여기서 줄을 선다.
// 화면(PinDialogHost)은 attachPinHost로 구독하고 currentPin()을 그린다.
import type { PinPrompt } from './api.ts';

export type PinRequest = PinPrompt & { id: number; resolve: (ok: boolean) => void };

const queue: PinRequest[] = [];
let nextId = 1;
let listener: (() => void) | null = null;

/** PIN 입력을 요청한다. 인증되면 true, 취소하면 false. 창을 그릴 화면이 없으면 바로 false */
export function requestPin(prompt: PinPrompt): Promise<boolean> {
  return new Promise((resolve) => {
    if (!listener) return resolve(false);
    queue.push({ ...prompt, id: nextId++, resolve });
    listener();
  });
}

/** 지금 보여 줄 요청 (줄의 맨 앞). 없으면 null */
export function currentPin(): PinRequest | null {
  return queue[0] ?? null;
}

/** 맨 앞 요청을 끝내고 다음 요청으로 넘어간다 */
export function settlePin(ok: boolean): void {
  const done = queue.shift();
  if (!done) return;
  done.resolve(ok);
  listener?.();
}

/** 화면이 줄을 구독한다. 구독을 끊으면(화면이 사라지면) 남은 요청은 모두 취소(false)로 끝낸다 */
export function attachPinHost(onChange: () => void): () => void {
  listener = onChange;
  return () => {
    if (listener === onChange) listener = null;
    queue.splice(0).forEach((r) => r.resolve(false));
  };
}
