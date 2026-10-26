import type { WholesaleProduct } from "../../lib/types"
import { formatMoney } from "../../lib/format"
import { useCurrencyConfig } from "../../lib/currencyConfig"
import { Button } from "../ui/Button"

interface Props {
  product: WholesaleProduct
  onAddToCart: () => void
}

export default function WholesaleProductCard({ product, onAddToCart }: Props) {
  const { defaultCurrency: currency } = useCurrencyConfig()

  return (
    <div className="product-card">
      <div className="product-card-image product-card-placeholder" aria-hidden="true">
        <span style={{ fontSize: "2rem" }}>📦</span>
      </div>
      <div className="product-card-body">
        <p className="product-card-business">{product.supplierBusinessName}</p>
        <h3 className="product-card-name">{product.name}</h3>
        {product.sku && (
          <p className="muted" style={{ fontSize: "0.75rem", marginBottom: 4 }}>SKU: {product.sku}</p>
        )}
        <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginBottom: 8 }}>
          <span className="badge">{formatMoney(product.pricePerPack, currency)} / pack</span>
          <span className="badge badge-muted">{product.packSize} units/pack</span>
          <span className="badge badge-muted">MOQ: {product.moq} pack{product.moq !== 1 ? "s" : ""}</span>
          {product.availableStock <= 5 && product.availableStock > 0 && (
            <span className="badge" style={{ color: "var(--warning)", borderColor: "var(--warning-soft)", background: "var(--warning-soft)" }}>
              Only {product.availableStock} left
            </span>
          )}
          {product.availableStock === 0 && (
            <span className="badge" style={{ color: "var(--danger)", borderColor: "var(--danger-soft)", background: "var(--danger-soft)" }}>
              Out of stock
            </span>
          )}
        </div>
        {product.category && (
          <p className="muted" style={{ fontSize: "0.78rem", marginBottom: 8 }}>{product.category}</p>
        )}
        <Button
          onClick={onAddToCart}
          disabled={product.availableStock === 0}
          style={{ width: "100%" }}
        >
          Add {product.moq} pack{product.moq !== 1 ? "s" : ""} to stock cart
        </Button>
      </div>
    </div>
  )
}
