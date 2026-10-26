import { JourneyCard } from "../../components/market/JourneyCard"
import { useCallback, useEffect, useRef, useState } from "react"
import { Link } from "react-router-dom"
import { Search, SlidersHorizontal, X } from "lucide-react"
import { api } from "../../lib/api"
import type { PublicBusiness, Page, Product } from "../../lib/types"
import { ProductCard } from "../../components/market/ProductCard"
import { BusinessCard } from "../../components/market/BusinessCard"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { SkeletonGrid } from "../../components/ui/Skeleton"

type MarketTab = "products" | "businesses"

/** Consumer-facing PULA Market — Shop (Phase 5).
 *  Products: uses /api/market/products (CAN_SELL_TO_CONSUMERS scoped, never leaks wholesaler stock).
 *  Businesses: uses /api/businesses?capability=CAN_SELL_TO_CONSUMERS for the same reason.
 *  Categories: populated from /api/market/categories (top-12 pill row). */
export function MarketHomePage() {
  const [tab, setTab] = useState<MarketTab>("products")
  const [query, setQuery] = useState("")
  const [category, setCategory] = useState("")
  const [maxPrice, setMaxPrice] = useState("")
  const [showPriceFilter, setShowPriceFilter] = useState(false)
  const [categories, setCategories] = useState<string[]>([])
  const [page, setPage] = useState<Page<Product> | null>(null)
  const [businessPage, setBusinessPage] = useState<Page<PublicBusiness> | null>(null)
  const [pageNum, setPageNum] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)

  // Load popular categories once on mount for the pill row
  useEffect(() => {
    api
      .get<string[]>("/market/categories?limit=12")
      .then(setCategories)
      .catch(() => {/* non-fatal — pill row stays empty */})
  }, [])

  const load = useCallback(
    (nextPage: number, replace: boolean) => {
      setLoading(true)
      const params = new URLSearchParams({ page: String(nextPage), size: "12" })
      if (query.trim()) params.set("q", query.trim())
      if (!query.trim() && category) params.set("category", category)
      if (maxPrice && !isNaN(Number(maxPrice))) params.set("maxPrice", maxPrice)

      // Phase 5: use the consumer-scoped endpoint
      api
        .get<Page<Product>>(`/market/products?${params.toString()}`)
        .then((result) => {
          setPage((prev) => replace || !prev ? result : { ...result, content: [...prev.content, ...result.content] })
          setError(null)
        })
        .catch((e) => setError(e instanceof Error ? e.message : "Could not load the market"))
        .finally(() => setLoading(false))
    },
    [query, category, maxPrice],
  )

  const loadBusinesses = useCallback(
    (nextPage: number, replace: boolean) => {
      setLoading(true)
      const params = new URLSearchParams({
        page: String(nextPage),
        size: "12",
        capability: "CAN_SELL_TO_CONSUMERS",
      })
      if (category) params.set("category", category)

      api
        .get<Page<PublicBusiness>>(`/businesses?${params.toString()}`)
        .then((result) => {
          setBusinessPage((prev) => replace || !prev ? result : { ...result, content: [...prev.content, ...result.content] })
          setError(null)
        })
        .catch((e) => setError(e instanceof Error ? e.message : "Could not load businesses"))
        .finally(() => setLoading(false))
    },
    [category],
  )

  useEffect(() => {
    setPageNum(0)
    if (tab === "products") load(0, true)
    else loadBusinesses(0, true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, category, maxPrice, tab])

  function selectCategory(cat: string) {
    setCategory((prev) => (prev === cat ? "" : cat))
    setQuery("")
  }

  function clearFilters() {
    setQuery("")
    setCategory("")
    setMaxPrice("")
    setShowPriceFilter(false)
    searchRef.current?.focus()
  }

  const hasFilters = Boolean(query.trim() || category || maxPrice)
  const onRetry = () => tab === "products" ? load(0, true) : loadBusinesses(0, true)

  return (
    <div className="page">
      <PageHeader
        title="PULA Market"
        subtitle="Shop from local businesses, spazas, traders and service providers in your community."
      />
      <JourneyCard />

      <div className="row wrap">
        <Link to="/market/food" className="btn btn-primary">Food &amp; restaurants</Link>
        <Link to="/market/compare-basket" className="btn btn-ghost">
          Compare My Basket
        </Link>
      </div>

      {/* Tab bar */}
      <div className="tab-bar" role="tablist">
        {(["products", "businesses"] as MarketTab[]).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            className={`tab ${tab === t ? "active" : ""}`}
            onClick={() => { setTab(t); setPageNum(0) }}
          >
            {t === "products" ? "Products" : "Sellers"}
          </button>
        ))}
      </div>

      {/* Search + price filter */}
      <div className="market-search row wrap">
        {tab === "products" && (
          <div className="search-input-wrap">
            <Search size={16} aria-hidden="true" className="search-input-icon" />
            <input
              ref={searchRef}
              className="market-search-input search-input-with-icon"
              placeholder="Search products…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
        )}
        <input
          className="market-search-input"
          placeholder="Filter by category…"
          value={category}
          onChange={(e) => setCategory(e.target.value)}
          disabled={tab === "products" && Boolean(query.trim())}
          style={{ maxWidth: 200 }}
        />
        {tab === "products" && (
          <button
            className={`btn btn-ghost ${showPriceFilter ? "active" : ""}`}
            onClick={() => setShowPriceFilter((v) => !v)}
            aria-expanded={showPriceFilter}
            aria-label="Toggle price filter"
            title="Filter by price"
          >
            <SlidersHorizontal size={15} aria-hidden="true" />
          </button>
        )}
        {hasFilters && (
          <button className="btn btn-ghost" onClick={clearFilters} style={{ color: "var(--danger)" }}>
            <X size={15} aria-hidden="true" /> Clear
          </button>
        )}
      </div>

      {/* Price filter panel */}
      {showPriceFilter && tab === "products" && (
        <div className="market-filter-panel row" style={{ gap: 12, alignItems: "center", marginBottom: 8 }}>
          <label className="muted" style={{ fontSize: "var(--text-sm)" }}>Max price</label>
          <input
            type="number"
            min="0"
            step="1"
            placeholder="e.g. 200"
            className="market-search-input"
            style={{ maxWidth: 140 }}
            value={maxPrice}
            onChange={(e) => setMaxPrice(e.target.value)}
          />
          {maxPrice && <span className="muted" style={{ fontSize: "var(--text-sm)" }}>≤ R{maxPrice}</span>}
        </div>
      )}

      {/* Category pills — only shown when there's no text search active */}
      {categories.length > 0 && !query.trim() && (
        <div className="category-pills" role="list" aria-label="Browse by category">
          {categories.map((cat) => (
            <button
              key={cat}
              role="listitem"
              className={`category-pill ${category === cat ? "active" : ""}`}
              onClick={() => selectCategory(cat)}
              aria-pressed={category === cat}
            >
              {cat}
            </button>
          ))}
        </div>
      )}

      {error && <ErrorState description={error} onRetry={onRetry} />}

      {tab === "products" ? (
        page === null || loading && page.content.length === 0 ? (
          <SkeletonGrid count={6} columns="product-grid" />
        ) : page.content.length === 0 ? (
          <EmptyState
            title="No products found"
            description={hasFilters
              ? "Try adjusting your search or category — new sellers join regularly."
              : "No products listed yet — check back soon as new sellers join PULA Market."}
            action={hasFilters ? <Button variant="ghost" onClick={clearFilters}><X size={14} /> Clear filters</Button> : undefined}
          />
        ) : (
          <>
            <div className="product-grid">
              {page.content.map((p) => <ProductCard key={p.id} product={p} />)}
            </div>
            {!page.last && (
              <div className="row" style={{ justifyContent: "center", marginTop: 16 }}>
                <Button
                  variant="ghost"
                  onClick={() => { const next = pageNum + 1; setPageNum(next); load(next, false) }}
                >
                  Load more
                </Button>
              </div>
            )}
          </>
        )
      ) : businessPage === null || loading && businessPage.content.length === 0 ? (
        <SkeletonGrid count={6} columns="business-grid" />
      ) : businessPage.content.length === 0 ? (
        <EmptyState
          title="No sellers found"
          description={category
            ? `No sellers in "${category}" yet — try a different category or browse all.`
            : "No sellers registered yet — check back soon."}
          action={category ? <Button variant="ghost" onClick={clearFilters}><X size={14} /> Clear filter</Button> : undefined}
        />
      ) : (
        <>
          <div className="business-grid">
            {businessPage.content.map((b) => <BusinessCard key={b.id} business={b} />)}
          </div>
          {!businessPage.last && (
            <div className="row" style={{ justifyContent: "center", marginTop: 16 }}>
              <Button
                variant="ghost"
                onClick={() => { const next = pageNum + 1; setPageNum(next); loadBusinesses(next, false) }}
              >
                Load more
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
