import { useEffect, useState } from "react";
import { toast } from "@/components/common/Toast";
import { useIndustries } from "@/hooks/useIndustries";
import { api } from "@/lib/api";
import { asksMenuExclusion, canExcludeFromCycle } from "@/lib/closing";
import { pickableMenus } from "@/lib/menus";
import {
  toVisitEditRequest,
  validateVisitRows,
  type VisitRow,
} from "@/lib/visitEdit";
import type { StaffRef } from "../types";
import { errorText } from "./common";
import { t } from "@/lib/i18n";

// 방문의 실제 시술 · 시술별 담당 · 이번 시술 주기 계산 제외 수정 (FR-VIS-03 · 05, F76). PATCH /api/visits/{id}는 CUSTOMER_EDIT(기본 PIN).
// 방문 전체 시술로 시작하고 전체를 보낸다(같은 날 다른 예약의 시술도 함께, C6). 메뉴 선택지는 켜 둔 업종 + 이미 든 메뉴다 (F72).

type Menu = {
  id: number;
  category: string;
  name: string;
  predictionAttribute: string;
};

export function VisitEditor(props: {
  visitId: number;
  initial: VisitRow[];
  onDone: (changed: boolean) => void;
}) {
  const [rows, setRows] = useState<VisitRow[]>(props.initial);
  const [menus, setMenus] = useState<Menu[]>([]);
  const [staff, setStaff] = useState<StaffRef[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const industries = useIndustries();

  useEffect(() => {
    let cancelled = false;
    api<Menu[]>("/api/service-menus", { silent: true })
      .then((list) => !cancelled && setMenus(list))
      .catch((err) => !cancelled && setError(errorText(err)));
    api<StaffRef[]>("/api/staff", { silent: true })
      .then((list) => !cancelled && setStaff(list))
      .catch((err) => !cancelled && setError(errorText(err)));
    return () => {
      cancelled = true;
    };
  }, []);

  const menu = (id: number) => menus.find((m) => m.id === id);
  const chosen = rows.map((r) => r.serviceMenuId);
  const choices = pickableMenus(menus, industries, chosen);
  const addable = choices.filter((m) => !chosen.includes(m.id));
  const update = (i: number, patch: Partial<VisitRow>) =>
    setRows((cur) => cur.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  async function toggleExcluded(i: number, value: boolean) {
    update(i, { cycleExcluded: value });
    const m = menu(rows[i].serviceMenuId);
    // 「앞으로 이 메뉴를 모두 주기 계산에서 제외할까요?」 (FR-VIS-05). [예]면 메뉴 예측 속성을 바꾼다 (OWNER_PIN)
    if (!value || !m || !asksMenuExclusion(m.predictionAttribute)) return;
    if (
      !window.confirm(t("visit.edit.excludeConfirm", { name: m.name }))
    )
      return;
    try {
      await api(`/api/service-menus/${m.id}`, {
        method: "PATCH",
        body: { predictionAttribute: "EXCLUDE" },
        silent: true,
      });
      setMenus((cur) =>
        cur.map((x) =>
          x.id === m.id ? { ...x, predictionAttribute: "EXCLUDE" } : x,
        ),
      );
      toast(t("visit.edit.excluded", { name: m.name }));
    } catch (err) {
      setError(errorText(err));
    }
  }

  async function save() {
    const invalid = validateVisitRows(rows);
    if (invalid) return setError(invalid);
    setBusy(true);
    setError("");
    try {
      await api(`/api/visits/${props.visitId}`, {
        method: "PATCH",
        body: toVisitEditRequest(rows),
        silent: true,
      });
      toast(t("visit.edit.saved"));
      props.onDone(true);
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  }

  return (
    <span
      className="col"
      style={{
        gap: 8,
        padding: 12,
        border: "1px solid #E6DCD6",
        borderRadius: 12,
      }}
    >
      {rows.map((r, i) => {
        const m = menu(r.serviceMenuId);
        return (
          <span
            key={i}
            className="row"
            style={{ gap: 8, flexWrap: "wrap", alignItems: "center" }}
          >
            <select
              className="sel"
              aria-label={t("visit.edit.menu")}
              value={r.serviceMenuId}
              onChange={(e) =>
                update(i, {
                  serviceMenuId: Number(e.target.value),
                  cycleExcluded: false,
                })
              }
            >
              {choices
                .filter(
                  (c) => c.id === r.serviceMenuId || !chosen.includes(c.id),
                )
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </select>
            <select
              className="sel"
              aria-label={t("common.staff")}
              value={r.staffId}
              onChange={(e) => update(i, { staffId: Number(e.target.value) })}
            >
              {!staff.some((s) => s.id === r.staffId) && (
                <option value={r.staffId}>{t("visit.edit.currentStaff")}</option>
              )}
              {staff.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
            <label
              className="meta"
              style={{ display: "inline-flex", gap: 4, alignItems: "center" }}
            >
              <input
                type="checkbox"
                checked={r.cycleExcluded}
                disabled={!m || !canExcludeFromCycle(m.predictionAttribute)}
                onChange={(e) => toggleExcluded(i, e.target.checked)}
              />
              {t("visit.edit.excludeThis")}
            </label>
            <button
              type="button"
              className="lk"
              aria-label={t("visit.edit.removeLabel")}
              onClick={() => setRows((cur) => cur.filter((_, j) => j !== i))}
            >
              {t("visit.edit.remove")}
            </button>
          </span>
        );
      })}
      {addable.length > 0 && (
        <select
          className="sel"
          aria-label={t("visit.edit.addLabel")}
          value=""
          onChange={(e) => {
            const id = Number(e.target.value);
            setRows((cur) => [
              ...cur,
              {
                serviceMenuId: id,
                staffId: cur[0]?.staffId ?? staff[0]?.id ?? 0,
                cycleExcluded: false,
              },
            ]);
          }}
          style={{ alignSelf: "flex-start" }}
        >
          <option value="">{t("visit.edit.addOption")}</option>
          {addable.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
        </select>
      )}
      {error && (
        <p className="note" role="alert" style={{ color: "#8A2F3F" }}>
          {error}
        </p>
      )}
      <span className="row" style={{ gap: 8 }}>
        <button
          type="button"
          className="btn btn-p"
          disabled={busy || menus.length === 0}
          onClick={save}
        >
          {busy ? t("common.saving") : t("visit.edit.save")}
        </button>
        <button
          type="button"
          className="btn"
          disabled={busy}
          onClick={() => props.onDone(false)}
        >
          {t("common.discard")}
        </button>
      </span>
    </span>
  );
}
