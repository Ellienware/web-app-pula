import { useCallback, useEffect, useState } from "react"
import { useParams } from "react-router-dom"
import { api } from "../../lib/api"
import type { PublicBusiness, Order, Page, Product } from "../../lib/types"
import { FULFILMENT_OPTION_LABELS } from "../../lib/types"
import { ProductCard } from "../../components/market/ProductCard"
import { PublicBranchDirectory } from "../../components/branch/PublicBranchDirectory"
import { StarRating } from "../../components/market/StarRating"
import { ReviewSection } from "../../components/market/ReviewSection"
import { ErrorState } from "../../components/ui/StatePanel"
import { SkeletonCard, SkeletonGrid } from "../../components/ui/Skeleton"

export function BusinessProfilePage() {
  const { id } = useParams<{ id: string }>()
  const [business, setBusiness] = useState<PublicBusiness | null>(null)
  const [products, setProducts] = useState<Product[] | null>(null)
  const [eligibleOrderId, setEligibleOrderId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!id) return
    api.get<PublicBusiness>(`/businesses/${id}`).then(setBusiness).catch((e) => setError(e.message))
    api
      .get<Page<Product>>(`/businesses/${id}/products?size=50`)
      .then((page) => setProducts(page.content))
      .catch(() => setProducts([]))
    api
      .get<Page<Order>>("/orders/mine?size=50")
      .then((page) => {
        const completed = page.content.find((o) => o.businessId === id && o.status === "COMPLETED")
        setEligibleOrderId(completed?.id ?? null)
      })
      .catch(() => setEligibleOrderId(null))
  }, [id])

  useEffect(load, [load])

  if (error) {
    return (
      <div className="page">
        <ErrorState title="We couldn't load this seller" description={error} onRetry={load} />
      </div>
    )
  }
  if (!business || !id) {
    return (
      <div className="page stack">
        <SkeletonCard lines={3} />
      </div>
    )
  }

  const deliveryOptions = business.fulfilmentOptions.filter((o) =>
    ["SELLER_DELIVERY", "MOMORIDE_DELIVERY", "CUSTOMER_PICKUP"].includes(o),
  )

  return (
    <div className="page stack">
      <div className="card business-header">
        <div className="business-header-logo" aria-hidden="true">
          {business.logoUrl ? <img src={business.logoUrl} alt="" /> : <span>{business.name[0]?.toUpperCase()}</span>}
        </div>
        <div style={{ flex: 1 }}>
          <h1>{business.name}</h1>
          <div className="row wrap" style={{ gap: 6, marginBottom: 6 }}>
            <span className="badge">{business.category}</span>
            {business.location && (
              <span className="muted" style={{ fontSize: "0.85rem" }}>📍 {business.location}</span>
            )}
            {business.ratingCount > 0 && (
              <StarRating value={business.ratingAverage} count={business.ratingCount} />
            )}
          </div>
          {business.description && <p style={{ marginBottom: 8 }}>{business.description}</p>}

          {/* Contact */}
          {(business.contactPhone || business.contactEmail) && (
            <p className="muted" style={{ fontSize: "0.85rem", marginBottom: 8 }}>
              {business.contactPhone && (
                <a href={`tel:${business.contactPhone}`} style={{ color: "inherit" }}>
                  📞 {business.contactPhone}
                </a>
              )}
              {business.contactPhone && business.contactEmail ? " · " : ""}
              {business.contactEmail && (
                <a href={`mailto:${business.contactEmail}`} style={{ color: "inherit" }}>
                  ✉️ {business.contactEmail}
                </a>
              )}
            </p>
          )}

          {/* Fulfilment/delivery options */}
          {deliveryOptions.length > 0 && (
            <div className="row wrap" style={{ gap: 6 }}>
              {deliveryOptions.map((opt) => (
                <span key={opt} className="badge badge-success" style={{ fontSize: "0.78rem" }}>
                  {FULFILMENT_OPTION_LABELS[opt]}
                </span>
              ))}
              {business.minimumOrderAmount != null && business.minimumOrderAmount > 0 && (
                <span className="badge badge-muted" style={{ fontSize: "0.78rem" }}>
                  Min R{business.minimumOrderAmount}
                </span>
              )}
              {business.deliveryFee != null && business.deliveryFee > 0 && (
                <span className="badge badge-muted" style={{ fontSize: "0.78rem" }}>
                  Delivery R{business.deliveryFee}
                </span>
              )}
            </div>
          )}
        </div>
      </div>

      <section>
        <h2>Listings</h2>
        {products === null ? (
          <SkeletonGrid count={3} columns="product-grid" />
        ) : products.length === 0 ? (
          <p className="muted">This seller hasn't published anything yet.</p>
        ) : (
          <div className="product-grid">
            {products.map((p) => (
              <ProductCard key={p.id} product={p} />
            ))}
          </div>
        )}
      </section>

      <ReviewSection targetType="BUSINESS" targetId={id} eligibleOrderId={eligibleOrderId} />
      <PublicBranchDirectory businessId={id} />
    </div>
  )
}
