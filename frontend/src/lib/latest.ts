// 「마지막 요청의 응답만 반영」 (#344). 순수 함수라 테스트하기 쉽게 hooks/useLoad.ts에서 떼어 둔다.

/**
 * 요청마다 번호를 매기고, 가장 나중에 시작한 요청의 결과만 콜백으로 넘긴다.
 * `cancel()`을 부르면(키가 바뀜 · 화면을 떠남) 그때까지 시작한 요청은 모두 버린다.
 */
export function latestOnly() {
  let seq = 0;
  return {
    run<T>(promise: Promise<T>, onValue: (value: T) => void, onError: (error: unknown) => void): Promise<void> {
      const mine = ++seq;
      return promise.then(
        (value) => {
          if (mine === seq) onValue(value);
        },
        (error) => {
          if (mine === seq) onError(error);
        },
      );
    },
    cancel() {
      seq++;
    },
  };
}
