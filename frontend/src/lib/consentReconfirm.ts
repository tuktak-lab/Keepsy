// 동의 재확인 대상 (FR-CMP-13, D1). 재확인 기한까지 남은 날을 화면 글자로 바꾼다.

/** 'YYYY-MM-DD' 두 날짜 사이의 일수 (to − from) */
function daysBetween(from: string, to: string): number {
  const ms = Date.parse(`${to.slice(0, 10)}T00:00:00Z`) - Date.parse(`${from.slice(0, 10)}T00:00:00Z`);
  return Math.round(ms / 86_400_000);
}

/** 기한까지 남은 날: "D-12" · "오늘 기한" · "3일 지남". overdue는 기한이 오늘이거나 지났는지 */
export function dueText(dueDate: string, today: string): { text: string; overdue: boolean } {
  const left = daysBetween(today, dueDate);
  if (left > 0) return { text: `D-${left}`, overdue: false };
  if (left === 0) return { text: '오늘 기한', overdue: true };
  return { text: `${-left}일 지남`, overdue: true };
}
