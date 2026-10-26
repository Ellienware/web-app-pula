import { useEffect, useState, type FormEvent } from "react"
import { api } from "../../lib/api"
import type { CanonicalProduct, MatchClassification, Page, Product, ProductStatus } from "../../lib/types"
import { Modal } from "../Modal"
import { ImageField } from "../ui/ImageField"

interface CanonicalSuggestion {
  canonicalProduct: CanonicalProduct
  score: number
}

/**
 * Only shown when editing an existing product — linking makes sense only
 * once a product exists. Deliberately never auto-links: every suggestion
 * requires the seller to explicitly click it and choose EXACT_MATCH or
 * SIMILAR_ALTERNATIVE. See ProductService.linkCanonicalProduct's own
 * javadoc for why this stays a seller decision, never an automatic one.
 */
function CanonicalLinkSection({ product, onLinked }: { product: Product; onLinked: (p: Product) => void }) {
  const [query, setQuery] = useState("")
  const [searchResults, setSearchResults] = useState<CanonicalProduct[]>([])
  const [suggestions, setSuggestions] = useState<CanonicalSuggestion[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .get<CanonicalSuggestion[]>(`/products/${product.id}/canonical-suggestions`)
      .then(setSuggestions)
      .catch(() => setSuggestions([]))
  }, [product.id])

  useEffect(() => {
    if (!query.trim()) {
      setSearchResults([])
      return
    }
    const timeout = setTimeout(() => {
      api
        .get<Page<CanonicalProduct>>(`/canonical-products?q=${encodeURIComponent(query)}&size=6`)
        .then((page) => setSearchResults(page.content))
        .catch(() => setSearchResults([]))
    }, 250)
    return () => clearTimeout(timeout)
  }, [query])

  async function link(canonicalProductId: string, classification: MatchClassification) {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.put<Product>(`/products/${product.id}/canonical-link`, {
        canonicalProductId,
        classification,
      })
      onLinked(updated)
      setQuery("")
      setSearchResults([])
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not link this product")
    } finally {
      setBusy(false)
    }
  }

  async function unlink() {
    setBusy(true)
    try {
      const updated = await api.put<Product>(`/products/${product.id}/canonical-link`, { canonicalProductId: null })
      onLinked(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not unlink")
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="field" style={{ borderTop: "1px solid var(--border, #e5e7eb)", paddingTop: "0.75rem", marginTop: "0.5rem" }}>
      <span>Price comparison</span>
      {product.canonicalProductId ? (
        <div className="row" style={{ alignItems: "center", gap: 8 }}>
          <span className="badge">{product.matchClassification === "EXACT_MATCH" ? "Exact match" : "Similar alternative"}</span>
          <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={unlink}>
            Unlink
          </button>
        </div>
      ) : (
        <>
          <p className="muted" style={{ fontSize: "0.8rem", margin: "0.25rem 0" }}>
            Link this listing to a canonical product so buyers can compare its price with other sellers.
          </p>
          {suggestions.length > 0 && (
            <div className="stack" style={{ gap: 4, marginBottom: 8 }}>
              <span className="muted" style={{ fontSize: "0.75rem" }}>Suggested:</span>
              {suggestions.map((s) => (
                <div key={s.canonicalProduct.id} className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
                  <span style={{ fontSize: "0.85rem" }}>{s.canonicalProduct.name}</span>
                  <div className="row" style={{ gap: 4 }}>
                    <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
                      onClick={() => link(s.canonicalProduct.id, "EXACT_MATCH")}>
                      Exact match
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
                      onClick={() => link(s.canonicalProduct.id, "SIMILAR_ALTERNATIVE")}>
                      Similar
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
          <input
            placeholder="Or search for a product…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {searchResults.map((r) => (
            <div key={r.id} className="row" style={{ justifyContent: "space-between", alignItems: "center", marginTop: 4 }}>
              <span style={{ fontSize: "0.85rem" }}>{r.name}</span>
              <div className="row" style={{ gap: 4 }}>
                <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={() => link(r.id, "EXACT_MATCH")}>
                  Exact match
                </button>
                <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={() => link(r.id, "SIMILAR_ALTERNATIVE")}>
                  Similar
                </button>
              </div>
            </div>
          ))}
        </>
      )}
      {error && <p className="form-error">{error}</p>}
    </div>
  )
}

export function ProductFormModal({
  product,
  onClose,
  onSaved,
  publishOnCreate = false,
}: {
  /** Pass an existing product to edit it; omit to create a new one. */
  product?: Product
  onClose: () => void
  onSaved: () => void
  /** New products are saved as DRAFT by the API; when true the form then makes the new product ACTIVE so customers see it. */
  publishOnCreate?: boolean
}) {
  const [name, setName] = useState(product?.name ?? "")
  const [category, setCategory] = useState(product?.category ?? "")
  const [description, setDescription] = useState(product?.description ?? "")
  const [price, setPrice] = useState(product ? String(product.price) : "")
  const [isService, setIsService] = useState(product?.isService ?? false)
  const [stock, setStock] = useState(product ? String(product.stock) : "0")
  const [imageUrls, setImageUrls] = useState<string[]>(product?.imageUrls ?? [])
  const [linkedProduct, setLinkedProduct] = useState(product)
  const [status, setStatus] = useState<ProductStatus>(product?.status ?? "ACTIVE")
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (product) {
        await api.put<Product>(`/products/${product.id}`, {
          name,
          category,
          description: description || null,
          imageUrls,
          price: Number(price),
          stock: isService ? undefined : Number(stock),
          status,
        })
      } else {
        const created = await api.post<Product>("/products", {
          name,
          category,
          description: description || null,
          imageUrls,
          price: Number(price),
          isService,
          stock: isService ? 0 : Number(stock),
        })
        if (publishOnCreate) await api.put<Product>(`/products/${created.id}`, { status: "ACTIVE" })
      }
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save product")
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={product ? "Edit listing" : "New listing"} onClose={onClose}>
      <form className="modal-form" onSubmit={handleSubmit}>
        <label className="field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} required placeholder="Jollof rice (tray)" />
        </label>
        <label className="field">
          <span>Category</span>
          <input value={category} onChange={(e) => setCategory(e.target.value)} required placeholder="Food" />
        </label>
        <label className="field">
          <span>Description</span>
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
        </label>
        <ImageField value={imageUrls} onChange={setImageUrls} />
        <div className="field-row">
          <label className="field">
            <span>Price</span>
            <input
              type="number"
              min="0"
              step="0.01"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              required
            />
          </label>
          {!isService && (
            <label className="field">
              <span>Stock</span>
              <input type="number" min="0" value={stock} onChange={(e) => setStock(e.target.value)} />
            </label>
          )}
        </div>
        {!product && (
          <label className="field-check">
            <input type="checkbox" checked={isService} onChange={(e) => setIsService(e.target.checked)} />
            <span>This is a service, not a physical product (no stock tracking)</span>
          </label>
        )}
        {product && (
          <label className="field">
            <span>Status</span>
            <select value={status} onChange={(e) => setStatus(e.target.value as ProductStatus)}>
              <option value="DRAFT">Draft</option>
              <option value="ACTIVE">Active</option>
              <option value="ARCHIVED">Archived</option>
            </select>
          </label>
        )}
        {linkedProduct && <CanonicalLinkSection product={linkedProduct} onLinked={setLinkedProduct} />}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={busy}>
            {busy ? "Saving…" : product ? "Save changes" : "Create listing"}
          </button>
        </div>
      </form>
    </Modal>
  )
}
