import { useCallback, useEffect, useRef, useState } from "react";
import { useApp } from "./app-context";
import { fmt, notifications } from "./api";

/**
 * Notification center — the live counterpart to the full-page outbox viewer.
 *
 * A bell in the topbar shows an unread-count badge (polled on a modest 30s cadence and
 * refreshed whenever the dropdown opens). The dropdown lists recent unread notifications;
 * clicking one marks it read (the human-gated POST /{id}/read) AND navigates to its subject
 * screen via the shared nav(view, ref); "Mark all read" clears the badge. Read-state is
 * additive — a read notification is not deleted, it still appears in the outbox list.
 *
 * Notifications live per-service (each service owns its own outbox table), so the bell
 * aggregates the primary human-facing sources rather than fanning out to all nine.
 */
const BELL_SERVICES = ["decision", "portfolio", "counterparty"];

type Item = {
  svc: string;
  id: number;
  eventType: string;
  subjectType?: string;
  subjectRef?: string;
  renderedSubject?: string;
  createdAt?: string;
  readAt?: string | null;
};

/** eventType → screen (most specific). Falls back to subjectType, then the Notifications page. */
const EVENT_VIEW: Record<string, string> = {
  COVENANT_BREACH: "monitoring",
  COVENANT_DUE: "monitoring",
  MER_DUE: "monitoring",
  MER_OVERDUE: "monitoring",
  MER_ESCALATED: "monitoring",
  CP_NUDGE: "disbursement",
  COMMITTEE_QUORUM_PENDING: "committee",
  EWS_BREACH: "customer360",
  CRILC_REPORT_DUE: "exports",
  REKYC_DUE: "counterparties",
};
const SUBJECT_VIEW: Record<string, string> = {
  Counterparty: "counterparties",
};

function routeFor(n: Item): { view: string; ref?: string } {
  const view = EVENT_VIEW[n.eventType] || SUBJECT_VIEW[n.subjectType || ""] || "notifications";
  // Deal-scoped screens seed their own selector from ref; the Notifications page ignores it.
  return { view, ref: n.subjectRef || undefined };
}

/**
 * Mirrors the backend's addressedTo(): a notification is the actor's own when the actor appears
 * in the row's recipients OR recipientRoles list. Used to scope the dropdown consistently with
 * the recipient-scoped unread-count / read-all endpoints (the list endpoint carries no filter).
 */
function addressedToActor(n: any, actor: string): boolean {
  if (!actor) return false;
  const recipients: string[] = Array.isArray(n?.recipients) ? n.recipients : [];
  const roles: string[] = Array.isArray(n?.recipientRoles) ? n.recipientRoles : [];
  return recipients.includes(actor) || roles.includes(actor);
}

export default function NotificationBell() {
  const { actor, nav } = useApp();
  const [open, setOpen] = useState(false);
  const [count, setCount] = useState(0);
  const [items, setItems] = useState<Item[]>([]);
  const [loading, setLoading] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  // Cheap, frequent: just the unread totals across the primary services — scoped to the
  // signed-in actor so the badge is a PERSONAL inbox, not the whole per-service outbox.
  const refreshCount = useCallback(async () => {
    const results = await Promise.allSettled(
      BELL_SERVICES.map((s) => notifications.unreadCount(s, { recipient: actor })));
    let total = 0;
    for (const r of results) if (r.status === "fulfilled") total += r.value?.unread || 0;
    setCount(total);
  }, [actor]);

  // Heavier, on-demand: the recent unread rows to show in the dropdown. The list endpoint has
  // no recipient filter, so we apply the SAME predicate the backend uses to scope the count /
  // read-all (actor is one of the row's recipients or recipientRoles) — keeping the badge, the
  // dropdown, and "mark all read" consistently scoped to the actor's own notifications.
  const loadItems = useCallback(async () => {
    setLoading(true);
    try {
      const per = await Promise.allSettled(
        BELL_SERVICES.map(async (s) => {
          const rows = await notifications.list(s);
          return (rows || [])
            .filter((n: any) => !n.readAt && addressedToActor(n, actor))
            .map((n: any) => ({ ...n, svc: s } as Item));
        }),
      );
      const all: Item[] = [];
      for (const r of per) if (r.status === "fulfilled") all.push(...r.value);
      all.sort((a, b) => String(b.createdAt ?? "").localeCompare(String(a.createdAt ?? "")));
      setItems(all.slice(0, 12));
    } finally {
      setLoading(false);
    }
  }, [actor]);

  // Poll the badge on a modest interval; the tab being backgrounded still refreshes on open.
  useEffect(() => {
    refreshCount();
    const t = window.setInterval(refreshCount, 30000);
    return () => window.clearInterval(t);
  }, [refreshCount]);

  // Close on outside-click / Escape.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const toggle = useCallback(() => {
    setOpen((o) => {
      const next = !o;
      if (next) { loadItems(); refreshCount(); }
      return next;
    });
  }, [loadItems, refreshCount]);

  const onOpenItem = useCallback(async (n: Item) => {
    const { view, ref } = routeFor(n);
    try { await notifications.markRead(n.svc, n.id, actor); } catch { /* best-effort */ }
    setOpen(false);
    nav(view, ref);
    // Reflect the read locally, then reconcile with the server.
    setItems((prev) => prev.filter((x) => !(x.svc === n.svc && x.id === n.id)));
    refreshCount();
  }, [actor, nav, refreshCount]);

  const onMarkAll = useCallback(async () => {
    // Clear only the actor's own notifications (recipient=actor); the backend's read-all is
    // itself recipient-owned, so this never clears anyone else's inbox.
    await Promise.allSettled(BELL_SERVICES.map((s) => notifications.markAllRead(s, actor, actor)));
    setItems([]);
    setCount(0);
    refreshCount();
  }, [actor, refreshCount]);

  const badge = count > 99 ? "99+" : String(count);

  return (
    <div className="notif-bell" ref={wrapRef}>
      <button
        className={`notif-bell-btn${count > 0 ? " has-unread" : ""}`}
        onClick={toggle}
        aria-label={count > 0 ? `Notifications (${count} unread)` : "Notifications"}
        aria-expanded={open}
        title="Notifications"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
             strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        {count > 0 && <span className="notif-badge">{badge}</span>}
      </button>

      {open && (
        <div className="notif-panel" role="menu">
          <div className="notif-panel-head">
            <span className="notif-panel-title">Notifications{count > 0 ? ` · ${count} unread` : ""}</span>
            <button className="notif-markall" onClick={onMarkAll} disabled={count === 0}>
              Mark all read
            </button>
          </div>
          <div className="notif-list">
            {loading ? (
              <div className="notif-empty">Loading…</div>
            ) : items.length === 0 ? (
              <div className="notif-empty">You&apos;re all caught up.</div>
            ) : (
              items.map((n) => (
                <button key={`${n.svc}:${n.id}`} className="notif-item" onClick={() => onOpenItem(n)}
                        title="Open subject and mark read">
                  <span className="notif-dot" />
                  <span className="notif-item-body">
                    <span className="notif-item-title">{n.renderedSubject || n.eventType.replace(/_/g, " ")}</span>
                    <span className="notif-item-meta">
                      <span className="notif-item-subject mono">
                        {[n.subjectType, n.subjectRef].filter(Boolean).join(" ") || n.svc}
                      </span>
                      <span className="notif-item-time">{fmt.dateTime(n.createdAt)}</span>
                    </span>
                  </span>
                </button>
              ))
            )}
          </div>
          <div className="notif-panel-foot">
            <button className="notif-viewall" onClick={() => { setOpen(false); nav("notifications"); }}>
              View all notifications →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
