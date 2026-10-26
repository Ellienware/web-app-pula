import { Link } from "react-router-dom"
import { PageHeader } from "../../components/ui/PageHeader"
import { DemoPaymentNotice } from "../../components/ui/DemoPaymentNotice"

const SECTIONS = [
  { to: "/admin/users", title: "Users", body: "See accounts and suspend or reactivate them." },
  { to: "/admin/services", title: "Services", body: "Review service providers and listings." },
  { to: "/admin/marketplace", title: "Marketplace finance", body: "Orders, settlements, refunds and reconciliation." },
  { to: "/admin/wallet", title: "Wallet operations", body: "Demo wallet activity and withdrawals." },
]

/** One landing page for the admin tools that already existed but had no way to reach them. */
export default function AdminHubPage() {
  return (
    <div className="page stack">
      <PageHeader title="Administration" subtitle="Actions here affect other people's accounts. Each one is recorded." />
      <DemoPaymentNotice kind="payout" />
      <ul className="stack" style={{ listStyle: "none", padding: 0 }}>
        {SECTIONS.map((s) => (
          <li key={s.to}>
            <Link to={s.to} className="card order-row">
              <div><strong>{s.title}</strong><div className="muted">{s.body}</div></div>
              <span aria-hidden="true">›</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}
