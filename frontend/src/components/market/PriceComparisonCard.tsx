import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../lib/api"
import type { PriceComparison } from "../../lib/types"
import { MoneyDisplay } from "../ui/MoneyDisplay"
import { SkeletonCard } from "../ui/Skeleton"

/**
 * Shows every seller's EXACT_MATCH price for the same canonical product,
 * cheapest first — the master brief's "Full Cream Milk 2L: Shoprite R32.99,
 * Local Shop R31.00…" comparison table. Only rendered when the product is
 * actually linked to a canonical product (see ProductDetailPage) — an
 * unlinked product has nothing to compare against.
 */
export function PriceComparisonCard({ canonicalProductId, currentProductId }: {
  canonicalProductId: string
  currentProductId: string
}) {
  const [comparison, setComparison] = useState<PriceComparison | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .get<PriceComparison>(`/canonical-products/${canonicalProductId}/comparison`)
      .then(setComparison)
      .catch((e) => setError(e.message))
  }, [canonicalProductId])

  if (error) return null
  if (!comparison) return <SkeletonCard lines={2} />
  if (comparison.exactMatches.length <= 1) return null

  return (
    <div className="card stack">
      <h2>Compare prices</h2>
      <p className="muted">Other sellers with the exact same product.</p>
      <table className="comparison-table">
        <tbody>
          {comparison.exactMatches.map((entry) => (
            <tr key={entry.productId} className={entry.productId === currentProductId ? "comparison-row-current" : ""}>
              <td>
                {entry.productId === currentProductId ? (
                  <strong>{entry.businessName} (this listing)</strong>
                ) : (
                  <Link to={`/market/products/${entry.productId}`}>{entry.businessName}</Link>
                )}
              </td>
              <td className="comparison-price">
                {entry.available ? (
                  <MoneyDisplay amount={entry.price} currency={entry.currency} />
                ) : (
                  <span className="muted">
                    <MoneyDisplay amount={entry.price} currency={entry.currency} /> · unavailable
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
