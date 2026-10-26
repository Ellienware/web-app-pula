import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../../lib/api"
import type { Business } from "../../../lib/types"
import { isFoodBusinessType } from "../../../lib/types"
import { PageHeader } from "../../../components/ui/PageHeader"
import { EmptyState, ErrorState } from "../../../components/ui/StatePanel"
import { SkeletonCard } from "../../../components/ui/Skeleton"
import { KitchenOrders } from "../../../components/food/merchant/KitchenOrders"
import { MenuManager } from "../../../components/food/merchant/MenuManager"
import { ModifierManager } from "../../../components/food/merchant/ModifierManager"
import { BranchManager } from "../../../components/food/merchant/BranchManager"
import { SettingsPanel } from "../../../components/food/merchant/SettingsPanel"

type Tab = "orders" | "menu" | "extras" | "branches" | "settings"
const TABS: { id: Tab; label: string }[] = [
  { id: "orders", label: "Kitchen" }, { id: "menu", label: "Menu" }, { id: "extras", label: "Extras" },
  { id: "branches", label: "Branches" }, { id: "settings", label: "Settings" },
]

/** Restaurant tools. Reached from the seller dashboard; only food businesses can open it. */
export function FoodMerchantPage() {
  const [business, setBusiness] = useState<Business | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>("orders")

  useEffect(() => {
    api.get<Business>("/businesses/me").then(setBusiness)
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load your business"))
  }, [])

  if (error) return <div className="page"><ErrorState title="Couldn't load your business" description={error} /></div>
  if (!business) return <div className="page"><SkeletonCard lines={3} /></div>
  if (!isFoodBusinessType(business.businessType)) {
    return (
      <div className="page">
        <EmptyState title="This is for food businesses"
          description="Change your business type to Restaurant, Home Kitchen, Café or another food type in your seller settings to use menus."
          action={<Link to="/sell" className="btn btn-primary">Go to seller settings</Link>} />
      </div>
    )
  }
  return (
    <div className="page stack">
      <PageHeader eyebrow="PULA Business · Food" title={business.name} subtitle="Menu, orders and delivery for your kitchen."
        actions={<><Link to="/cashflow" className="btn btn-secondary">Cash Flow</Link> <Link to="/sell" className="btn btn-ghost">All seller tools</Link></>} />
      <div className="food-tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" className={`food-tab${tab === t.id ? " is-active" : ""}`} onClick={() => setTab(t.id)}>{t.label}</button>
        ))}
      </div>
      {tab === "orders" && <KitchenOrders />}
      {tab === "menu" && <MenuManager business={business} />}
      {tab === "extras" && <ModifierManager />}
      {tab === "branches" && <BranchManager />}
      {tab === "settings" && <SettingsPanel />}
    </div>
  )
}
