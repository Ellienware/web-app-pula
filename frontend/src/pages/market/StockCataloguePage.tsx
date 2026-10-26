import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import type { Page, WholesaleProduct, CreateWholesaleProductRequest } from "../../lib/types"
import { formatMoney } from "../../lib/format"
import { useCurrencyConfig } from "../../lib/currencyConfig"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
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
  const [formBusy, setFormBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  function load() {
    setLoading(true)
    api.get<Page<WholesaleProduct>>("/stock/products/mine")
      .then((res) => { setProducts(res.content); setError(null) })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  async function createProduct() {
    if (!name.trim() || !pricePerPack) { setFormError("Name and price are required"); return }
    setFormBusy(true); setFormError(null)
    try {
      const req: CreateWholesaleProductRequest = {
        name: name.trim(), sku: sku.trim() || null, category: category.trim() || null,
        packSize: Number(packSize), moq: Number(moq),
        pricePerPack: Number(pricePerPack), availableStock: Number(availableStock),
      }
      await api.post<WholesaleProduct>("/stock/products", req)
      setName(""); setSku(""); setCategory(""); setPackSize("1"); setMoq("1")
      setPricePerPack(""); setAvailableStock("0")
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
          <div className="field-row">
            <label className="field"><span>Pack size (units/pack)</span>
              <input type="number" min="1" value={packSize} onChange={(e) => setPackSize(e.target.value)} />
            </label>
            <label className="field"><span>MOQ (min packs)</span>
              <input type="number" min="1" value={moq} onChange={(e) => setMoq(e.target.value)} />
            </label>
          </div>
          <div className="field-row">
            <label className="field"><span>Price per pack (R)</span>
              <input type="number" min="0" step="0.01" value={pricePerPack} onChange={(e) => setPricePerPack(e.target.value)} />
            </label>
            <label className="field"><span>Available stock (packs)</span>
              <input type="number" min="0" value={availableStock} onChange={(e) => setAvailableStock(e.target.value)} />
            </label>
          </div>
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
            <div className="product-card-body">
              <h3 className="product-card-name">{p.name}</h3>
              {p.sku && <p className="muted" style={{ fontSize: "0.75rem" }}>SKU: {p.sku}</p>}
              <div className="row wrap" style={{ gap: 4, marginBottom: 8 }}>
                <span className="badge">{formatMoney(p.pricePerPack, currency)}/pack</span>
                <span className="badge badge-muted">{p.packSize} units</span>
                <span className="badge badge-muted">MOQ {p.moq}</span>
                <span className="badge badge-muted">{p.availableStock} packs left</span>
              </div>
              <VolumeTiersPanel productId={p.id} basePrice={p.pricePerPack} currency={currency} />
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
