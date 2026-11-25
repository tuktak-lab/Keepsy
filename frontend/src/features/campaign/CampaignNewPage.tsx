import { useMemo, useState, type ReactNode } from 'react';
import { AppLink } from '@/components/common/AppLink';
import { PinGate } from '@/components/common/PinGate';
import { CountUp } from '@/components/common/CountUp';
import { toast } from '@/components/common/Toast';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import styleText from './CampaignsPage.css?inline';

// 캠페인 작성 (FR-CMP-01~09 · 12 · 18). 경로: /campaigns/new
// 데모의 캠페인 관리 한 화면에 있던 작성 6단계를 화면 경로 표(ARCHITECTURE 4장)대로 떼어 왔다. 아직 데모 데이터이고 API 연결은 #138이다.
import { useStyleSheet } from '@/hooks/useStyleSheet';

type Status = '주기 도래' | '이탈 신호' | '장기 미방문';
type Cust = {
  name: string;
  cat: string;
  status: Status;
  consent: boolean;
  optout: boolean;
  next: boolean;
  otherApp: boolean;
  nightOk: boolean;
};

const CATS = ['젤 네일', '네일 케어', '속눈썹 연장', '패디큐어'];
const STATUSES: Status[] = ['주기 도래', '이탈 신호', '장기 미방문'];
// 예측 상태 풀이 (명세서 용어 · FR-PRD-04). 처음 쓰는 원장도 고른 상태의 뜻을 바로 알 수 있게
const STATUS_DESC: Record<Status, string> = {
  '주기 도래': '다음 방문 예상 시기가 됐는데 아직 예약이 없는 고객',
  '이탈 신호': '다음 방문 예상 시기가 지났는데 아직 오지 않은 고객',
  '장기 미방문': '예상 시기가 지나고도 오랫동안 오지 않은 고객',
};
const SHOP = '네일무드 성수';
const UNIT_COST = 20;
const won = (n: number) => n.toLocaleString() + '원';
const unit = (u: string) => (n: number) => n + u;

// 열고 닫히는 안내 영역: 높이를 grid-template-rows 0fr→1fr 로 부드럽게 바꿔 레이아웃 점프를 줄입니다.
function Collapse(props: { open: boolean; children: ReactNode }) {
  return (
    <div className={'cp-col' + (props.open ? ' on' : '')} aria-hidden={!props.open}>
      <div><div className="cp-col-in">{props.children}</div></div>
    </div>
  );
}

const c = (name: string, cat: string, status: Status, o: Partial<Cust> = {}): Cust => ({
  name,
  cat,
  status,
  consent: true,
  optout: false,
  next: false,
  otherApp: false,
  nightOk: false,
  ...o,
});

const CUSTS: Cust[] = [
  c('최유진', '젤 네일', '주기 도래'),
  c('송다인', '젤 네일', '주기 도래', { nightOk: true }),
  c('정소윤', '젤 네일', '주기 도래'),
  c('한지우', '젤 네일', '주기 도래'),
  c('이나래', '젤 네일', '주기 도래', { next: true }),
  c('박세아', '젤 네일', '주기 도래', { consent: false }),
  c('윤하늘', '젤 네일', '주기 도래', { otherApp: true }),
  c('서민아', '젤 네일', '주기 도래', { nightOk: true }),
  c('오서현', '젤 네일', '이탈 신호'),
  c('정하린', '젤 네일', '이탈 신호', { consent: false }),
  c('강유나', '젤 네일', '이탈 신호', { optout: true }),
  c('김예원', '젤 네일', '이탈 신호'),
  c('조은서', '젤 네일', '장기 미방문'),
  c('임채원', '젤 네일', '장기 미방문', { otherApp: true }),
  c('김민지', '네일 케어', '주기 도래'),
  c('배수아', '네일 케어', '주기 도래', { nightOk: true }),
  c('문가영', '네일 케어', '이탈 신호'),
  c('박지은', '속눈썹 연장', '주기 도래'),
  c('홍서윤', '속눈썹 연장', '주기 도래', { next: true }),
  c('안유림', '속눈썹 연장', '이탈 신호'),
  c('류하은', '패디큐어', '주기 도래'),
  c('신다은', '패디큐어', '장기 미방문', { optout: true }),
];

const TPLS = [
  { id: 0, name: '주기 도래 안부', fits: '주기 도래', body: '{고객명} 고객님, 지난 {시술} 이후 시간이 꽤 지났어요. 편한 시간에 예약 링크에서 확인해 보세요.' },
  { id: 1, name: '오랜만이에요', fits: '이탈 신호', body: '{고객명} 고객님, 잘 지내시나요? {시술} 관리 받으신 지 조금 됐어요. 이번 달 일정 확인하고 가세요.' },
  { id: 2, name: '다시 만나요', fits: '장기 미방문', body: '{고객명} 고객님, 오랜만에 인사드려요. 지난 {시술} 기록을 기준으로 가볍게 다시 시작해 보실래요?' },
];

function Body() {
  const [cat, setCat] = useState(CATS[0]);
  const [statuses, setStatuses] = useState<Status[]>(['주기 도래']);
  const [tpl, setTpl] = useState<number | null>(null);
  const [ctrl, setCtrl] = useState(10);
  const [reviewed, setReviewed] = useState(false);
  const [mode, setMode] = useState<'now' | 'schedule'>('now');
  const [night, setNight] = useState(() => {
    const h = new Date().getHours();
    return h >= 21 || h < 8;
  });
  const [nightOnly, setNightOnly] = useState(false);
  const [done, setDone] = useState('');
  const [pvIdx, setPvIdx] = useState(0);

  const targets = useMemo(() => CUSTS.filter((x) => x.cat === cat && statuses.includes(x.status)), [cat, statuses]);
  const ex = {
    consent: targets.filter((x) => !x.consent),
    optout: targets.filter((x) => x.consent && x.optout),
    next: targets.filter((x) => x.consent && !x.optout && x.next),
    other: targets.filter((x) => x.consent && !x.optout && !x.next && x.otherApp),
  };
  const base = targets.filter((x) => x.consent && !x.optout && !x.next && !x.otherApp);
  const otherCheck = targets.filter((x) => x.consent && !x.optout && !x.next && x.otherApp).length;
  const sendable = nightOnly ? base.filter((x) => x.nightOk) : base;
  const nightExcluded = base.length - sendable.length;
  const controlN = Math.min(sendable.length, Math.round((sendable.length * ctrl) / 100));
  const sendN = sendable.length - controlN;
  const cost = sendN * UNIT_COST;

  const mainStatus = statuses[0] ?? '주기 도래';
  const recId = TPLS.find((t) => t.fits === mainStatus)?.id ?? 0;
  const useTpl = tpl ?? recId;
  const tplObj = TPLS[useTpl];
  const pvCust = sendable[Math.min(pvIdx, Math.max(sendable.length - 1, 0))];
  const personalized = (cu?: Cust) =>
    tplObj.body.replace('{고객명}', cu?.name ?? '고객').replace('{시술}', cat);
  const fullMsg = (cu?: Cust) => `(광고) ${SHOP}\n${personalized(cu)}\n무료 수신거부 080-000-0000 (예시)`;

  const blocked = mode === 'now' && night && !nightOnly;
  const canSend = reviewed && sendN > 0 && !blocked;

  // 예측 상태는 하나만 고른다 (CONTRACTS 6장 `predictionStatus`). 공용 세그먼트 알약(motion.ts)도 하나만 표시한다
  const pickStatus = (s: Status) => {
    setStatuses([s]);
    setReviewed(false);
    setPvIdx(0);
  };

  const send = () => {
    if (!canSend) {
      if (!reviewed) toast('검토 체크를 해야 발송할 수 있어요', 'warn');
      else if (blocked) toast('야간(21시~08시)에는 광고를 즉시 발송할 수 없어요 · 예약 발송을 선택해 주세요', 'warn');
      else if (sendN <= 0) toast('발송 가능한 고객이 없어요', 'warn');
      return;
    }
    const isNow = mode === 'now';
    setDone(isNow ? `가상 발송했어요. 실제 문자는 나가지 않습니다. (발송 ${sendN}건 · 대조군 ${controlN}명)` : `내일 11:00 예약 발송으로 등록했어요. (발송 ${sendN}건 · 대조군 ${controlN}명)`);
    toast(isNow ? `가상 발송했어요 · ${sendN}건` : `내일 11:00 예약 발송으로 등록했어요 · ${sendN}건`);
    setReviewed(false);
  };

  return (
    <div className="cp-wrap">
      <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
        <div>
          <h1 className="cp-h1">새 캠페인 만들기</h1>
          <p className="cp-sub">시술 카테고리 하나를 정해 대상을 고르고, 제외 사유와 비용을 확인한 뒤 검토 후 발송해요.</p>
        </div>
        <AppLink href="/campaigns" className="cp-btn">캠페인 목록</AppLink>
      </div>

      <section className="cp-card cp-main" aria-label="캠페인 작성">
          <div className="cp-main-in">
            <>
              <div className="cp-step">
                <h3><i>1</i>시술 카테고리와 대상 선정</h3>
                <div className="cp-seg" role="group" aria-label="시술 카테고리">
                  {CATS.map((k) => (
                    <button type="button" key={k} aria-pressed={cat === k} onClick={() => { setCat(k); setReviewed(false); setPvIdx(0); }}>{k}</button>
                  ))}
                </div>
                <div className="cp-seg" role="group" aria-label="예측 상태">
                  {STATUSES.map((s) => (
                    <button type="button" key={s} aria-pressed={statuses.includes(s)} onClick={() => pickStatus(s)}>{s}</button>
                  ))}
                </div>
                <div className="cp-box">
                  <span style={{ fontWeight: 600 }} className="num">대상 <CountUp value={targets.length} duration={300} format={unit('명')} /> → 발송 가능 <CountUp value={sendable.length} duration={300} format={unit('명')} /></span>
                  <span className="cp-note kp-fade" key={cat + mainStatus}>
                    <b>{mainStatus}</b>: {STATUS_DESC[mainStatus]}
                    <br />
                    {cat} 시술을 받은 고객 중 지금 {mainStatus}인 고객에게 보내요.
                  </span>
                </div>
              </div>

              <div className="cp-step">
                <h3><i>2</i>제외 사유</h3>
                <div className="cp-excl kp-fade" key={cat + statuses.join() + nightOnly}>
                  <span className="cp-ex">수신 미동의 <b className="num">{ex.consent.length}</b></span>
                  <span className="cp-ex">수신 거부 <b className="num">{ex.optout.length}</b></span>
                  <span className="cp-ex">다음 예약 있음 <b className="num">{ex.next.length}</b></span>
                  <span className="cp-ex">다른 앱 예약 확인 필요 <b className="num">{otherCheck}</b></span>
                  {nightOnly && <span className="cp-ex">야간 광고 미동의 <b className="num">{nightExcluded}</b></span>}
                </div>
                <span className="cp-note">다른 앱 예약 확인 필요 고객은 발송 대상에서 빠지며, 다른 앱 예약 목록과 대조한 뒤 직접 안내할 수 있어요.</span>
              </div>

              <div className="cp-step">
                <h3><i>3</i>템플릿 선택</h3>
                <div className="cp-tpls">
                  {TPLS.map((t) => (
                    <button type="button" className="cp-tpl" key={t.id} aria-pressed={useTpl === t.id} onClick={() => setTpl(t.id)}>
                      <b>{t.name} {t.id === recId && <span className="cp-chip" style={{ background: '#F1E4E0', color: '#7A4450', marginLeft: 4 }}>추천</span>}</b>
                      <span className="cp-note">{t.fits} 고객용{t.id === recId ? ' · 선택한 대상 상태 기준 추천(규칙 기반)' : ''}</span>
                    </button>
                  ))}
                </div>
                <div className="cp-pv">
                  <div className="cp-phone" aria-label="가상 휴대폰 미리보기">
                    <div className="top">가상 휴대폰 · 문자 메시지</div>
                    <div className="cp-bubble kp-fade" key={fullMsg(pvCust)}>{fullMsg(pvCust)}</div>
                  </div>
                  <div style={{ flex: '1 1 240px', display: 'flex', flexDirection: 'column', gap: 10 }}>
                    <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                      <span className="cp-lbl">개인화 미리보기 고객</span>
                      <select className="cp-fld" value={pvIdx} onChange={(e) => setPvIdx(Number(e.target.value))}>
                        {sendable.length === 0 && <option value={0}>발송 가능한 고객 없음</option>}
                        {sendable.map((x, i) => <option key={x.name} value={i}>{x.name}</option>)}
                      </select>
                    </label>
                    <div className="cp-box">
                      <span className="cp-note">자동 삽입되며 삭제할 수 없어요</span>
                      <span style={{ fontSize: 13 }}>(광고) 표기<span className="cp-lock">잠금</span></span>
                      <span style={{ fontSize: 13 }}>전송자명 {SHOP}<span className="cp-lock">잠금</span></span>
                      <span style={{ fontSize: 13 }}>무료 수신거부 안내<span className="cp-lock">잠금</span></span>
                    </div>
                    <span className="cp-note">고객 이름과 시술명은 발송 시 고객별로 채워져요.</span>
                  </div>
                </div>
              </div>

              <div className="cp-step">
                <h3><i>4</i>예상 비용·건수와 대조군</h3>
                <div className="cp-stats">
                  <div className="cp-stat"><b className="num"><CountUp value={sendable.length} duration={300} format={unit('명')} /></b><span>발송 가능 대상</span></div>
                  <div className="cp-stat"><b className="num"><CountUp value={controlN} duration={300} format={unit('명')} /></b><span>대조군(발송 안 함)</span></div>
                  <div className="cp-stat"><b className="num"><CountUp value={sendN} duration={300} format={unit('건')} /></b><span>예상 발송</span></div>
                  <div className="cp-stat"><b className="num"><CountUp value={cost} duration={300} format={won} /></b><span>예상 비용(건당 {UNIT_COST}원 예시)</span></div>
                </div>
                <label style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
                  <span className="cp-lbl">대조군 비율</span>
                  <input type="range" min={0} max={30} step={5} value={ctrl} onChange={(e) => setCtrl(Number(e.target.value))} style={{ accentColor: '#7A4450' }} />
                  <input className="cp-fld num" type="number" min={0} max={30} value={ctrl} onChange={(e) => setCtrl(Math.max(0, Math.min(30, Number(e.target.value) || 0)))} style={{ width: 80 }} />
                  <span className="cp-note">% · 대상 중 일부를 무작위로 골라 발송하지 않고, 성과 분석에서 발송군과 비교해요.</span>
                </label>
              </div>

              <div className="cp-step">
                <h3><i>5</i>발송 시점</h3>
                <div className="cp-seg" role="group" aria-label="발송 방식">
                  <button type="button" aria-pressed={mode === 'now'} onClick={() => setMode('now')}>즉시 발송</button>
                  <button type="button" aria-pressed={mode === 'schedule'} onClick={() => setMode('schedule')}>예약 발송</button>
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'center' }}>
                  <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <span className="cp-note">시연용 현재 시각</span>
                    <select className="cp-fld" value={night ? 'n' : 'd'} onChange={(e) => setNight(e.target.value === 'n')}>
                      <option value="d">오후 2:00 (주간)</option>
                      <option value="n">밤 10:30 (야간)</option>
                    </select>
                  </label>
                  <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
                    <input type="checkbox" checked={nightOnly} onChange={(e) => { setNightOnly(e.target.checked); setReviewed(false); }} style={{ accentColor: '#7A4450' }} />
                    야간 광고 수신 동의 고객만 발송
                  </label>
                </div>
                <Collapse open={blocked}>
                  <div className="cp-warn">야간(21시~08시)에는 광고를 즉시 발송할 수 없어요. 예약 발송을 선택하면 다음 날 오전에 보내드려요.
                    <div style={{ marginTop: 8 }}><button type="button" className="cp-btn" tabIndex={blocked ? undefined : -1} onClick={() => setMode('schedule')}>예약 발송으로 바꾸기</button></div>
                  </div>
                </Collapse>
                <Collapse open={!blocked && mode === 'schedule'}>
                  <div className="cp-ok">내일 오전 11:00에 발송돼요. 발송 전까지 문구와 대상을 수정할 수 있어요.</div>
                </Collapse>
              </div>

              <div className="cp-step">
                <h3><i>6</i>검토 후 발송</h3>
                <label className="cp-check">
                  <input type="checkbox" checked={reviewed} onChange={(e) => setReviewed(e.target.checked)} />
                  <span>원장 등 허용된 스탭이 다른 앱 예약 목록과 대조해, 발송 대상에 이미 예약한 고객이 없음을 확인했어요.</span>
                </label>
                {done && <div className="cp-ok kp-fade" key={done}>{done}</div>}
                <div className="cp-btns">
                  <span className="cp-note">MVP는 가상 발송이에요. 실제 문자는 전송되지 않아요.</span>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <button type="button" className="cp-btn">임시 저장</button>
                    <button type="button" className="cp-btn p" aria-disabled={!canSend} onClick={send}>
                      {mode === 'now' ? `${sendN}명에게 가상 발송` : `${sendN}명 예약 발송 등록`}
                    </button>
                  </div>
                </div>
                {!reviewed && <span className="cp-note">검토 체크를 해야 발송할 수 있어요.</span>}
              </div>
            </>
          </div>
        </section>
      <p className="cp-foot">마케팅 수신 동의 고객에게만 발송해요. 문구는 규칙·템플릿 기반으로 추천되며 자동 발송되지 않고, 검토 체크가 필요해요. MVP는 가상 발송입니다.</p>
    </div>
  );
}

export default function CampaignNewPage() {
  useStyleSheet('CampaignsPage', styleText);
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', minHeight: '1100px' }}>
      <div style={{ flex: '0 0 232px' }}>
        <Sidebar active="campaigns" />
      </div>
      <main style={{ flex: '999 1 640px', minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Topbar crumb="파트너 센터 / 캠페인 관리 / 새 캠페인" />
        <PinGate id="campaigns" title="새 캠페인 만들기">
          <Body />
        </PinGate>
      </main>
    </div>
  );
}
