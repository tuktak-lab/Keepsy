// 예약 캘린더 계산 (순수 함수, 화면과 분리해 테스트한다).
// 날짜는 `YYYY-MM-DD`(Asia/Seoul) 문자열로만 다루고, 시각은 `startAt`(ISO 8601, 오프셋 포함)에서 서울 기준으로 읽는다 (CONTRACTS 3장, NFR-12).

export type DateStr = string;

/** CONTRACTS 3장 「예약 상태」 · 「예약 경로」 */
export type ReservationStatus = 'CONFIRMED' | 'COMPLETED' | 'CANCELED' | 'NO_SHOW';
export type Channel = 'NAVER' | 'KAKAO' | 'PHONE' | 'STORE' | 'ETC';

export const CHANNEL_LABEL: Record<Channel, string> = {
  NAVER: '네이버 예약',
  KAKAO: '카카오톡',
  PHONE: '전화',
  STORE: '매장 직접',
  ETC: '기타',
};

export const DOW_LABEL = ['월', '화', '수', '목', '금', '토', '일'];
const DAY_OF_WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

const seoul = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** 서울 기준 날짜와, 그날 0시부터 지난 분 */
export function seoulParts(at: string | number | Date): { date: DateStr; minutes: number } {
  const p = Object.fromEntries(seoul.formatToParts(new Date(at)).map((x) => [x.type, x.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, minutes: Number(p.hour) * 60 + Number(p.minute) };
}

export const todayInSeoul = (now: number): DateStr => seoulParts(now).date;

function utc(date: DateStr): Date {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

export function addDays(date: DateStr, days: number): DateStr {
  const t = utc(date);
  t.setUTCDate(t.getUTCDate() + days);
  return t.toISOString().slice(0, 10);
}

/** 0 = 월요일 … 6 = 일요일 */
export const dayIndex = (date: DateStr): number => (utc(date).getUTCDay() + 6) % 7;

/** 그 날짜가 속한 주의 월요일 (주간은 월요일부터 7일, F10) */
export const weekStart = (date: DateStr): DateStr => addDays(date, -dayIndex(date));

export const weekDates = (start: DateStr): DateStr[] => Array.from({ length: 7 }, (_, i) => addDays(start, i));

export const dayOfMonth = (date: DateStr): number => Number(date.slice(8, 10));

/** 미니 달력 칸 (월요일 시작) */
export function monthCells(year: number, month0: number): { date: DateStr; day: number; inMonth: boolean }[] {
  const first: DateStr = `${year}-${String(month0 + 1).padStart(2, '0')}-01`;
  const start = weekStart(first);
  const days = new Date(Date.UTC(year, month0 + 1, 0)).getUTCDate();
  const rows = Math.ceil((dayIndex(first) + days) / 7);
  return Array.from({ length: rows * 7 }, (_, i) => {
    const date = addDays(start, i);
    return { date, day: dayOfMonth(date), inMonth: Number(date.slice(5, 7)) === month0 + 1 };
  });
}

/** `1,260 → "21:00"` */
export const formatMinutes = (minutes: number): string =>
  `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;

/** `"09:30" → 570` */
export const parseHm = (hm: string): number => {
  const [h, m] = hm.split(':').map(Number);
  return h * 60 + m;
};

export const endMs = (startAt: string, minutes: number): number => Date.parse(startAt) + minutes * 60_000;

export type Tone = 'booked' | 'done' | 'unclosed';

/** 캘린더에 그리는 상태. 취소 · 노쇼는 시간을 차지하지 않아 그리지 않는다 (FR-RSV-04) → null */
export function toneOf(status: ReservationStatus, end: number, now: number): Tone | null {
  if (status === 'COMPLETED') return 'done';
  if (status === 'CONFIRMED') return end <= now ? 'unclosed' : 'booked';
  return null;
}

// ── 겹친 예약 배치 ──
// 같은 칸(요일)에서 시간이 겹치는 예약끼리만 가로로 나눈다. 한 묶음이 maxLanes칸을 넘으면 앞 칸만 그리고 나머지는 "+N건"으로 접는다.
export type Placement = { lane: number; n: number; hidden: boolean };
export type Overflow = { group: number; start: number; end: number; lane: number; n: number; count: number };
type Span = { id: number; start: number; end: number };

export function layout<T extends Span>(list: T[], groupOf: (t: T) => number, groups: number, maxLanes: number) {
  const pos = new Map<number, Placement>();
  const more: Overflow[] = [];
  for (let g = 0; g < groups; g++) {
    const arr = list.filter((a) => groupOf(a) === g).sort((a, b) => a.start - b.start || a.id - b.id);
    let cluster: T[] = [];
    let laneEnds: number[] = [];
    let clusterStart = 0;
    let clusterEnd = -1;
    const flush = () => {
      if (!cluster.length) return;
      const lanes = laneEnds.length;
      if (lanes > maxLanes) {
        const keep = maxLanes - 1;
        let hidden = 0;
        for (const a of cluster) {
          const p = pos.get(a.id)!;
          const h = p.lane >= keep;
          pos.set(a.id, { lane: p.lane, n: maxLanes, hidden: h });
          if (h) hidden++;
        }
        more.push({ group: g, start: clusterStart, end: clusterEnd, lane: keep, n: maxLanes, count: hidden });
      } else {
        for (const a of cluster) pos.get(a.id)!.n = Math.max(lanes, 1);
      }
      cluster = [];
      laneEnds = [];
    };
    for (const a of arr) {
      if (a.start >= clusterEnd) {
        flush();
        clusterStart = a.start;
      }
      let lane = laneEnds.findIndex((e) => e <= a.start);
      if (lane < 0) lane = laneEnds.length;
      laneEnds[lane] = a.end;
      clusterEnd = Math.max(clusterEnd, a.end);
      pos.set(a.id, { lane, n: 1, hidden: false });
      cluster.push(a);
    }
    flush();
  }
  return { pos, more };
}

// ── 시간축 · 선택할 수 없는 시간 (FR-RSV-02) ──
export type OpeningHour = { dayOfWeek: string; open: string | null; close: string | null };

const DEFAULT_AXIS = { start: 9, end: 20 };

/** 보이는 주의 영업시간과 예약을 모두 담는 시간축 (시 단위, 정시로 맞춤). 영업시간이 하나도 없으면 9~20시 */
export function axisRange(hours: OpeningHour[], spans: { start: number; end: number }[]): { start: number; end: number } {
  const starts: number[] = [];
  const ends: number[] = [];
  for (const h of hours) {
    if (h.open && h.close) {
      starts.push(parseHm(h.open));
      ends.push(parseHm(h.close));
    }
  }
  for (const s of spans) {
    starts.push(s.start);
    ends.push(s.end);
  }
  if (!starts.length) return DEFAULT_AXIS;
  const start = Math.max(0, Math.floor(Math.min(...starts) / 60));
  const end = Math.min(24, Math.ceil(Math.max(...ends) / 60));
  return end > start ? { start, end } : DEFAULT_AXIS;
}

/** 요일별 영업시간 밖 구간(분). 정기 휴무인 요일(`open`이 null)은 하루 전체 */
export function closedRanges(
  hours: OpeningHour[],
  index: number,
  axis: { start: number; end: number },
): [number, number][] {
  const from = axis.start * 60;
  const to = axis.end * 60;
  const h = hours.find((x) => x.dayOfWeek === DAY_OF_WEEK[index]);
  if (!h) return [];
  if (!h.open || !h.close) return [[from, to]];
  const ranges: [number, number][] = [];
  if (parseHm(h.open) > from) ranges.push([from, Math.min(parseHm(h.open), to)]);
  if (parseHm(h.close) < to) ranges.push([Math.max(parseHm(h.close), from), to]);
  return ranges;
}

export type DayOff = { id: number; date: DateStr; staffId: number | null; reason: string | null };

/** 그날의 휴무 사유. 매장 휴무는 항상, 스탭 휴무는 그 스탭만 보고 있을 때. 없으면 null (사유가 비어 있으면 빈 문자열) */
export function dayOffOn(dayOffs: DayOff[], date: DateStr, staffId: number | null): string | null {
  const hit = dayOffs.find((d) => d.date === date && (d.staffId === null || d.staffId === staffId));
  return hit ? (hit.reason ?? '') : null;
}
