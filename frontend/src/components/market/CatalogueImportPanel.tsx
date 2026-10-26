import { useState } from "react"
import { api } from "../../lib/api"
import type { CatalogueImportResult } from "../../lib/types"

const TEMPLATE = "name,sku,category,packSize,moq,pricePerPack,availableStock,description\nWhite Sugar 2kg,SUG-2,Groceries,6,2,120.50,40,Case of 6"

/** Bulk-import a supplier catalogue from a CSV file. Rows are validated one by one; bad rows are reported, good rows are saved. */
export function CatalogueImportPanel({ onDone }: { onDone: () => void }) {
  const [result, setResult] = useState<CatalogueImportResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function onFile(file: File | undefined) {
    if (!file) return
    setBusy(true); setError(null); setResult(null)
    try {
      const csv = await file.text()
      setResult(await api.post<CatalogueImportResult>("/stock/products/import", { csv }))
      onDone()
    } catch (e) { setError(e instanceof Error ? e.message : "Import failed") }
    finally { setBusy(false) }
  }

  function downloadTemplate() {
    const url = URL.createObjectURL(new Blob([TEMPLATE], { type: "text/csv" }))
    const a = document.createElement("a"); a.href = url; a.download = "pula-catalogue-template.csv"; a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="card stack">
      <h3 style={{ margin: 0 }}>Import catalogue (CSV)</h3>
      <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>
        Up to 500 rows. Rows with an existing SKU update that product; others are added.
      </p>
      <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
        <input type="file" accept=".csv,text/csv" disabled={busy} onChange={(e) => onFile(e.target.files?.[0])} aria-label="Choose CSV file" />
        <button className="btn btn-ghost" onClick={downloadTemplate}>Download template</button>
      </div>
      {busy && <p className="muted">Importing…</p>}
      {error && <p style={{ color: "var(--danger)", margin: 0 }}>{error}</p>}
      {result && (
        <div>
          <p style={{ margin: 0 }}>Added {result.created}, updated {result.updated}{result.errors.length ? `, ${result.errors.length} row(s) skipped` : ""}.</p>
          {result.errors.slice(0, 10).map((r) => <p key={r.row} className="muted" style={{ margin: 0, fontSize: "0.82rem" }}>Row {r.row}: {r.message}</p>)}
        </div>
      )}
    </div>
  )
}
