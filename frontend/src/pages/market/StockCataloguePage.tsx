import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import type { Page, WholesaleProduct, CreateWholesaleProductRequest, CanonicalProduct, MatchClassification, WholesalePriceComparison } from "../../lib/types"
import { formatMoney } from "../../lib/format"
import { useCurrencyConfig } from "../../lib/currencyConfig"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { ImageField } from "../../components/ui/ImageField"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { CatalogueImportPanel } from "../../components/market/CatalogueImportPanel"
import { VolumeTiersPanel } from "../../components/market/VolumeTiersPanel"

/** Supplier's wholesale catalogue management (brief §12-13). */
export default function StockCataloguePage() {
  const [products, setProducts] = useState<WholesaleProduct[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showForm, setShowForm] = useState(false)
  const { defaultCurrency: currency } = useCurrencyConfig()

  const [name, setName] = useState("")
  const [sku, setSku] = useState("")
  const [category, setCategory] = useState("")
  const [packSize, setPackSize] = useState("1")
  const [moq, setMoq] = useState("1")
  const [pricePerPack, setPricePerPack] = useState("")
  const [availableStock, setAvailableStock] = useState("0")
  const [sellingMode, setSellingMode] = useState<"INDIVIDUAL_ONLY" | "BULK_ONLY" | "BOTH">("BULK_ONLY")
  const [pricePerUnit, setPricePerUnit] = useState("")
  const [imageUrls, setImageUrls] = useState<string[]>([])
  const [formBusy, setFormBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const [linkProductId, setLinkProductId] = useState<string | null>(null)
  const [canonicalQuery, setCanonicalQuery] = useState("")
  const [canonicalResults, setCanonicalResults] = useState<CanonicalProduct[]>([])
  const [selectedCanonicalId, setSelectedCanonicalId] = useState("")
  const [matchClassification, setMatchClassification] = useState<MatchClassification>("EXACT_MATCH")
  const [linkBusy, setLinkBusy] = useState(false)
  const [linkError, setLinkError] = useState<string | null>(null)
  const [comparisonProductId, setComparisonProductId] = useState<string | null>(null)
  const [comparison, setComparison] = useState<WholesalePriceComparison | null>(null)
  const [comparisonBusy, setComparisonBusy] = useState(false)
  const [comparisonError, setComparisonError] = useState<string | null>(null)

  function load() {
    setLoading(true)
    api.get<Page<WholesaleProduct>>("/stock/products/mine")
      .then((res) => { setProducts(res.content); setError(null) })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  async function createProduct() {
    if (!name.trim() || (sellingMode !== "INDIVIDUAL_ONLY" && !pricePerPack) || (sellingMode !== "BULK_ONLY" && !pricePerUnit)) { setFormError("Enter the required price for the selected selling mode"); return }
    setFormBusy(true); setFormError(null)
    try {
      const req: CreateWholesaleProductRequest = {
        name: name.trim(), sku: sku.trim() || null, category: category.trim() || null,
        packSize: Number(packSize), moq: Number(moq),
        pricePerPack: pricePerPack ? Number(pricePerPack) : 0, availableStock: Number(availableStock),
        sellingMode, pricePerUnit: pricePerUnit ? Number(pricePerUnit) : null,
        imageUrls,
      }
      await api.post<WholesaleProduct>("/stock/products", req)
      setName(""); setSku(""); setCategory(""); setPackSize("1"); setMoq("1")
      setPricePerPack(""); setAvailableStock("0"); setSellingMode("BULK_ONLY"); setPricePerUnit(""); setImageUrls([])
      setShowForm(false)
      load()
    } catch (e) {
      setFormError(e instanceof Error ? e.message : "Could not create product")
    } finally {
      setFormBusy(false)
    }
  }

  async function deactivate(id: string) {
    try {
      await api.del(`/stock/products/${id}`)
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not remove product")
    }
  }

  async function searchCanonicalProducts() {
    setLinkError(null)
    setCanonicalResults([])
    if (!canonicalQuery.trim()) {
      setLinkError("Enter a product name to search.")
      return
    }
    try {
      const result = await api.get<Page<CanonicalProduct>>(
        `/stock/canonical-products?q=${encodeURIComponent(canonicalQuery.trim())}&size=10`,
      )
      setCanonicalResults(result.content)
      if (result.content.length === 0) setLinkError("No canonical products found. Try another search.")
    } catch (e) {
      setLinkError(e instanceof Error ? e.message : "Could not search canonical products")
    }
  }

  async function saveCanonicalLink(product: WholesaleProduct) {
    setLinkBusy(true)
    setLinkError(null)
    try {
      const updated = await api.put<WholesaleProduct>(
        `/stock/products/${product.id}/canonical-link`,
        {
          canonicalProductId: selectedCanonicalId,
          classification: matchClassification,
        },
      )
      setProducts((current) => current.map((item) => item.id === updated.id ? updated : item))
      setLinkProductId(null)
      setCanonicalQuery("")
      setCanonicalResults([])
      setSelectedCanonicalId("")
    } catch (e) {
      setLinkError(e instanceof Error ? e.message : "Could not link wholesale product")
    } finally {
      setLinkBusy(false)
    }
  }

  async function unlinkCanonicalProduct(product: WholesaleProduct) {
    setLinkBusy(true)
    setLinkError(null)
    try {
      const updated = await api.put<WholesaleProduct>(
        `/stock/products/${product.id}/canonical-link`,
        { canonicalProductId: null, classification: "UNIQUE_PRODUCT" },
      )
      setProducts((current) => current.map((item) => item.id === updated.id ? updated : item))
      setLinkProductId(null)
      setComparison(null)
      setComparisonProductId(null)
    } catch (e) {
      setLinkError(e instanceof Error ? e.message : "Could not unlink wholesale product")
    } finally {
      setLinkBusy(false)
    }
  }

  async function loadWholesaleComparison(product: WholesaleProduct) {
    if (!product.canonicalProductId) return
    setComparisonProductId(product.id)
    setComparison(null)
    setComparisonError(null)
    setComparisonBusy(true)
    try {
      const result = await api.get<WholesalePriceComparison>(
        `/canonical-products/${product.canonicalProductId}/wholesale-comparison`,
      )
      setComparison(result)
    } catch (e) {
      setComparisonError(e instanceof Error ? e.message : "Could not load wholesale comparison")
    } finally {
      setComparisonBusy(false)
    }
  }

  return (
    <div className="page stack">
      <PageHeader title="My wholesale catalogue" subtitle="Products buyers can order from your business." />
      <CatalogueImportPanel onDone={load} />
      <Button onClick={() => setShowForm((v) => !v)} style={{ alignSelf: "flex-start" }}>
        {showForm ? "Cancel" : "+ Add product"}
      </Button>

      {showForm && (
        <div className="card stack" style={{ maxWidth: 480 }}>
          <h3>Add wholesale product</h3>
          <label className="field"><span>Product name *</span>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. 2L Cooking Oil (case of 12)" />
          </label>
          <div className="field-row">
            <label className="field"><span>SKU (optional)</span>
              <input value={sku} onChange={(e) => setSku(e.target.value)} placeholder="e.g. OIL-2L-12" />
            </label>
            <label className="field"><span>Category</span>
              <input value={category} onChange={(e) => setCategory(e.target.value)} placeholder="e.g. Cooking" />
            </label>
          </div>
          <ImageField value={imageUrls} onChange={setImageUrls} max={4} />
          <label className="field"><span>Selling mode *</span>
            <select value={sellingMode} onChange={(e) => setSellingMode(e.target.value as typeof sellingMode)}>
              <option value="INDIVIDUAL_ONLY">Individual units only</option>
              <option value="BULK_ONLY">Bulk packs only</option>
              <option value="BOTH">Individual units and bulk packs</option>
            </select>
          </label>
          {(sellingMode === "INDIVIDUAL_ONLY" || sellingMode === "BOTH") && (
            <label className="field"><span>Price per individual unit (R) *</span>
              <input type="number" min="0" step="0.01" value={pricePerUnit} onChange={(e) => setPricePerUnit(e.target.value)} placeholder="e.g. 12.50" />
            </label>
          )}
          <div className="field-row">
            <label className="field"><span>Pack size (units/pack)</span>
              <input type="number" min="1" value={packSize} onChange={(e) => setPackSize(e.target.value)} />
            </label>
            <label className="field"><span>MOQ (min packs)</span>
              <input type="number" min="1" value={moq} onChange={(e) => setMoq(e.target.value)} />
            </label>
          </div>
          <div className="field-row">
            {sellingMode !== "INDIVIDUAL_ONLY" && (
              <label className="field"><span>Price per pack (R) *</span>
                <input type="number" min="0" step="0.01" value={pricePerPack} onChange={(e) => setPricePerPack(e.target.value)} />
              </label>
            )}
            <label className="field"><span>Available stock (packs)</span>
              <input type="number" min="0" value={availableStock} onChange={(e) => setAvailableStock(e.target.value)} />
            </label>
          </div>
          {sellingMode === "INDIVIDUAL_ONLY" && <p className="muted" style={{ fontSize: "0.82rem" }}>Individual-unit checkout is not enabled yet. This listing can be saved and displayed, but buyers cannot place individual-unit orders until the next checkout stage.</p>}
          {formError && <p className="form-error">{formError}</p>}
          <Button onClick={createProduct} disabled={formBusy}>{formBusy ? "Saving…" : "Add to catalogue"}</Button>
        </div>
      )}

      {error && <ErrorState description={error} onRetry={load} />}
      {loading && <p className="muted">Loading your catalogue…</p>}
      {!loading && !error && products.length === 0 && (
        <EmptyState title="No products yet" description="Add wholesale products to your catalogue so buyers can order from you." />
      )}

      <div className="product-grid">
        {products.map((p) => (
          <div key={p.id} className="product-card">
            <div className="product-card-image">
              {p.imageUrls?.[0] ? (
                <img
                  src={p.imageUrls[0]}
                  alt={p.name}
                  loading="lazy"
                  style={{ width: "100%", height: "100%", objectFit: "cover" }}
                />
              ) : (
                <div className="product-card-placeholder" aria-hidden="true">
                  <span style={{ fontSize: "2rem" }}>📦</span>
                </div>
              )}
            </div>
            <div className="product-card-body">
              <h3 className="product-card-name">{p.name}</h3>
              {p.sku && <p className="muted" style={{ fontSize: "0.75rem" }}>SKU: {p.sku}</p>}
              <div className="row wrap" style={{ gap: 4, marginBottom: 8 }}>
                {p.sellingMode !== "INDIVIDUAL_ONLY" && <span className="badge">{formatMoney(p.pricePerPack, currency)}/pack</span>}
                {(p.sellingMode === "INDIVIDUAL_ONLY" || p.sellingMode === "BOTH") && p.pricePerUnit != null && <span className="badge">{formatMoney(p.pricePerUnit, currency)}/unit</span>}
                <span className="badge badge-muted">{p.sellingMode === "BOTH" ? "Individual + bulk" : p.sellingMode === "INDIVIDUAL_ONLY" ? "Individual only" : "Bulk only"}</span>
                <span className="badge badge-muted">{p.packSize} units</span>
                <span className="badge badge-muted">MOQ {p.moq}</span>
                <span className="badge badge-muted">{p.availableStock} packs left</span>
              </div>
              {p.canonicalProductId ? (
                <div className="card stack" style={{ marginBottom: 10, padding: 10 }}>
                  <p style={{ margin: 0 }}>
                    <strong>Canonical product:</strong> {p.canonicalProductName || p.canonicalProductId}
                  </p>
                  <p className="muted" style={{ margin: 0, fontSize: "0.8rem" }}>
                    Match: {p.matchClassification || "EXACT_MATCH"}
                  </p>
                  <div className="row wrap" style={{ gap: 8 }}>
                    <Button onClick={() => loadWholesaleComparison(p)} disabled={comparisonBusy}>
                      {comparisonBusy && comparisonProductId === p.id ? "Loading…" : "Compare wholesale prices"}
                    </Button>
                    <Button onClick={() => {
                      setLinkProductId(p.id)
                      setCanonicalQuery("")
                      setCanonicalResults([])
                      setSelectedCanonicalId(p.canonicalProductId || "")
                      setMatchClassification(p.matchClassification === "SIMILAR_ALTERNATIVE" ? "SIMILAR_ALTERNATIVE" : "EXACT_MATCH")
                      setLinkError(null)
                    }}>
                      Change link
                    </Button>
                    <Button variant="danger" onClick={() => unlinkCanonicalProduct(p)} disabled={linkBusy}>
                      Unlink
                    </Button>
                  </div>
                </div>
              ) : (
                <Button onClick={() => {
                  setLinkProductId(p.id)
                  setCanonicalQuery("")
                  setCanonicalResults([])
                  setSelectedCanonicalId("")
                  setMatchClassification("EXACT_MATCH")
                  setLinkError(null)
                }} style={{ width: "100%", marginBottom: 10 }}>
                  Link canonical product
                </Button>
              )}

              {linkProductId === p.id && (
                <div className="card stack" style={{ marginBottom: 10, padding: 12 }}>
                  <h4 style={{ margin: 0 }}>Link to canonical product</h4>
                  <p className="muted" style={{ margin: 0, fontSize: "0.82rem" }}>
                    Search and select a product. Nothing is linked automatically.
                  </p>
                  <div className="field">
                    <label htmlFor={`canonical-query-${p.id}`}>Search canonical products</label>
                    <div className="row wrap" style={{ gap: 6 }}>
                      <input
                        id={`canonical-query-${p.id}`}
                        value={canonicalQuery}
                        onChange={(e) => setCanonicalQuery(e.target.value)}
                        placeholder="e.g. cooking oil"
                      />
                      <Button onClick={searchCanonicalProducts}>Search</Button>
                    </div>
                  </div>
                  {canonicalResults.map((canonical) => (
                    <label key={canonical.id} className="row" style={{ gap: 8, alignItems: "flex-start" }}>
                      <input
                        type="radio"
                        name={`canonical-choice-${p.id}`}
                        checked={selectedCanonicalId === canonical.id}
                        onChange={() => setSelectedCanonicalId(canonical.id)}
                      />
                      <span>
                        <strong>{canonical.name}</strong>
                        <span className="muted" style={{ display: "block", fontSize: "0.8rem" }}>
                          {[canonical.brand, canonical.category, canonical.size, canonical.unit].filter(Boolean).join(" · ")}
                        </span>
                      </span>
                    </label>
                  ))}
                  <label className="field">
                    <span>Match classification</span>
                    <select
                      value={matchClassification}
                      onChange={(e) => setMatchClassification(e.target.value as MatchClassification)}
                    >
                      <option value="EXACT_MATCH">Exact match</option>
                      <option value="SIMILAR_ALTERNATIVE">Similar alternative</option>
                    </select>
                  </label>
                  {linkError && <p className="form-error" role="alert">{linkError}</p>}
                  <div className="row wrap" style={{ gap: 8 }}>
                    <Button onClick={() => saveCanonicalLink(p)} disabled={linkBusy || !selectedCanonicalId}>
                      {linkBusy ? "Saving…" : "Save link"}
                    </Button>
                    <Button onClick={() => { setLinkProductId(null); setLinkError(null) }}>
                      Cancel
                    </Button>
                  </div>
                </div>
              )}

              {comparisonProductId === p.id && (
                <div className="card stack" style={{ marginBottom: 10, padding: 12 }}>
                  <h4 style={{ margin: 0 }}>Wholesale price comparison</h4>
                  {comparisonBusy && <p className="muted">Loading comparison…</p>}
                  {comparisonError && <p className="form-error" role="alert">{comparisonError}</p>}
                  {comparison && (
                    <>
                      <p style={{ margin: 0 }}>{comparison.canonicalProductName}</p>
                      <h5 style={{ marginBottom: 0 }}>Exact matches</h5>
                      {comparison.exactMatches.length === 0 && <p className="muted">No exact wholesale matches linked yet.</p>}
                      {comparison.exactMatches.map((entry) => (
                        <div key={entry.wholesaleProductId} className="card stack" style={{ padding: 8 }}>
                          <strong>{entry.name}</strong>
                          <span>{entry.supplierBusinessName}</span>
                          <span>{entry.pricePerPack == null ? "Pack price unavailable" : `${entry.currency} ${entry.pricePerPack.toFixed(2)} per pack`}</span>
                          <span className="muted" style={{ fontSize: "0.82rem" }}>
                            {entry.normalizedPricePerItem == null
                              ? "Price per item unavailable"
                              : `${entry.currency} ${entry.normalizedPricePerItem.toFixed(4)} per item`}
                            {" · "}Pack size {entry.packSize}
                            {" · "}MOQ {entry.moq}
                            {" · "}Stock {entry.availableStock}
                            {" · "}{entry.available ? "In stock" : "Out of stock"}
                          </span>
                        </div>
                      ))}
                      <h5 style={{ marginBottom: 0 }}>Similar alternatives</h5>
                      {comparison.similarAlternatives.length === 0 && <p className="muted">No similar alternatives linked yet.</p>}
                      {comparison.similarAlternatives.map((entry) => (
                        <div key={entry.wholesaleProductId} className="card stack" style={{ padding: 8 }}>
                          <strong>{entry.name}</strong>
                          <span>{entry.supplierBusinessName}</span>
                          <span>{entry.pricePerPack == null ? "Pack price unavailable" : `${entry.currency} ${entry.pricePerPack.toFixed(2)} per pack`}</span>
                          <span className="muted" style={{ fontSize: "0.82rem" }}>
                            Pack size {entry.packSize} · MOQ {entry.moq} · Stock {entry.availableStock}
                          </span>
                        </div>
                      ))}
                      <Button onClick={() => { setComparisonProductId(null); setComparison(null); setComparisonError(null) }}>
                        Close comparison
                      </Button>
                    </>
                  )}
                </div>
              )}

              {linkProductId === p.id && linkError && !canonicalResults.length && (
                <p className="form-error" role="alert">{linkError}</p>
              )}

              {p.sellingMode !== "INDIVIDUAL_ONLY" && <VolumeTiersPanel productId={p.id} basePrice={p.pricePerPack} currency={currency} />}
              <Button variant="danger" onClick={() => deactivate(p.id)} style={{ width: "100%" }}>
                Remove
              </Button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
