import { useEffect, useState } from "react"
import { api, ApiError } from "../../lib/api"
import { Button } from "../ui/Button"
import {
  BUSINESS_DOCUMENT_TYPE_LABELS,
  type BusinessDocument,
  type BusinessDocumentType,
  type UploadBusinessDocumentRequest,
} from "../../lib/types"

const DOCUMENT_TYPES = Object.keys(BUSINESS_DOCUMENT_TYPE_LABELS) as BusinessDocumentType[]

/**
 * Supporting documents (ID, proof of address, CIPC certificate, etc.) —
 * brief §7-8. There's no in-app file upload in this slice, so the owner
 * links to a file they've already hosted elsewhere rather than uploading
 * one here; each entry is just metadata (type + link + optional note).
 * Owner-only: never shown to buyers or to PULA AI.
 */
export default function BusinessDocumentsPanel() {
  const [documents, setDocuments] = useState<BusinessDocument[]>([])
  const [loading, setLoading] = useState(true)
  const [documentType, setDocumentType] = useState<BusinessDocumentType>("ID_DOCUMENT")
  const [fileUrl, setFileUrl] = useState("")
  const [note, setNote] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function load() {
    setLoading(true)
    api
      .get<BusinessDocument[]>("/businesses/me/documents")
      .then(setDocuments)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load your documents"))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  async function upload() {
    if (!fileUrl.trim()) {
      setError("Add a link to the document first")
      return
    }
    setBusy(true)
    setError(null)
    try {
      const req: UploadBusinessDocumentRequest = { documentType, fileUrl: fileUrl.trim(), note: note.trim() || null }
      await api.post<BusinessDocument>("/businesses/me/documents", req)
      setFileUrl("")
      setNote("")
      load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not add that document")
    } finally {
      setBusy(false)
    }
  }

  async function remove(id: string) {
    setBusy(true)
    try {
      await api.del(`/businesses/me/documents/${id}`)
      setDocuments((current) => current.filter((d) => d.id !== id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not remove that document")
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card stack" style={{ maxWidth: 480 }}>
      <h2>Supporting documents</h2>
      <p className="muted" style={{ fontSize: "0.85rem" }}>
        Link documents you've already got stored somewhere (e.g. a photo in Google Drive) — ID, proof of address, a
        CIPC certificate if you have one. These stay private to you.
      </p>

      {loading ? (
        <p className="muted">Loading…</p>
      ) : documents.length === 0 ? (
        <p className="muted">No documents added yet.</p>
      ) : (
        <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "grid", gap: "0.5rem" }}>
          {documents.map((doc) => (
            <li key={doc.id} className="field-row" style={{ alignItems: "center", justifyContent: "space-between" }}>
              <div>
                <strong>{BUSINESS_DOCUMENT_TYPE_LABELS[doc.documentType]}</strong>
                <br />
                <a href={doc.fileUrl} target="_blank" rel="noreferrer" style={{ fontSize: "0.85rem" }}>
                  {doc.fileUrl}
                </a>
                {doc.note && (
                  <>
                    <br />
                    <span className="muted" style={{ fontSize: "0.8rem" }}>{doc.note}</span>
                  </>
                )}
              </div>
              <Button variant="danger" onClick={() => remove(doc.id)} disabled={busy}>
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}

      <label className="field">
        <span>Document type</span>
        <select value={documentType} onChange={(e) => setDocumentType(e.target.value as BusinessDocumentType)}>
          {DOCUMENT_TYPES.map((t) => (
            <option key={t} value={t}>
              {BUSINESS_DOCUMENT_TYPE_LABELS[t]}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Link to the document</span>
        <input
          type="url"
          placeholder="https://…"
          value={fileUrl}
          onChange={(e) => setFileUrl(e.target.value)}
        />
      </label>
      <label className="field">
        <span>Note (optional)</span>
        <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Municipal account, March" />
      </label>

      {error && <p className="form-error">{error}</p>}
      <Button onClick={upload} disabled={busy}>
        {busy ? "Adding…" : "Add document"}
      </Button>
    </div>
  )
}
