import { ApiError, getToken } from "./api"

/** Client-side mirror of the server rules (the server re-checks everything, including the real file type). */
export const MAX_IMAGE_BYTES = 2 * 1024 * 1024
export const ACCEPTED_IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"]

/**
 * Uploads one image from the user's computer or phone and returns the URL to store on the product / dish / service
 * (a path like /api/images/{id}). Multipart, so it can't go through the JSON-only api helper.
 */
export async function uploadImage(file: File): Promise<string> {
  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) throw new ApiError(0, "Use a JPEG, PNG or WebP image")
  if (file.size > MAX_IMAGE_BYTES) throw new ApiError(0, "That image is over 2 MB. Choose a smaller one")
  const body = new FormData()
  body.append("file", file)
  const token = getToken()
  let res: Response
  try {
    res = await fetch("/api/images", { method: "POST", body, headers: token ? { Authorization: `Bearer ${token}` } : undefined })
  } catch {
    throw new ApiError(0, "Could not reach PULA to upload the image. Check your connection and try again")
  }
  const text = await res.text()
  let json: { url?: string; message?: string } | null = null
  try { json = text ? JSON.parse(text) : null } catch { json = null }
  if (!res.ok) throw new ApiError(res.status, json?.message || "Image upload failed", undefined)
  if (!json?.url) throw new ApiError(res.status, "Unexpected response from server")
  return json.url
}
