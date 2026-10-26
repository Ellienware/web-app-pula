import { StockPlanPanel } from "../../components/market/StockPlanPanel"
import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { Search } from "lucide-react"
import { api } from "../../lib/api"
import type { Page, WholesaleProduct, StockCart } from "../../lib/types"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { SkeletonGrid } from "../../components/ui/Skeleton"
import WholesaleProductCard from "../../components/market/WholesaleProductCard"
import StockCartPanel from "../../components/market/StockCartPanel"

/**
 * PULA Market — Stock (brief §11). The B2B procurement marketplace.
 * Buyers with CAN_BUY_STOCK browse wholesale catalogues and add to a
 * separate stock cart. Only authenticated business owners see this.
 */
export default function StockMarketPage() {
  const [query, setQuery] = useState("")
  const [category, setCategory] = useState("")
  const [products, setProducts] = useState<Page<WholesaleProduct> | null>(null)
  const [cart, setCart] = useState<StockCart | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showCart, setShowCart] = useState(false)
  const [pageNum, setPageNum] = useState(0)

  useEffect(() => {
    api.get<StockCart>("/stock/cart").then(setCart).catch(() => null)
  }, [])

  useEffect(() => {
    setPageNum(0)
    load(0, true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, category])

  function load(page: number, replace: boolean) {
    setLoading(true)
    const params = new URLSearchParams({ page: String(page), size: "12" })
    if (query.trim()) params.set("q", query.trim())
    if (!query.trim() && category) params.set("category", category)
    api.get<Page<WholesaleProduct>>(`/stock/products?${params}`)
      .then((res) => {
        setProducts((prev) => replace || !prev ? res : { ...res, content: [...prev.content, ...res.content] })
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  async function addToCart(product: WholesaleProduct) {
    try {
      const updated = await api.post<StockCart>("/stock/cart/items", {
        wholesaleProductId: product.id,
        quantityPacks: product.moq,
      })
      setCart(updated)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not add to stock cart")
    }
  }

  const cartCount = cart?.items.length ?? 0

  return (
    <div className="page stack">
      <PageHeader
        title="PULA Market — Stock"
        subtitle="Buy stock from wholesalers and suppliers. Prices are per pack (as described)."
      />
      <StockPlanPanel onCartChanged={setCart} />

      <div className="row wrap" style={{ gap: 8, justifyContent: "space-between" }}>
        <div className="row" style={{ gap: 8, flex: 1 }}>
          <div className="search-input-wrap" style={{ flex: 1 }}>
            <Search size={16} aria-hidden="true" className="search-input-icon" />
            <input
              className="market-search-input search-input-with-icon"
              placeholder="Search wholesale products…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <input
            className="market-search-input"
            placeholder="Category…"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            style={{ maxWidth: 160 }}
          />
        </div>
        <div className="row" style={{ gap: 8 }}>
          <Link to="/market/stock/orders" className="btn btn-ghost">My stock orders</Link>
          <Link to="/market/stock/bulk" className="btn btn-ghost">Bulk buying</Link>
          <Link to="/market/stock/catalogue" className="btn btn-ghost">Manage my catalogue</Link>
          <Button onClick={() => setShowCart(true)} style={{ position: "relative" }}>
            🛒 Stock cart {cartCount > 0 && <span className="cart-badge">{cartCount}</span>}
          </Button>
        </div>
      </div>

      {error && <ErrorState description={error} onRetry={() => load(0, true)} />}

      {showCart && (
        <StockCartPanel
          cart={cart}
          onUpdated={setCart}
          onClose={() => setShowCart(false)}
        />
      )}

      {loading && !products ? (
        <SkeletonGrid count={6} columns="product-grid" />
      ) : !products || products.content.length === 0 ? (
        <EmptyState
          title="No wholesale products found"
          description={query || category
            ? "Try a different search term or category."
            : "No suppliers have listed products yet — check back soon."}
        />
      ) : (
        <>
          <div className="product-grid">
            {products.content.map((p) => (
              <WholesaleProductCard key={p.id} product={p} onAddToCart={() => addToCart(p)} />
            ))}
          </div>
          {!products.last && (
            <div style={{ textAlign: "center", marginTop: 16 }}>
              <Button variant="ghost" onClick={() => { const n = pageNum + 1; setPageNum(n); load(n, false) }}>
                Load more
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
