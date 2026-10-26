import { useState } from "react"
import { ACCEPTED_IMAGE_TYPES, uploadImage } from "../../lib/imageApi"

/**
 * Picks images for a product, dish or service: choose a file from this computer or phone (uploaded to PULA),
 * or paste an https image link. `value` is the list of image URLs that will be saved with the record.
 */
export function ImageField({ value, onChange, max = 4 }: { value: string[]; onChange: (urls: string[]) => void; max?: number }) {
  const [link, setLink] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function onFile(file: File | undefined) {
    if (!file) return
    setBusy(true); setError(null)
    try { onChange([...value, await uploadImage(file)].slice(0, max)) }
    catch (e) { setError(e instanceof Error ? e.message : "Image upload failed") }
    finally { setBusy(false) }
  }

  function addLink() {
    const v = link.trim()
    if (!v) return
    if (!/^https:\/\/\S+$/i.test(v)) { setError("Image links must start with https://"); return }
    setError(null); onChange([...value, v].slice(0, max)); setLink("")
  }

  return (
    <div className="field image-field">
      <span>Image</span>
      {value.length > 0 && (
        <div className="image-field-thumbs">
          {value.map((u, i) => (
            <div key={u + i} className="image-field-thumb">
              <img src={u} alt="" />
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange(value.filter((_, j) => j !== i))} aria-label="Remove image">Remove</button>
            </div>
          ))}
        </div>
      )}
      {value.length < max && (
        <>
          <input type="file" accept={ACCEPTED_IMAGE_TYPES.join(",")} disabled={busy} aria-label="Choose an image from this device"
            onChange={(e) => { void onFile(e.target.files?.[0]); e.target.value = "" }} />
          <div className="row" style={{ gap: 8 }}>
            <input value={link} onChange={(e) => setLink(e.target.value)} placeholder="…or paste an https:// image link" aria-label="Image link" />
            <button type="button" className="btn btn-ghost btn-sm" onClick={addLink} disabled={!link.trim()}>Add link</button>
          </div>
        </>
      )}
      <p className="muted" style={{ fontSize: "0.78rem", margin: 0 }}>JPEG, PNG or WebP, up to 2 MB.</p>
      {busy && <p className="muted" style={{ margin: 0 }}>Uploading…</p>}
      {error && <p className="form-error" role="alert" style={{ margin: 0 }}>{error}</p>}
    </div>
  )
}
