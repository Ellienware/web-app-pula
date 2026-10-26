import { createContext, useContext, useEffect, useState, type ReactNode } from "react"
import { translate, type LanguageCode, type TranslationKey } from "../lib/i18n/translations"

interface LanguageState {
  language: LanguageCode
  setLanguage: (lang: LanguageCode) => void
  t: (key: TranslationKey) => string
}

const LanguageContext = createContext<LanguageState | null>(null)

const STORAGE_KEY = "momocircle.language"

function readStoredLanguage(): LanguageCode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === "en" || stored === "zu" || stored === "xh" || stored === "st" || stored === "tn" || stored === "af") {
      return stored
    }
  } catch {
    // localStorage can throw in some browser privacy modes — fall through to the default.
  }
  return "en"
}

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<LanguageCode>(readStoredLanguage)

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, language)
    } catch {
      // Non-fatal — the choice just won't persist across reloads in this browser mode.
    }
  }, [language])

  function setLanguage(lang: LanguageCode) {
    setLanguageState(lang)
  }

  function t(key: TranslationKey) {
    return translate(language, key)
  }

  return <LanguageContext.Provider value={{ language, setLanguage, t }}>{children}</LanguageContext.Provider>
}

export function useLanguage() {
  const ctx = useContext(LanguageContext)
  if (!ctx) throw new Error("useLanguage must be used within a LanguageProvider")
  return ctx
}
