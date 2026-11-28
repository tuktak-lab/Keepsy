// 진행 중인 조회(GET) 수. 로딩 화면(components/common/LoadingScreen)이 읽는다. 요청 · 응답 · 오류에는 관여하지 않는다.

let started = 0;
let settled = 0;

/** 조회 하나를 센다. 결과와 오류는 그대로 돌려준다 */
export function trackLoading<T>(request: Promise<T>): Promise<T> {
  started += 1;
  return request.finally(() => {
    settled += 1;
  });
}

/** 지금까지 시작한 조회 수와 끝난(성공 · 실패) 조회 수 */
export function loadingCounts(): { started: number; settled: number } {
  return { started, settled };
}
