import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { AppLink } from '@/components/common/AppLink';
import styleText from './SchedulePage.css?inline';
import { ReservationForm } from './ReservationForm';
import { useStyleSheet } from '@/hooks/useStyleSheet';
import { t } from '@/lib/i18n';
import type { MessageKey } from '@/lib/messages/ko';
import { api } from '@/lib/api';
import { useNowMs } from '@/lib/clock';
import {
  addDays,
  axisRange,
  CHANNEL_LABEL,
  closedRanges,
  dayIndex,
  dayOfMonth,
  dayOffOn,
  dowLabel,
  dowLabels,
  endMs,
  formatMinutes,
  hoursOfDay,
  isDateStr,
  layout,
  monthCells,
  monthDayText,
  seoulParts,
  staffColumns,
  toneOf,
  todayInSeoul,
  weekDates,
  weekStart,
  type Channel,
  type DateStr,
  type DayOff,
  type OpeningHour,
  type ReservationStatus,
  type Tone,
} from '@/lib/calendar';

// 예약 캘린더 (FR-RSV-02 주간 · FR-RSV-05 일간 · FR-RSV-03 상태 표시 · FR-RSV-01 등록 · 수정).
// 주간은 요일이 열, 일간(`?view=day`)은 스탭이 열이다. 예약 상세는 #128, 시술 마감은 #129.
// API: GET /api/reservations · /api/staff · /api/shop · /api/day-offs (docs CONTRACTS 4장 · 6장). 등록 · 수정 · 취소 · 노쇼는 ReservationForm

type StaffRef = { id: number; name: string };
type Reservation = {
  id: number;
  status: ReservationStatus;
  startAt: string;
  minutes: number;
  staff: StaffRef;
  consultFirst: boolean;
  channel: Channel;
  customer: { id: number; name: string | null } | null; // null = 고객 없는 예약 (E3)
  services: { serviceMenuId: number; name: string }[];
};
type WeekData = { from: DateStr; reservations: Reservation[]; dayOffs: DayOff[] };
type View = 'week' | 'day';
// col: 주간은 요일 열(0~6), 일간은 스탭 열
type Block = { id: number; r: Reservation; tone: Tone; col: number; date: DateStr; start: number; end: number };

const HH = 56; // 1시간 높이(px)
const STATES: [Tone, MessageKey][] = [
  ['booked', 'reservation.state.booked'],
  ['done', 'reservation.state.done'],
  ['unclosed', 'reservation.state.unclosed'], // 캘린더 · 예약 상세는 시술 종료 시각 기준 (calendar.ts toneOf)
];
const toneLabel = (tone: Tone): string => t(STATES.find(([x]) => x === tone)![1]);

// 주소의 `?date=YYYY-MM-DD`: 예약 상세의 「예약 캘린더로」가 그 예약의 날짜로 돌아오게 한다.
// 서버 렌더(정적 빌드)에는 주소가 없어 null이고, 하이드레이션이 끝난 뒤 브라우저 값으로 바뀐다 (첫 렌더가 서버 HTML과 어긋나지 않게)
const noSubscribe = () => () => {};
const readDateParam = (): DateStr | null => {
  const value = new URLSearchParams(window.location.search).get('date');
  return isDateStr(value) ? value : null;
};

const monthDay = (d: DateStr) => monthDayText(d);

export default function SchedulePage() {
  useStyleSheet('SchedulePage', styleText);

  const nowMs = useNowMs();
  const today = nowMs ? todayInSeoul(nowMs) : '';
  const urlDate = useSyncExternalStore(noSubscribe, readDateParam, () => null);
  const [picked, setPicked] = useState<DateStr | null>(null); // 사용자가 고른 기준 날짜. 없으면 주소의 date, 그것도 없으면 오늘
  const anchor = picked ?? urlDate ?? today;
  const [miniPick, setMiniPick] = useState<{ y: number; m: number } | null>(null); // 미니 달력에서 넘겨 본 달. 없으면 기준 날짜의 달
  const [staffId, setStaffId] = useState<number | null>(null); // null = 전체
  const [staff, setStaff] = useState<StaffRef[]>([]);
  const [hours, setHours] = useState<OpeningHour[]>([]);
  const [week, setWeek] = useState<WeekData | null>(null);
  const [failed, setFailed] = useState<DateStr | null>(null);
  const [reload, setReload] = useState(0);
  const [creating, setCreating] = useState(false); // 예약 등록 창 (일정 변경은 예약 상세에서)
  // 보기 단위: `?view=day`면 일간. 서버 렌더(정적 빌드)는 달력 본문을 그리지 않아 첫 값이 달라도 하이드레이션이 어긋나지 않는다
  const [view, setView] = useState<View>(() =>
    typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('view') === 'day' ? 'day' : 'week',
  );

  // 스탭 · 영업시간은 한 번만 읽는다. 실패하면 api()가 오류 안내를 띄우고, 이 화면은 기본값(스탭 필터 없음, 9~20시)으로 그린다.
  useEffect(() => {
    let cancelled = false;
    api<StaffRef[]>('/api/staff')
      .then((list) => !cancelled && setStaff(list))
      .catch(() => {});
    api<{ openingHours: OpeningHour[] }>('/api/shop')
      .then((shop) => !cancelled && setHours(shop.openingHours))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  // 보이는 주의 예약과 휴무일 (주간은 월요일부터 7일, 기간은 최대 7일)
  const from = anchor ? weekStart(anchor) : '';
  useEffect(() => {
    if (!from) return;
    let cancelled = false;
    const to = addDays(from, 6);
    Promise.all([
      api<{ reservations: Reservation[] }>(`/api/reservations?from=${from}&to=${to}`),
      api<DayOff[]>(`/api/day-offs?from=${from}&to=${to}`),
    ])
      .then(([r, dayOffs]) => {
        if (cancelled) return;
        setWeek({ from, reservations: r.reservations, dayOffs });
      })
      .catch(() => !cancelled && setFailed(from));
    return () => {
      cancelled = true;
    };
  }, [from, reload]);

  const goTo = (date: DateStr) => {
    setPicked(date);
    setMiniPick(null);
  };
  const retry = () => {
    setFailed(null);
    setReload((n) => n + 1);
  };
  // 보기 단위를 바꾼다. 새로고침 · 링크 공유에도 유지되도록 주소의 `view`를 맞춘다 (페이지 이동은 하지 않음)
  const changeView = (next: View, date?: DateStr) => {
    setView(next);
    if (date) goTo(date);
    const url = new URL(window.location.href);
    if (next === 'day') url.searchParams.set('view', 'day');
    else url.searchParams.delete('view');
    window.history.replaceState(window.history.state, '', url);
  };
  const closeForm = useCallback(() => setCreating(false), []);
  // 저장하면 그 예약이 있는 주로 옮겨 다시 읽는다
  const saved = useCallback((date: DateStr) => {
    setCreating(false);
    setPicked(date);
    setMiniPick(null);
    setReload((n) => n + 1);
  }, []);
  const stale = useCallback(() => setReload((n) => n + 1), []); // 창은 그대로 두고 뒤의 캘린더만 다시 읽는다

  const dates = from ? weekDates(from) : [];
  const data = week && week.from === from ? week : null;
  const loadFailed = failed === from && !data;

  // 열: 주간은 요일 7개, 일간은 스탭 (비활성이 된 스탭의 예약이 남아 있으면 그 열도 둔다)
  const isDay = view === 'day';
  const staffCols = isDay ? staffColumns(staff, data?.reservations ?? [], staffId) : [];
  const groups = isDay ? Math.max(staffCols.length, 1) : 7;

  const blocks: Block[] = [];
  for (const r of data?.reservations ?? []) {
    if (staffId !== null && r.staff.id !== staffId) continue;
    const tone = toneOf(r.status, endMs(r.startAt, r.minutes), nowMs); // 취소 · 노쇼는 시간을 차지하지 않아 그리지 않는다
    if (!tone) continue;
    const at = seoulParts(r.startAt);
    const col = isDay ? (at.date === anchor ? staffCols.findIndex((s) => s.id === r.staff.id) : -1) : dates.indexOf(at.date);
    if (col < 0) continue;
    blocks.push({ id: r.id, r, tone, col, date: at.date, start: at.minutes, end: at.minutes + r.minutes });
  }

  const axis = axisRange(isDay ? hoursOfDay(hours, dayIndex(anchor || today || '2026-01-05')) : hours, blocks);
  const axisHours = Array.from({ length: axis.end - axis.start }, (_, i) => axis.start + i);
  const gridHeight = (axis.end - axis.start) * HH;
  const gW = 100 / groups;
  const { pos, more } = layout(blocks, (b) => b.col, groups, isDay ? 99 : 2);
  // 주간 보기에서 겹쳐 숨긴 예약 수 (날짜 열마다). 칸 안 대신 열 아래 「더보기」로 알려 주고 그날 일간 보기로 보낸다 (#415)
  const hiddenOf = (col: number) => more.filter((m) => m.group === col).reduce((n, m) => n + m.count, 0);
  const anyHidden = !isDay && dates.some((_, i) => hiddenOf(i) > 0);
  const topOf = (minutes: number) => ((minutes - axis.start * 60) / 60) * HH;
  // 열마다의 날짜 · 요일 · (일간이면) 스탭. 음영과 열 배경이 같이 쓴다
  const colDefs = isDay
    ? staffCols.map((s) => ({ key: String(s.id), date: anchor, dow: dayIndex(anchor), staffId: s.id as number | null }))
    : dates.map((d, i) => ({ key: d, date: d, dow: i, staffId }));

  const dayBlocks = blocks.filter((b) => b.date === anchor);
  const countOf = (tone: Tone) => dayBlocks.filter((b) => b.tone === tone).length;

  const miniMonth = miniPick ?? (anchor ? { y: Number(anchor.slice(0, 4)), m: Number(anchor.slice(5, 7)) - 1 } : { y: 2026, m: 0 });
  const shiftMini = (delta: number) => {
    const t = new Date(Date.UTC(miniMonth.y, miniMonth.m + delta, 1));
    setMiniPick({ y: t.getUTCFullYear(), m: t.getUTCMonth() });
  };

  const title = (() => {
    if (!from) return '';
    if (isDay) return t('reservation.date.dayTitle', { year: anchor.slice(0, 4), date: monthDay(anchor), dow: dowLabel(dayIndex(anchor)) });
    const end = addDays(from, 6);
    const sameMonth = from.slice(5, 7) === end.slice(5, 7);
    return t('reservation.date.weekTitle', { year: from.slice(0, 4), from: monthDay(from), to: sameMonth ? t('reservation.date.day', { day: dayOfMonth(end) }) : monthDay(end) });
  })();

  // 줄 우선순위: 고객명(없으면 예약 경로) → 시간 → 시술명 → 상태. 카드 높이(=예약 시간)가 모자라면 아래 줄부터 잘린다.
  const block = (b: Block, left: number, width: number, over = false) => {
    const { r, tone } = b;
    const h = Math.max(((b.end - b.start) / 60) * HH - 4, 24);
    const lines = Math.min(3, Math.max(0, Math.floor((h - 8 - 17.5) / 16.2)));
    const route = CHANNEL_LABEL[r.channel] ?? r.channel;
    const treat = r.services.map((s) => s.name).join(' + ');
    const label = r.consultFirst ? `${toneLabel(tone)} · ${t('reservation.consultFirst')}` : toneLabel(tone);
    // 카드 안은 폭이 좁아 보이는 글자는 짧게 (범례 · 요약 · 접근성 이름은 전체 문구)
    const shortTone = tone === 'unclosed' ? t('reservation.state.unclosedShort') : toneLabel(tone);
    const cardLabel = r.consultFirst ? `${shortTone} · ${t('reservation.consultFirst')}` : shortTone;
    const who = r.customer ? (r.customer.name ?? t('reservation.noName')) : route; // 고객 없는 예약은 이름 대신 예약 경로 (E3)
    const range = `${formatMinutes(b.start)}–${formatMinutes(b.end)}`;
    // 모든 예약 카드는 예약 상세로 간다. 일정 변경 · 취소 · 노쇼 · 고객 연결은 거기서 한다 (#128)
    const props = {
      className: `blk s-${tone} l${lines}${r.customer ? '' : ' nocust'}${h < 44 ? ' short' : ''}${over ? ' over' : ''}`,
      'aria-label': t('reservation.block.aria', { who: r.customer ? who : t('reservation.block.noCustomer', { route }), treat, range, label, staff: r.staff.name }),
      title: `${r.staff.name} · ${range}`,
      style: {
        top: topOf(b.start) + 2,
        height: h,
        left: `calc(${left}% + 3px)`,
        width: `calc(${width}% - 6px)`,
      },
    };
    const inner = (
      <>
        <span className="b1">
          <b>{who}</b>
          <span className="num">{formatMinutes(b.start)}</span>
        </span>
        <span className="bt num">{range}</span>
        <span className="b2">{treat}</span>
        <span className="b3">{cardLabel}</span>
      </>
    );
    return (
      <AppLink key={b.id} href={`/reservations/detail?id=${r.id}`} {...props}>
        {inner}
      </AppLink>
    );
  };

  return (
    <>
      <div className="wrap">
        <header className="head">
          <div>
            <h1 className="h1">{t('reservation.schedule.title')}</h1>
            <p className="sub">{t('reservation.schedule.sub')}</p>
          </div>
          <button type="button" className="btn btn-p" onClick={() => setCreating(true)} disabled={staff.length === 0}>
            {t('reservation.schedule.new')}
          </button>
        </header>

        {anchor ? (
          <div className="layout">
            {/* ── 좌측: 미니 달력 · 날짜 요약 · 스탭 ── */}
            <aside className="left card">
              <section className="mini">
                <div className="mini-h">
                  <b className="serif">
                    {t('reservation.date.yearMonth', { year: miniMonth.y, month: miniMonth.m + 1 })}
                  </b>
                  <div>
                    <button type="button" className="ib" aria-label={t('reservation.schedule.prevMonth')} onClick={() => shiftMini(-1)}>
                      ‹
                    </button>
                    <button type="button" className="ib" aria-label={t('reservation.schedule.nextMonth')} onClick={() => shiftMini(1)}>
                      ›
                    </button>
                  </div>
                </div>
                <div className="mini-g">
                  {dowLabels().map((d) => (
                    <span key={d} className="mini-w">
                      {d}
                    </span>
                  ))}
                  {monthCells(miniMonth.y, miniMonth.m).map((c) => (
                    <button
                      key={c.date}
                      type="button"
                      className={'mini-d num' + (c.inMonth ? '' : ' out') + (c.date === today ? ' today' : '') + (c.date === anchor ? ' on' : '')}
                      aria-label={monthDay(c.date)}
                      aria-pressed={c.date === anchor}
                      onClick={() => goTo(c.date)}
                    >
                      {c.day}
                    </button>
                  ))}
                </div>
              </section>

              <section className="daysum">
                <span className="meta">{t('reservation.schedule.selected')}</span>
                <b className="ds-date">
                  {monthDay(anchor)} ({dowLabel(dates.indexOf(anchor))}){anchor === today ? <em>{t('reservation.today')}</em> : null}
                </b>
                {data ? (
                  dayBlocks.length ? (
                    <>
                      <p className="ds-n">
                        {t('reservation.schedule.booking')} <b className="num">{dayBlocks.length}</b>{t('reservation.schedule.countUnit')}
                      </p>
                      <ul className="ds-list">
                        {STATES.map(([tone, text]) => (
                          <li key={tone}>
                            <span className={'sw s-' + tone}></span>
                            {t(text)}
                            <b className="num">{countOf(tone)}</b>
                          </li>
                        ))}
                      </ul>
                    </>
                  ) : (
                    <p className="meta">{t('reservation.schedule.emptyDay')}</p>
                  )
                ) : (
                  <p className="meta">{loadFailed ? t('common.loadFailed') : t('common.loading')}</p>
                )}
              </section>

              <section className="staffbox">
                <span className="lbl">{t('common.staff')}</span>
                <div className="staff" role="group" aria-label={t('common.staff')}>
                  <button type="button" aria-pressed={staffId === null} onClick={() => setStaffId(null)}>
                    {t('reservation.all')}
                  </button>
                  {staff.map((s) => (
                    <button key={s.id} type="button" aria-pressed={staffId === s.id} onClick={() => setStaffId(s.id)}>
                      {s.name}
                    </button>
                  ))}
                </div>
              </section>
            </aside>

            {/* ── 중앙: 주간 · 일간 캘린더 ── */}
            <section className="card center">
              <div className="c-head">
                <div className="c-nav">
                  <button type="button" className="btn btn-sm" onClick={() => goTo(today)}>
                    {t('reservation.today')}
                  </button>
                  <button type="button" className="ib" aria-label={isDay ? t('reservation.schedule.prevDay') : t('reservation.schedule.prevWeek')} onClick={() => goTo(addDays(anchor, isDay ? -1 : -7))}>
                    ‹
                  </button>
                  <button type="button" className="ib" aria-label={isDay ? t('reservation.schedule.nextDay') : t('reservation.schedule.nextWeek')} onClick={() => goTo(addDays(anchor, isDay ? 1 : 7))}>
                    ›
                  </button>
                  <b className="c-title serif num">{title}</b>
                </div>
                <div className="seg" role="group" aria-label={t('reservation.schedule.viewUnit')}>
                  <span className="seg-ind" style={{ transform: `translateX(${isDay ? 0 : 100}%)` }} aria-hidden="true"></span>
                  <button type="button" aria-pressed={isDay} onClick={() => changeView('day')}>
                    {t('reservation.view.day')}
                  </button>
                  <button type="button" aria-pressed={!isDay} onClick={() => changeView('week')}>
                    {t('reservation.view.week')}
                  </button>
                </div>
              </div>
              <div className="legend">
                {STATES.map(([tone, text]) => (
                  <span key={tone}>
                    <i className={'sw s-' + tone}></i>
                    {t(text)}
                  </span>
                ))}
                <span>
                  <i className="sw nocust"></i>{t('reservation.legend.noCustomer')}
                </span>
                <span>
                  <i className="sw off"></i>{t('reservation.legend.off')}
                </span>
              </div>

              <div className="grid-scroll">
                <div className="grid" style={{ minWidth: isDay ? 0 : 640 }}>
                  <div className="g-head">
                    <div className="axis-sp"></div>
                    {isDay
                      ? staffCols.map((s, i) => (
                          <div key={s.id} className="col-h static">
                            <b>{s.name}</b>
                            <span className="num">{t('reservation.count', { n: blocks.filter((b) => b.col === i).length })}</span>
                          </div>
                        ))
                      : dates.map((d, i) => (
                          <button
                            key={d}
                            type="button"
                            className={'col-h' + (d === today ? ' today' : '') + (d === anchor ? ' on' : '')}
                            onClick={() => goTo(d)}
                            onDoubleClick={() => changeView('day', d)}
                            title={t('reservation.schedule.dblClick')}
                            aria-label={`${monthDay(d)} (${dowLabel(i)})`}
                          >
                            <span className="ch-w">{dowLabel(i)}</span>
                            <span className="ch-d num">{dayOfMonth(d)}</span>
                          </button>
                        ))}
                  </div>
                  <div className="g-body">
                    <div className="axis" style={{ height: gridHeight }}>
                      {axisHours.map((h) => (
                        <span key={h} className="num" style={{ top: (h - axis.start) * HH }}>
                          {(h < 10 ? '0' : '') + h}:00
                        </span>
                      ))}
                    </div>
                    <div className="lanes" style={{ height: gridHeight }}>
                      {axisHours.map((h) => (
                        <div key={h} className="hl" style={{ top: (h - axis.start) * HH }}></div>
                      ))}
                      {colDefs.map((c, i) => (
                        <div key={c.key} className={'col' + (c.date === today ? ' today' : '')} style={{ left: `${i * gW}%`, width: `${gW}%` }}></div>
                      ))}
                      {/* 선택할 수 없는 시간: 영업시간 밖, 정기 휴무 요일, 매장 · 스탭 휴무일 (FR-RSV-02). 일간은 스탭 열마다 그 스탭의 휴무일도 반영 */}
                      {colDefs.flatMap((c, i) => {
                        const off = data ? dayOffOn(data.dayOffs, c.date, c.staffId) : null;
                        const ranges: [number, number][] = off !== null ? [[axis.start * 60, axis.end * 60]] : closedRanges(hours, c.dow, axis);
                        return ranges.map(([a, b], k) => (
                          <div
                            key={`${c.key}-${k}`}
                            className="off"
                            title={off !== null ? `${t('reservation.dayOff')}${off ? ' · ' + off : ''}` : t('reservation.outOfHours')}
                            style={{ top: topOf(a), height: topOf(b) - topOf(a), left: `${i * gW}%`, width: `${gW}%` }}
                          >
                            {off !== null && k === 0 ? <span>{t('reservation.dayOff')}{off ? ` · ${off}` : ''}</span> : null}
                          </div>
                        ));
                      })}
                      {blocks.map((b) => {
                        const p = pos.get(b.id);
                        if (!p || p.hidden) return null;
                        // 두 예약이 겹치면: 먼저 온 예약은 칸 전체, 나중 예약은 오른쪽으로 비켜 겹쳐 표시
                        if (!isDay && p.n === 2) return p.lane === 0 ? block(b, b.col * gW, gW) : block(b, b.col * gW + gW * 0.4, gW * 0.6, true);
                        const w = gW / p.n;
                        return block(b, b.col * gW + p.lane * w, w);
                      })}
                    </div>
                  </div>
                  {anyHidden ? (
                    <div className="g-foot">
                      <div className="axis-sp"></div>
                      {dates.map((d, i) => {
                        const n = hiddenOf(i);
                        return (
                          <div key={d} className="gf-cell">
                            {n > 0 ? (
                              <button type="button" className="more-btn" onClick={() => changeView('day', d)} title={t('reservation.moreTitle', { n })}>
                                {t('reservation.more', { n })}
                              </button>
                            ) : null}
                          </div>
                        );
                      })}
                    </div>
                  ) : null}
                  {!data && !loadFailed ? <p className="empty-note">{t('common.loading')}</p> : null}
                  {loadFailed ? (
                    <p className="empty-note">
                      {t('reservation.loadFailed')}{' '}
                      <button type="button" className="btn btn-sm" onClick={retry}>
                        {t('common.retry')}
                      </button>
                    </p>
                  ) : null}
                  {data && isDay && staffCols.length === 0 ? <p className="empty-note">{t('reservation.schedule.noStaff')}</p> : null}
                  {data && (!isDay || staffCols.length > 0) && blocks.length === 0 ? (
                    <p className="empty-note">{isDay ? t('reservation.schedule.emptyOnDay') : t('reservation.schedule.emptyInPeriod')}</p>
                  ) : null}
                </div>
              </div>
              <p className="meta foot">{t('reservation.schedule.foot')}</p>
            </section>
          </div>
        ) : null}
      </div>
      {creating ? <ReservationForm editId={null} initialDate={anchor} staff={staff} onClose={closeForm} onSaved={saved} onStale={stale} /> : null}
    </>
  );
}
