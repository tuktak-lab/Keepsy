import { useState, type FormEvent } from "react";
import { AppLink } from "@/components/common/AppLink";
import { Sidebar } from "@/components/layout/Sidebar";
import { Topbar } from "@/components/layout/Topbar";
import { toast } from "@/components/common/Toast";
import { api, ApiError, type Page } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { MESSAGE_KIND_LABEL, normalizePhone } from "@/lib/virtualPhone";
import styles from "./VirtualPhonePage.module.css";

// 가상 휴대폰 (FR-CMP-11 · 16). GET /api/virtual-phone/messages · POST /api/virtual-phone/unsubscribe.
// 기능 키 CAMPAIGN_SEND(기본 PIN)는 서버가 검사하고, pin_required는 api.ts가 PIN 창을 띄워 다시 부른다 (NFR-04).
// 실제 문자는 오가지 않는다 (A6).

type InboxItem = {
  id: number;
  kind: string;
  messageType: string;
  body: string;
  receivedAt: string;
};

const SIZE = 20;
const NOTICE_DELAY_MS = 4000;
const errorText = (err: unknown) =>
  err instanceof ApiError ? err.message : "잠시 후 다시 시도해 주세요";

export default function VirtualPhonePage() {
  const [input, setInput] = useState("");
  const [inputError, setInputError] = useState("");
  const [phone, setPhone] = useState<string | null>(null);
  const [items, setItems] = useState<InboxItem[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  // 상태 표시줄 시계 (서울, NFR-12). 화면을 연 시각으로 둔다
  const [clock] = useState(() =>
    new Intl.DateTimeFormat("ko-KR", {
      timeZone: "Asia/Seoul",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(new Date()),
  );

  async function load(target: string, count = SIZE) {
    setLoading(true);
    setError("");
    try {
      const page = await api<Page<InboxItem>>(
        `/api/virtual-phone/messages?phone=${encodeURIComponent(target)}&page=0&size=${Math.min(count, 100)}`,
        { silent: true },
      );
      setPhone(target);
      setItems(page.items);
      setTotal(page.totalElements);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }

  function open(e: FormEvent) {
    e.preventDefault();
    const target = normalizePhone(input);
    if (!target)
      return setInputError("010으로 시작하는 11자리 번호를 입력해 주세요");
    setInputError("");
    setConfirm(false);
    setItems(null);
    load(target);
  }

  async function unsubscribe() {
    if (!phone) return;
    setBusy(true);
    setError("");
    try {
      await api("/api/virtual-phone/unsubscribe", {
        method: "POST",
        body: { phone },
        silent: true,
      });
      toast("수신 거부를 처리했어요. 처리 안내는 잠시 뒤 도착해요");
      setConfirm(false);
      // 가상 발송기가 대기열을 3초마다 보내므로(VirtualMessageSender) 조금 기다렸다 다시 받는다. 야간이면 다음 08:00에 온다
      const count = (items?.length ?? 0) + 1;
      setTimeout(() => load(phone, Math.max(count, SIZE)), NOTICE_DELAY_MS);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ display: "flex", flexWrap: "wrap", minHeight: "1020px" }}>
      <div style={{ flex: "0 0 232px" }}>
        <Sidebar active="campaigns" />
      </div>
      <main
        style={{
          flex: "999 1 640px",
          minWidth: "0",
          display: "flex",
          flexDirection: "column",
        }}
      >
        <Topbar crumb="Campaign / 가상 휴대폰" />
        <div className={styles.wrap}>
          <div className={styles.head}>
            <div>
              <h1 className={styles.title}>가상 휴대폰</h1>
              <p className={styles.sub}>
                고객 번호가 받은 메시지를 확인해요. 실제 문자는 오가지 않아요.
              </p>
            </div>
            <AppLink href="/campaigns" className={styles.secondary}>
              캠페인으로
            </AppLink>
          </div>

          <div className={styles.layout}>
            <section className={styles.card} aria-label="번호 선택">
              <h2 className={styles.cardTitle}>번호 열기</h2>
              <form className={styles.field} onSubmit={open} noValidate>
                <label className={styles.label} htmlFor="vp-phone">
                  고객 번호
                </label>
                <div className={styles.inputRow}>
                  <input
                    id="vp-phone"
                    className={styles.input}
                    inputMode="numeric"
                    autoComplete="off"
                    placeholder="010-0000-0000"
                    value={input}
                    aria-invalid={inputError ? "true" : undefined}
                    aria-describedby={inputError ? "vp-phone-error" : undefined}
                    onChange={(e) => setInput(e.target.value)}
                  />
                  <button
                    type="submit"
                    className={styles.primary}
                    disabled={loading}
                  >
                    열기
                  </button>
                </div>
                {inputError && (
                  <span id="vp-phone-error" className={styles.fieldError}>
                    {inputError}
                  </span>
                )}
              </form>
              <p className={styles.hint}>
                받은 메시지를 최신순으로 보여 줘요.
                <br />
                [수신거부]를 누르면 마케팅 수신이 철회되고, 처리 안내가 이
                번호로 자동 발송돼요. 야간(21시~8시)에는 다음 날 8시에 발송돼요.
              </p>
            </section>

            <section className={styles.phone} aria-label="가상 휴대폰 화면">
              <div className={styles.device}>
                <div className={styles.bezel}>
                  <div className={styles.screen}>
                    <div className={styles.statusBar} aria-hidden="true">
                      {/* 정적 빌드 시각과 연 시각이 달라도 된다 */}
                      <span className="num" suppressHydrationWarning>
                        {clock}
                      </span>
                      <span className={styles.island}></span>
                      <span className={styles.statusIcons}>
                        <svg
                          width="17"
                          height="11"
                          viewBox="0 0 17 11"
                          fill="currentColor"
                        >
                          <rect x="0" y="7" width="3" height="4" rx="1" />
                          <rect x="4.5" y="5" width="3" height="6" rx="1" />
                          <rect x="9" y="2.5" width="3" height="8.5" rx="1" />
                          <rect x="13.5" y="0" width="3" height="11" rx="1" />
                        </svg>
                        <svg
                          width="15"
                          height="11"
                          viewBox="0 0 15 11"
                          fill="currentColor"
                        >
                          <path d="M7.5 2.2c2.1 0 4 .8 5.5 2.1l1.2-1.3A9.6 9.6 0 0 0 7.5.4 9.6 9.6 0 0 0 .8 3l1.2 1.3a7.8 7.8 0 0 1 5.5-2.1Zm0 3.3c1.2 0 2.3.4 3.2 1.2l1.2-1.3a6.4 6.4 0 0 0-8.8 0l1.2 1.3c.9-.8 2-1.2 3.2-1.2Zm0 3.2c-.4 0-.8.2-1.1.4L7.5 10.4l1.1-1.3c-.3-.2-.7-.4-1.1-.4Z" />
                        </svg>
                        <svg
                          width="26"
                          height="12"
                          viewBox="0 0 26 12"
                          fill="none"
                        >
                          <rect
                            x="0.5"
                            y="0.5"
                            width="22"
                            height="11"
                            rx="3.5"
                            stroke="currentColor"
                            opacity="0.4"
                          />
                          <rect
                            x="2"
                            y="2"
                            width="17"
                            height="8"
                            rx="2"
                            fill="currentColor"
                          />
                          <rect
                            x="24"
                            y="4"
                            width="1.5"
                            height="4"
                            rx="0.75"
                            fill="currentColor"
                            opacity="0.4"
                          />
                        </svg>
                      </span>
                    </div>
                    <div className={styles.screenHead}>
                      <strong className="num">
                        {phone ?? "번호를 열어 주세요"}
                      </strong>
                      {phone && <span>받은 메시지 {total}건</span>}
                    </div>

                    <div className={styles.messages}>
                      {error && (
                        <p className={styles.error} role="alert">
                          {error}
                        </p>
                      )}
                      {loading && items === null && (
                        <p className={styles.empty}>불러오는 중…</p>
                      )}
                      {!phone && !loading && !error && (
                        <p className={styles.empty}>
                          왼쪽에서 번호를 입력하면 받은 메시지가 보여요.
                        </p>
                      )}
                      {items?.length === 0 && (
                        <p className={styles.empty}>받은 메시지가 없어요.</p>
                      )}
                      {items?.map((m) => (
                        <article
                          className={styles.bubble}
                          key={m.id}
                          data-kind={m.kind}
                        >
                          <span className={styles.bubbleMeta}>
                            <b>{MESSAGE_KIND_LABEL[m.kind] ?? m.kind}</b> ·{" "}
                            {m.messageType}
                          </span>
                          <p>{m.body}</p>
                          <time className="num" dateTime={m.receivedAt}>
                            {formatDateTime(m.receivedAt)}
                          </time>
                        </article>
                      ))}
                      {items && items.length < total && (
                        <button
                          type="button"
                          className={styles.more}
                          disabled={loading}
                          onClick={() =>
                            phone && load(phone, items.length + SIZE)
                          }
                        >
                          더 보기
                        </button>
                      )}
                    </div>

                    <div className={styles.screenFoot}>
                      {phone &&
                        (confirm ? (
                          <div
                            className={styles.confirm}
                            role="alertdialog"
                            aria-label="수신 거부 확인"
                          >
                            <span>
                              이 번호의 마케팅 수신을 거부할까요?
                              <br />
                              처리 안내가 이 번호로 발송돼요.
                            </span>
                            <span className={styles.confirmActions}>
                              <button
                                type="button"
                                className={styles.danger}
                                disabled={busy}
                                onClick={unsubscribe}
                              >
                                수신거부
                              </button>
                              <button
                                type="button"
                                className={styles.secondary}
                                disabled={busy}
                                onClick={() => setConfirm(false)}
                              >
                                취소
                              </button>
                            </span>
                          </div>
                        ) : (
                          <button
                            type="button"
                            className={styles.unsubscribe}
                            onClick={() => setConfirm(true)}
                          >
                            수신거부
                          </button>
                        ))}
                      <span
                        className={styles.homeBar}
                        aria-hidden="true"
                      ></span>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </div>
      </main>
    </div>
  );
}
