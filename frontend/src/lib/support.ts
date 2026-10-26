/**
 * Support contact details come ONLY from build-time configuration. PULA never invents a support address or number; when
 * nothing valid is configured the Help screen says so. Pure module, identical on web and mobile.
 */
export interface SupportContact { email?: string; phone?: string }

const EMAIL = /^[^\s@<>"]+@[^\s@<>"]+\.[^\s@<>"]{2,}$/
const PHONE = /^\+?[0-9][0-9 ()-]{6,18}[0-9]$/

export function parseSupportContact(email?: string | null, phone?: string | null): SupportContact {
  const out: SupportContact = {}
  const e = (email ?? "").trim()
  const p = (phone ?? "").trim()
  if (e && EMAIL.test(e)) out.email = e
  if (p && PHONE.test(p)) out.phone = p
  return out
}

export const hasSupportContact = (c: SupportContact): boolean => Boolean(c.email || c.phone)
