import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { Package, Plus, Store } from "lucide-react"
import { api, ApiError } from "../../lib/api"
import type { Business, Order, Page, Product, SellerDashboard } from "../../lib/types"
import {
  isFoodBusinessType,
  FULFILMENT_OPTION_LABELS,
  BUSINESS_REGISTRATION_STATUS_LABELS,
} from "../../lib/types"
import type { FulfilmentOption, BusinessRegistrationStatus, UpdateBusinessRequest } from "../../lib/types"
import { formatDateTime, formatMoney } from "../../lib/format"
import { useCurrencyConfig } from "../../lib/currencyConfig"
import { CreateBusinessModal } from "../../components/market/CreateBusinessModal"
import { ProductFormModal } from "../../components/market/ProductFormModal"
import { OrderStatusBadge } from "../../components/market/OrderStatusBadge"
import BusinessReadinessCard from "../../components/market/BusinessReadinessCard"
import BusinessDocumentsPanel from "../../components/market/BusinessDocumentsPanel"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { StatCard } from "../../components/ui/StatCard"
import { MoneyDisplay } from "../../components/ui/MoneyDisplay"
import { EmptyState } from "../../components/ui/StatePanel"
import { SkeletonCard, SkeletonGrid } from "../../components/ui/Skeleton"

type Tab = "overview" | "products" | "orders" | "settings"

export function SellerDashboardPage() {
  const { defaultCurrency } = useCurrencyConfig()
  const [business, setBusiness] = useState<Business | null>(null)
  const [hasBusiness, setHasBusiness] = useState<boolean | null>(null)
  const [showOnboarding, setShowOnboarding] = useState(false)
  const [tab, setTab] = useState<Tab>("overview")

  const [stats, setStats] = useState<SellerDashboard | null>(null)
  const [products, setProducts] = useState<Product[] | null>(null)
  const [orders, setOrders] = useState<Order[] | null>(null)
  const [editingProduct, setEditingProduct] = useState<Product | undefined>(undefined)
  const [showNewProduct, setShowNewProduct] = useState(false)
  const [archivingId, setArchivingId] = useState<string | null>(null)

  const loadBusiness = useCallback(() => {
    api
      .get<Business>("/businesses/me")
      .then((b) => {
        setBusiness(b)
        setHasBusiness(true)
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) {
          setHasBusiness(false)
        }
      })
  }, [])

  useEffect(loadBusiness, [loadBusiness])

  const loadStats = useCallback(() => {
    api.get<SellerDashboard>("/seller/dashboard").then(setStats).catch(() => setStats(null))
  }, [])

  const loadProducts = useCallback(() => {
    if (!business) return
    api
      .get<Page<Product>>(`/businesses/${business.id}/products?size=100`)
      .then((page) => setProducts(page.content))
      .catch(() => setProducts([]))
  }, [business])

  const loadOrders = useCallback(() => {
    api
      .get<Page<Order>>("/orders/selling?size=50")
      .then((page) => setOrders(page.content))
      .catch(() => setOrders([]))
  }, [])

  async function archiveProduct(product: Product) {
    if (!window.confirm(`Archive "${product.name}"? It will no longer be visible to buyers.`)) {
      return
    }
    setArchivingId(product.id)
    try {
      await api.del(`/products/${product.id}`)
      loadProducts()
    } catch {
      // loadProducts() re-fetching the true state below is enough recovery — no separate error UI needed
      // for what's a low-stakes, retryable seller action.
      loadProducts()
    } finally {
      setArchivingId(null)
    }
  }

  useEffect(() => {
    if (!business) return
    loadStats()
    loadProducts()
    loadOrders()
  }, [business, loadStats, loadProducts, loadOrders])

  if (hasBusiness === null) {
    return (
      <div className="page">
        <SkeletonCard lines={2} />
      </div>
    )
  }

  if (!hasBusiness) {
    return (
      <div className="page">
        <EmptyState
          icon={<Store size={22} aria-hidden="true" />}
          title="Start selling on PULA Market"
          description="Set up a seller profile to list products or services and start receiving orders."
          action={<Button onClick={() => setShowOnboarding(true)}>Set up seller profile</Button>}
        />
        {showOnboarding && (
          <CreateBusinessModal
            onClose={() => setShowOnboarding(false)}
            onCreated={() => {
              setShowOnboarding(false)
              loadBusiness()
            }}
          />
        )}
      </div>
    )
  }

  return (
    <div className="page stack">
      <PageHeader
        title={business!.name}
        subtitle="Your PULA Market seller dashboard."
        actions={
          <>
            {isFoodBusinessType(business!.businessType) && (
              <Link to="/sell/food" className="btn btn-primary btn-sm">Kitchen &amp; menu</Link>
            )}{" "}
            <Link to="/sell/branches" className="btn btn-ghost btn-sm">Branches</Link>{" "}
            <Link to="/sell/team" className="btn btn-ghost btn-sm">Team</Link>{" "}
            <Link to="/sell/settings" className="btn btn-ghost btn-sm">Settings</Link>{" "}
            <Link to={isFoodBusinessType(business!.businessType) ? `/market/restaurants/${business!.id}` : `/market/businesses/${business!.id}`} className="btn btn-ghost btn-sm">
              View storefront
            </Link>
          </>
        }
      />

      <div className="tab-bar" role="tablist">
        {(["overview", "products", "orders", "settings"] as Tab[]).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            className={`tab ${tab === t ? "active" : ""}`}
            onClick={() => setTab(t)}
          >
            {t[0].toUpperCase() + t.slice(1)}
          </button>
        ))}
      </div>

      {tab === "overview" && (
        <div className="stack">
          {stats ? (
            <div className="grid grid-3">
              <StatCard label="Total sales" value={<MoneyDisplay amount={stats.totalSales} currency={stats.currency ?? defaultCurrency} size="md" />} />
              <StatCard label="This month" value={<MoneyDisplay amount={stats.monthSales} currency={stats.currency ?? defaultCurrency} size="md" />} />
              <StatCard label="This week" value={<MoneyDisplay amount={stats.weekSales} currency={stats.currency ?? defaultCurrency} size="md" />} />
              <StatCard label="Total orders" value={<span className="money-display money-display-md">{stats.totalOrders}</span>} />
              <StatCard label="Pending orders" value={<span className="money-display money-display-md">{stats.pendingOrders}</span>} />
              <StatCard label="Completed orders" value={<span className="money-display money-display-md">{stats.completedOrders}</span>} />
              <StatCard label="Unique customers" value={<span className="money-display money-display-md">{stats.uniqueCustomers}</span>} />
            </div>
          ) : (
            <SkeletonGrid count={3} />
          )}

          {stats && stats.deliveryStats.requested > 0 && (
            <div className="card">
              <h2>PULA Ride deliveries</h2>
              <div className="row wrap" style={{ gap: "1.5rem" }}>
                <span>Requested: <strong>{stats.deliveryStats.requested}</strong></span>
                <span>Delivered: <strong>{stats.deliveryStats.delivered}</strong></span>
                <span>Cancelled: <strong>{stats.deliveryStats.cancelled}</strong></span>
              </div>
            </div>
          )}

          {stats && stats.dailyRevenue.some((d) => d.amount > 0) && (
            <div className="card">
              <h2>Last 14 days</h2>
              <div className="row wrap" style={{ gap: 4, alignItems: "flex-end", height: 80 }}>
                {stats.dailyRevenue.map((d) => {
                  const max = Math.max(...stats.dailyRevenue.map((x) => x.amount), 1)
                  return (
                    <div
                      key={d.date}
                      title={`${d.date}: ${formatMoney(d.amount, stats.currency ?? defaultCurrency)}`}
                      style={{
                        width: 12,
                        height: Math.max(2, (d.amount / max) * 70),
                        background: "var(--gold, #e0b84a)",
                        borderRadius: 2,
                      }}
                    />
                  )
                })}
              </div>
            </div>
          )}

          {stats && stats.topProducts.length > 0 && (
            <div className="card">
              <h2>Top products</h2>
              <ul className="stack">
                {stats.topProducts.map((p) => (
                  <li key={p.id} className="row between">
                    <span>{p.name}</span>
                    <span className="money">{formatMoney(p.price, p.currency)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {tab === "products" && (
        <div className="stack">
          <div className="row between">
            <h2>Your listings</h2>
            <Button size="sm" onClick={() => setShowNewProduct(true)}>
              <Plus size={14} aria-hidden="true" /> New listing
            </Button>
          </div>
          {products === null ? (
            <SkeletonGrid count={3} />
          ) : products.length === 0 ? (
            <EmptyState
              icon={<Package size={22} aria-hidden="true" />}
              title="You haven't listed anything yet"
              description="Add your first product or service to start selling."
              action={<Button onClick={() => setShowNewProduct(true)}>+ New listing</Button>}
            />
          ) : (
            <ul className="stack">
              {products.map((p) => (
                <li key={p.id} className="card row between">
                  <div>
                    <strong>{p.name}</strong>
                    <div className="muted">
                      {formatMoney(p.price, p.currency)} · {p.isService ? "Service" : `${p.stock} in stock`}
                    </div>
                  </div>
                  <div className="row">
                    <span className={`badge ${p.status === "ACTIVE" ? "success" : p.status === "OUT_OF_STOCK" ? "failed" : ""}`}>
                      {p.status}
                    </span>
                    <button className="btn btn-ghost btn-sm" onClick={() => setEditingProduct(p)}>
                      Edit
                    </button>
                    {p.status !== "ARCHIVED" && (
                      <button
                        className="btn btn-ghost btn-sm danger"
                        onClick={() => archiveProduct(p)}
                        disabled={archivingId === p.id}
                      >
                        {archivingId === p.id ? "Archiving…" : "Archive"}
                      </button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {tab === "orders" && (
        <div className="stack">
          <h2>Incoming orders</h2>
          {orders === null ? (
            <div className="stack">
              <SkeletonCard lines={2} />
              <SkeletonCard lines={2} />
            </div>
          ) : orders.length === 0 ? (
            <EmptyState title="No orders yet" description="Orders from buyers will show up here." />
          ) : (
            <ul className="order-list">
              {orders.map((o) => (
                <li key={o.id}>
                  <Link to={`/orders/${o.id}`} className="order-row card">
                    <div>
                      <strong>{o.buyerName}</strong>
                      <div className="muted">
                        {o.items.length} item{o.items.length === 1 ? "" : "s"} · {formatDateTime(o.createdAt)}
                      </div>
                    </div>
                    <div className="row">
                      <span className="money">{formatMoney(o.total, o.currency)}</span>
                      <OrderStatusBadge status={o.status} />
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {tab === "settings" && business && (
        <div className="stack" style={{ gap: "1.5rem" }}>
          <BusinessSettingsSection business={business} onSaved={setBusiness} />
          <BusinessReadinessCard />
          <BusinessDocumentsPanel />
        </div>
      )}

      {showNewProduct && (
        <ProductFormModal
          publishOnCreate
          onClose={() => setShowNewProduct(false)}
          onSaved={() => {
            setShowNewProduct(false)
            loadProducts()
          }}
        />
      )}
      {editingProduct && (
        <ProductFormModal
          product={editingProduct}
          onClose={() => setEditingProduct(undefined)}
          onSaved={() => {
            setEditingProduct(undefined)
            loadProducts()
          }}
        />
      )}
    </div>
  )
}

const FULFILMENT_CHOICES: FulfilmentOption[] = [
  "CUSTOMER_PICKUP",
  "SELLER_DELIVERY",
  "MOMORIDE_DELIVERY",
  "SUPPORTED_PARTNER_DELIVERY",
]

function BusinessSettingsSection({ business, onSaved }: { business: Business; onSaved: (b: Business) => void }) {
  const [fulfilmentOptions, setFulfilmentOptions] = useState<FulfilmentOption[]>(business.fulfilmentOptions)
  const [registrationStatus, setRegistrationStatus] = useState<BusinessRegistrationStatus>(business.registrationStatus)
  const [registrationNumber, setRegistrationNumber] = useState(business.registrationNumber ?? "")
  const [minimumOrderAmount, setMinimumOrderAmount] = useState(
    business.minimumOrderAmount != null ? String(business.minimumOrderAmount) : "",
  )
  const [deliveryFee, setDeliveryFee] = useState(business.deliveryFee != null ? String(business.deliveryFee) : "")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  function toggleFulfilment(option: FulfilmentOption) {
    setFulfilmentOptions((current) =>
      current.includes(option) ? current.filter((o) => o !== option) : [...current, option],
    )
  }

  async function save() {
    setBusy(true)
    setError(null)
    setSaved(false)
    try {
      const req: UpdateBusinessRequest = {
        fulfilmentOptions,
        registrationStatus,
        registrationNumber: registrationNumber.trim(),
        minimumOrderAmount: minimumOrderAmount ? Number(minimumOrderAmount) : null,
        deliveryFee: deliveryFee ? Number(deliveryFee) : null,
      }
      const updated = await api.put<Business>("/businesses/me", req)
      onSaved(updated)
      setSaved(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save settings")
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card stack" style={{ maxWidth: 480 }}>
      <h2>Business settings</h2>


      <fieldset className="field">
        <legend>How can customers get their order?</legend>
        {FULFILMENT_CHOICES.map((option) => (
          <label key={option} style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input
              type="checkbox"
              checked={fulfilmentOptions.includes(option)}
              onChange={() => toggleFulfilment(option)}
            />
            {FULFILMENT_OPTION_LABELS[option]}
          </label>
        ))}
      </fieldset>

      <label className="field">
        <span>Registration status</span>
        <select
          value={registrationStatus}
          onChange={(e) => setRegistrationStatus(e.target.value as BusinessRegistrationStatus)}
        >
          {(Object.keys(BUSINESS_REGISTRATION_STATUS_LABELS) as BusinessRegistrationStatus[]).map((s) => (
            <option key={s} value={s}>
              {BUSINESS_REGISTRATION_STATUS_LABELS[s]}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Registration number (optional)</span>
        <input
          placeholder="e.g. a CIPC enterprise number, if you have one"
          value={registrationNumber}
          onChange={(e) => setRegistrationNumber(e.target.value)}
        />
      </label>
      <p className="muted" style={{ fontSize: "0.8rem" }}>
        This is self-reported and helps PULA understand your business — PULA doesn't verify it against CIPC or file
        any registration on your behalf.
      </p>

      <div className="field-row">
        <label className="field">
          <span>Minimum order amount</span>
          <input
            type="number"
            min="0"
            step="0.01"
            placeholder="No minimum"
            value={minimumOrderAmount}
            onChange={(e) => setMinimumOrderAmount(e.target.value)}
          />
        </label>
        <label className="field">
          <span>Delivery fee</span>
          <input
            type="number"
            min="0"
            step="0.01"
            placeholder="Not set"
            value={deliveryFee}
            onChange={(e) => setDeliveryFee(e.target.value)}
          />
        </label>
      </div>
      <p className="muted" style={{ fontSize: "0.8rem" }}>
        These are used in basket comparisons so buyers can see your real minimum order and delivery cost — leave
        blank if not applicable.
      </p>

      {error && <p className="form-error">{error}</p>}
      {saved && <p className="muted">Saved.</p>}
      <Button onClick={save} disabled={busy}>
        {busy ? "Saving…" : "Save settings"}
      </Button>
    </div>
  )
}
