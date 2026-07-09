import { governance } from "../api";
import { useAsync } from "../ui";

/**
 * Topbar chip showing the effective RBAC governance posture (G7). Fail-open (default) vs
 * fail-closed on a directory outage — queried from a downstream service (decision).
 */
export default function PostureChip() {
  const { data } = useAsync(() => governance.posture("decision").catch(() => null), []);
  if (!data) return null;
  return (
    <span className={`gov-badge ${data.failClosed ? "human" : "det"}`}
      title="RBAC directory-outage posture (G7) — fail-open allows, fail-closed denies">
      <span className="gdot" />{data.failClosed ? "RBAC FAIL-CLOSED" : "RBAC FAIL-OPEN"}
    </span>
  );
}
