import { Link } from "react-router-dom"
import { PageHeader } from "../../components/ui/PageHeader"

const destinations = [
  {
    title: "Wholesale catalogue",
    description: "Create and manage the products your business supplies.",
    path: "/market/stock/catalogue",
    action: "Manage catalogue",
  },
  {
    title: "Wholesale orders",
    description: "Review orders and track your stock-market activity.",
    path: "/market/stock/orders",
    action: "View orders",
  },
  {
    title: "Bulk purchasing",
    description: "Explore or manage group purchasing opportunities.",
    path: "/market/stock/bulk",
    action: "Explore bulk buying",
  },
  {
    title: "Wholesale marketplace",
    description: "Browse wholesale listings and available supplies.",
    path: "/market/stock",
    action: "Browse marketplace",
  },
]

export default function WholesalerDashboardPage() {
  return (
    <div className="page stack">
      <PageHeader
        title="Wholesaler dashboard"
        subtitle="Manage your supply catalogue, orders and bulk purchasing."
      />

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {destinations.map((item) => (
          <section className="card stack" key={item.path}>
            <h2>{item.title}</h2>
            <p>{item.description}</p>
            <Link className="button" to={item.path}>
              {item.action}
            </Link>
          </section>
        ))}
      </div>
    </div>
  )
}
