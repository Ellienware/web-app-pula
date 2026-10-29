import { ApiError, getToken } from "./api"

export const MAX_IMAGE_BYTES = 2 * 1024 * 1024
export const ACCEPTED_IMAGE_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
]

interface ImageUploadResponse {
  url?: string
  message?: string
}

async function uploadToEndpoint(
  file: File,
  endpoint: string,
): Promise<string> {
  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) {
    throw new ApiError(0, "Use a JPEG, PNG or WebP image")
  }

  if (file.size === 0) {
    throw new ApiError(0, "Choose an image that is not empty")
  }

  if (file.size > MAX_IMAGE_BYTES) {
    throw new ApiError(0, "That image is over 2 MB. Choose a smaller one")
  }

  const body = new FormData()
  body.append("file", file)

  const token = getToken()
  let res: Response

  try {
    res = await fetch(endpoint, {
      method: "POST",
      body,
      headers: token
        ? { Authorization: `Bearer ${token}` }
        : undefined,
    })
  } catch {
    throw new ApiError(
      0,
      "Could not reach PULA to upload the image. Check your connection and try again",
    )
  }

  const text = await res.text()
  let json: ImageUploadResponse | null = null

  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = null
  }

  if (!res.ok) {
    throw new ApiError(
      res.status,
      json?.message || "Image upload failed",
      undefined,
    )
  }

  if (!json?.url) {
    throw new ApiError(res.status, "Unexpected response from server")
  }

  return json.url
}

export function uploadImage(file: File): Promise<string> {
  return uploadToEndpoint(file, "/api/images")
}

export function uploadBusinessLogo(file: File): Promise<string> {
  return uploadToEndpoint(file, "/api/images/business-logo")
}
