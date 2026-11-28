import { useState, type CSSProperties } from "react";
import { toast } from "@/components/common/Toast";
import { api, ApiError } from "@/lib/api";
import {
  cancelPaymentConfirm,
  PAYMENT_METHODS,
  parseAmount,
  paymentEditable,
  paymentMethodLabel,
  validatePaymentEdit,
  type PaymentMethod,
  type PaymentRecord,
} from "@/lib/closing";
import { formatDateTime, formatWon } from "@/lib/format";
import { t } from "@/lib/i18n";

// 결제 이력과 결제 수정 · 취소 (FR-PAY-02, F76). 완료 예약의 마감 카드(그 예약의 결제)와 고객 상세 결제 이력이 같이 쓴다.
// 수정 · 취소는 PAYMENT_EDIT(기본 PIN)이고 서버가 검사한다. 잠겨 있으면 api.ts가 PIN 창을 띄운다 (NFR-04).
// 결제 추가(추가 결제)는 마감 카드의 입력 칸이 하고, 여기서는 이미 있는 결제만 고친다.

const errorText = (err: unknown) =>
  err instanceof ApiError ? err.message : t("common.pin.tryLater");

// 고객 상세 · 마감 화면의 CSS가 달라 두 화면에 공통인 lk · num 클래스 말고는 여기서 모양을 정한다
const ROW: CSSProperties = {
  display: "flex",
  gap: 8,
  flexWrap: "wrap",
  alignItems: "center",
};
const META: CSSProperties = { color: "#8A7F7B", fontSize: 13 };
const FIELD: CSSProperties = {
  height: 34,
  padding: "0 10px",
  border: "1px solid #D8CDC5",
  borderRadius: 8,
  background: "#fff",
  font: "400 13.5px 'Noto Sans KR', sans-serif",
};

export function PaymentList(props: {
  payments: PaymentRecord[];
  onChanged: () => void;
  empty?: string;
}) {
  const [editing, setEditing] = useState<{
    paymentId: number;
    amount: string;
    method: PaymentMethod;
  } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function save() {
    if (!editing) return;
    const invalid = validatePaymentEdit(editing.amount);
    if (invalid) return setError(invalid);
    setBusy(true);
    setError("");
    try {
      await api(`/api/payments/${editing.paymentId}`, {
        method: "PATCH",
        body: { amount: parseAmount(editing.amount), method: editing.method },
        silent: true,
      });
      toast(t("payment.edited"));
      setEditing(null);
      props.onChanged();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  async function cancel(p: PaymentRecord) {
    if (!window.confirm(cancelPaymentConfirm())) return;
    setBusy(true);
    setError("");
    try {
      await api(`/api/payments/${p.paymentId}/cancel`, {
        method: "POST",
        silent: true,
      });
      toast(t("payment.canceled"));
      props.onChanged();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  if (props.payments.length === 0)
    return <p style={META}>{props.empty ?? t("payment.empty")}</p>;

  return (
    <div className="col" style={{ gap: 6 }}>
      {error && (
        <p role="alert" style={{ color: "#8A2F3F", fontSize: 13, margin: 0 }}>
          {error}
        </p>
      )}
      {props.payments.map((p) =>
        editing?.paymentId === p.paymentId ? (
          <div key={p.paymentId} style={ROW}>
            <span className="num" style={META}>
              {formatDateTime(p.paidAt)}
            </span>
            <input
              className="num"
              inputMode="numeric"
              aria-label={t("payment.amountLabel")}
              value={editing.amount}
              onChange={(e) =>
                setEditing({ ...editing, amount: e.target.value })
              }
              style={{ ...FIELD, width: 120 }}
            />
            <select
              style={FIELD}
              aria-label={t("payment.methodLabel")}
              value={editing.method}
              onChange={(e) =>
                setEditing({
                  ...editing,
                  method: e.target.value as PaymentMethod,
                })
              }
            >
              {PAYMENT_METHODS.map(([value, label]) => (
                <option key={value} value={value}>
                  {t(label)}
                </option>
              ))}
            </select>
            <button type="button" className="lk" disabled={busy} onClick={save}>
              {busy ? t("common.saving") : t("common.save")}
            </button>
            <button
              type="button"
              className="lk"
              disabled={busy}
              onClick={() => setEditing(null)}
            >
              {t("common.discard")}
            </button>
          </div>
        ) : (
          <div key={p.paymentId} style={ROW}>
            <span className="num" style={META}>
              {formatDateTime(p.paidAt)}
            </span>
            <strong
              className="num"
              style={
                p.canceled
                  ? { textDecoration: "line-through", color: "#8A7F7B" }
                  : undefined
              }
            >
              {formatWon(p.amount)}
            </strong>
            <span style={META}>{paymentMethodLabel(p.method)}</span>
            {p.canceled ? (
              <span
                aria-label={t("payment.canceledLabel")}
                style={{
                  fontSize: 12,
                  padding: "2px 8px",
                  borderRadius: 999,
                  background: "#F1ECE9",
                  color: "#6B5F5B",
                }}
              >
                {t("payment.canceledBadge")}
              </span>
            ) : (
              paymentEditable(p) && (
                <>
                  <button
                    type="button"
                    className="lk"
                    disabled={busy}
                    onClick={() =>
                      setEditing({
                        paymentId: p.paymentId,
                        amount: String(p.amount),
                        method: p.method,
                      })
                    }
                  >
                    {t("payment.edit")}
                  </button>
                  <button
                    type="button"
                    className="lk"
                    disabled={busy}
                    onClick={() => cancel(p)}
                  >
                    {t("payment.cancel")}
                  </button>
                </>
              )
            )}
          </div>
        ),
      )}
    </div>
  );
}
