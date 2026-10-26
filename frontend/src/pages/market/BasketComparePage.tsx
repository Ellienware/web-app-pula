import { useState } from "react"
import { Search, Trash2 } from "lucide-react"
import { api } from "../../lib/api"
import type { BasketComparison, CanonicalProduct, Page } from "../../lib/types"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { MoneyDisplay } from "../../components/ui/MoneyDisplay"

interface BasketLine {
  canonicalProduct: CanonicalProduct
  quantity: number
}

export function BasketComparePage() {
  const [query, setQuery] = useState("")
  const [results, setResults] = useState<CanonicalProduct[]>([])
  const [lines, setLines] = useState<BasketLine[]>([])
  const [comparison, setComparison] = useState<BasketComparison | null>(null)
  const [searching, setSearching] = useState(false)
  const [comparing, setComparing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function search(term: string) {
    setQuery(term)
    if (!term.trim()) {
      setResults([])
      return
    }
    setSearching(true)
    api
      .get<Page<CanonicalProduct>>(`/canonical-products?q=${encodeURIComponent(term)}&size=8`)
      .then((page) => setResults(page.content))
      .catch(() => setResults([]))
      .finally(() => setSearching(false))
  }

  function addLine(product: CanonicalProduct) {
    setLines((current) => {
      if (current.some((l) => l.canonicalProduct.id === product.id)) return current
      return [...current, { canonicalProduct: product, quantity: 1 }]
    })
    setQuery("")
    setResults([])
  }

  function updateQuantity(id: string, quantity: number) {
    setLines((current) =>
      current.map((l) => (l.canonicalProduct.id === id ? { ...l, quantity: Math.max(1, quantity) } : l)),
    )
  }

  function removeLine(id: string) {
    setLines((current) => current.filter((l) => l.canonicalProduct.id !== id))
    setComparison(null)
  }

  async function handleCompare() {
    setComparing(true)
    setError(null)
    try {
      const response = await api.post<BasketComparison>("/baskets/compare", {
        items: lines.map((l) => ({ canonicalProductId: l.canonicalProduct.id, quantity: l.quantity })),
      })
      setComparison(response)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not compare basket")
    } finally {
      setComparing(false)
    }
  }

  return (
    <div className="page stack">
      <PageHeader
        title="Compare My Basket"
        subtitle="Add a few items and see which sellers work out cheapest — this is an estimate based on listed prices, not a live checkout total."
      />

      <div className="card stack">
        <div className="search-input-wrap">
          <Search size={16} aria-hidden="true" className="search-input-icon" />
          <input
            className="market-search-input search-input-with-icon"
            placeholder="Search for an item, e.g. Full Cream Milk 2L…"
            value={query}
            onChange={(e) => search(e.target.value)}
          />
        </div>
        {searching && <p className="muted">Searching…</p>}
        {results.length > 0 && (
          <ul className="stack" style={{ listStyle: "none", padding: 0 }}>
            {results.map((r) => (
              <li key={r.id}>
                <button type="button" className="btn btn-ghost" style={{ width: "100%", justifyContent: "flex-start" }} onClick={() => addLine(r)}>
                  {r.name} {r.brand ? `· ${r.brand}` : ""}
                </button>
              </li>
            ))}
          </ul>
        )}

        {lines.length === 0 ? (
          <p className="muted">Your basket is empty — search above to add items.</p>
        ) : (
          <table className="comparison-table">
            <tbody>
              {lines.map((line) => (
                <tr key={line.canonicalProduct.id}>
                  <td>{line.canonicalProduct.name}</td>
                  <td>
                    <input
                      type="number"
                      min={1}
                      value={line.quantity}
                      onChange={(e) => updateQuantity(line.canonicalProduct.id, Number(e.target.value))}
                      style={{ width: 60 }}
                      aria-label={`Quantity for ${line.canonicalProduct.name}`}
                    />
                  </td>
                  <td>
                    <button
                      type="button"
                      className="btn btn-ghost"
                      onClick={() => removeLine(line.canonicalProduct.id)}
                      aria-label={`Remove ${line.canonicalProduct.name}`}
                    >
                      <Trash2 size={14} aria-hidden="true" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {lines.length > 0 && (
          <Button onClick={handleCompare} disabled={comparing}>
            {comparing ? "Comparing…" : "Compare prices"}
          </Button>
        )}
      </div>

      {error && <ErrorState description={error} onRetry={handleCompare} />}

      {comparison && (
        <div className="card stack">
          <h2>Estimated totals by seller</h2>
          {comparison.storeTotals.length === 0 ? (
            <EmptyState
              title="No matching sellers found"
              description="None of these items have a linked seller listing yet."
            />
          ) : (
            <>
              <table className="comparison-table">
                <tbody>
                  {comparison.storeTotals.map((store) => (
                    <tr
                      key={store.businessId}
                      className={
                        comparison.cheapestCompleteStore?.businessId === store.businessId
                          ? "comparison-row-current"
                          : ""
                      }
                    >
                      <td>
                        {store.businessName}
                        {!store.completeBasket && (
                          <span className="muted"> · {store.itemsFound}/{store.itemsRequested} items available</span>
                        )}
                        {comparison.cheapestCompleteStore?.businessId === store.businessId && (
                          <span className="badge success" style={{ marginLeft: 8 }}>
                            Cheapest full basket
                          </span>
                        )}
                        {store.minimumOrderAmount !== null && !store.meetsMinimumOrder && (
                          <div className="muted" style={{ fontSize: "0.8rem" }}>
                            Needs a minimum order of <MoneyDisplay amount={store.minimumOrderAmount} currency={store.currency} /> to qualify
                          </div>
                        )}
                        {store.deliveryFee !== null && (
                          <div className="muted" style={{ fontSize: "0.8rem" }}>
                            + <MoneyDisplay amount={store.deliveryFee} currency={store.currency} /> delivery
                          </div>
                        )}
                      </td>
                      <td className="comparison-price">
                        <MoneyDisplay amount={store.estimatedTotal} currency={store.currency} />
                        {store.deliveryFee !== null && (
                          <div className="muted" style={{ fontSize: "0.75rem" }}>
                            (<MoneyDisplay amount={store.estimatedSubtotal} currency={store.currency} /> + delivery)
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {comparison.potentialSavings !== null && comparison.currency && (
                <p className="muted">
                  Potential savings by choosing the cheapest full basket:{" "}
                  <MoneyDisplay amount={comparison.potentialSavings} currency={comparison.currency} />
                </p>
              )}
            </>
          )}
        </div>
      )}

      {comparison && comparison.fewestDeliveriesPlan && comparison.fewestDeliveriesPlan.length > 1 && (
        <div className="card stack">
          <h2>Or, pick it up in fewer trips</h2>
          <p className="muted" style={{ fontSize: "0.85rem" }}>
            A combination of sellers that covers your whole basket using as few stores as possible — not
            necessarily the cheapest, but fewer deliveries to arrange.
          </p>
          <table className="comparison-table">
            <tbody>
              {comparison.fewestDeliveriesPlan.map((stop) => (
                <tr key={stop.businessId}>
                  <td>
                    {stop.businessName}
                    <div className="muted" style={{ fontSize: "0.8rem" }}>
                      {stop.canonicalProductIdsCoveredHere.length} item
                      {stop.canonicalProductIdsCoveredHere.length === 1 ? "" : "s"} from here
                    </div>
                  </td>
                  <td className="comparison-price">
                    <MoneyDisplay
                      amount={stop.deliveryFee ? stop.subtotal + stop.deliveryFee : stop.subtotal}
                      currency={comparison.currency ?? "ZAR"}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {comparison.fewestDeliveriesPlanTotal !== null && comparison.currency && (
            <p>
              <strong>Total across {comparison.fewestDeliveriesPlan.length} stores: </strong>
              <MoneyDisplay amount={comparison.fewestDeliveriesPlanTotal} currency={comparison.currency} />
            </p>
          )}
        </div>
      )}
    </div>
  )
}
