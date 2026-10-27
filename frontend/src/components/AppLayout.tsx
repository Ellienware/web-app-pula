import { useEffect, useState, type ReactNode } from "react"
import { Link, NavLink, useLocation } from "react-router-dom"
import {
  Home,
  HelpCircle,
  LogOut,
  RotateCcw,
  ShieldCheck,
  MessageCircleMore,
  PiggyBank,
  Wrench,
  ReceiptText,
  ShoppingBag,
  ShoppingCart,
  Store,
  Car,
  Sprout,
  TrendingUp,
  Wallet,
} from "lucide-react"
import { useAuth } from "../context/AuthContext"
import { useLanguage } from "../context/LanguageContext"
import { useMarket } from "../context/MarketContext"
import { api } from "../lib/api"
import { initials } from "../lib/format"
import { translatedLanguages, type TranslationKey } from "../lib/i18n/translations"
import type { AppNotification, Page } from "../lib/types"
import { AssistantPanel } from "./AssistantPanel"
import { OfflineBanner } from "./OfflineBanner"

// Home doubles as the "Circle" hub (it lists and summarizes the user's circles),
// so it isn't duplicated as its own nav item — these four map 1:1 to real routes.
const NAV_ITEMS: { to: string; labelKey: TranslationKey; icon: typeof Home; end?: boolean }[] = [
  { to: "/", labelKey: "nav.home", icon: Home, end: true },
  { to: "/market", labelKey: "nav.market", icon: Store },
  { to: "/momoride", labelKey: "nav.ride", icon: Car },
  { to: "/cashflow", labelKey: "nav.cashflow", icon: TrendingUp },
  { to: "/future", labelKey: "nav.future", icon: Sprout },
  { to: "/money", labelKey: "nav.money", icon: Wallet },
  { to: "/notifications", labelKey: "nav.activity", icon: ReceiptText },
]

export function AppLayout({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth()
  const { t, language, setLanguage } = useLanguage()
  const { cartCount } = useMarket()
  const [assistantOpen, setAssistantOpen] = useState(false)
  const [unreadCount, setUnreadCount] = useState(0)
  const location = useLocation()
  // The assistant needs to know which circle/product (if any) is currently open, so a
  // confirmed CONTRIBUTE/ADD_MEMBER/... or ADD_TO_CART action has a target to act on.
  const circleMatch = location.pathname.match(/^\/circles\/([^/]+)/)
  const currentCircleId = circleMatch ? circleMatch[1] : null
  const productMatch = location.pathname.match(/^\/market\/products\/([^/]+)/)
  const currentProductId = productMatch ? productMatch[1] : null

  useEffect(() => {
    if (!user) return
    api
      .get<Page<AppNotification>>("/notifications?size=20")
      .then((page) => setUnreadCount(page.content.filter((n) => !n.read).length))
      .catch(() => setUnreadCount(0))
  }, [user, location.pathname])

  return (
    <div className="app-shell">
      <header className="topbar">
        <Link to="/" className="brand" aria-label="PULA home">
          <img
            className="brand-logo"
            src="/brand/pula-logo-wordmark.png"
            alt="PULA — Save. Shop. Move. Grow."
          />
        </Link>

        <nav className="primary-nav-desktop" aria-label="Primary">
          {NAV_ITEMS.map(({ to, labelKey, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} className={({ isActive }) => (isActive ? "active" : undefined)}>
              <Icon size={16} aria-hidden="true" />
              {t(labelKey)}
              {labelKey === "nav.activity" && unreadCount > 0 && <span className="nav-dot" aria-hidden="true" />}
            </NavLink>
          ))}
        </nav>

        <div className="topbar-actions">
          <select
            aria-label={t("language.choose")}
            value={language}
            onChange={(e) => setLanguage(e.target.value as typeof language)}
            className="btn btn-ghost btn-sm"
            style={{ paddingRight: "1.5rem" }}
          >
            {translatedLanguages().map((l) => (
              <option key={l.code} value={l.code}>
                {l.nativeLabel}
              </option>
            ))}
          </select>
          <NavLink to="/cart" className="icon-link" aria-label={`Cart, ${cartCount} item${cartCount === 1 ? "" : "s"}`}>
            <ShoppingCart size={18} aria-hidden="true" />
            {cartCount > 0 && <span className="cart-badge">{cartCount}</span>}
          </NavLink>
          <button className="btn btn-secondary btn-sm assistant-trigger" onClick={() => setAssistantOpen(true)}>
            <MessageCircleMore size={16} aria-hidden="true" />
            <span className="assistant-trigger-label">PULA AI</span>
          </button>
          <Link to="/profile" className="user-chip" title={`${user?.email ?? ""} - profile and settings`} aria-label="Profile and settings">
            <span className="avatar avatar-md" aria-hidden="true">
              {initials(user?.fullName ?? "")}
            </span>
            <span className="user-name">{user?.fullName}</span>
          </Link>
          <button className="btn btn-ghost btn-sm" onClick={logout} aria-label="Sign out">
            <LogOut size={16} aria-hidden="true" />
          </button>
        </div>
      </header>
      <OfflineBanner />

      {/* Secondary utility row — kept for existing deep links (Orders/Sell) that don't fit
          the primary four-item nav without crowding it. */}
      <nav className="secondary-nav" aria-label="More">
        <div className="secondary-nav-inner">
          <NavLink to="/orders" className={({ isActive }) => (isActive ? "active" : undefined)}>
            <ShoppingBag size={14} aria-hidden="true" /> Orders
          </NavLink>
          <NavLink to="/services" className={({ isActive }) => (isActive ? "active" : undefined)}>
            <Wrench size={14} aria-hidden="true" /> Services
          </NavLink>
          <NavLink to="/save-to-buy" className={({ isActive }) => (isActive ? "active" : undefined)}>
            <PiggyBank size={14} aria-hidden="true" /> Save-to-Buy
          </NavLink>
          <NavLink to="/sell" className={({ isActive }) => (isActive ? "active" : undefined)}>
            <Store size={14} aria-hidden="true" /> Sell on Market
          </NavLink>
          <NavLink to="/market/stock/bulk" className={({ isActive }) => (isActive ? "active" : undefined)}>
            <ShoppingBag size={14} aria-hidden="true" /> Bulk Buying
          </NavLink>
          <NavLink to="/refunds" className={({ isActive }) => (isActive ? "active" : undefined)}>
            <RotateCcw size={14} aria-hidden="true" /> Refunds
          </NavLink>
          <NavLink to="/help" className={({ isActive }) => (isActive ? "active" : undefined)}>
            <HelpCircle size={14} aria-hidden="true" /> Help
          </NavLink>
          {user?.role === "ADMIN" && (
            <NavLink to="/admin" className={({ isActive }) => (isActive ? "active" : undefined)}>
              <ShieldCheck size={14} aria-hidden="true" /> Administration
            </NavLink>
          )}
        </div>
      </nav>

      <main className="content">{children}</main>

      {/* Compact mobile navigation — replaces the desktop nav row under ~640px. */}
      <nav className="mobile-tabbar" aria-label="Primary">
        {NAV_ITEMS.map(({ to, labelKey, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => (isActive ? "active" : undefined)}>
            <span className="mobile-tabbar-icon">
              <Icon size={22} aria-hidden="true" />
              {labelKey === "nav.activity" && unreadCount > 0 && <span className="nav-dot" aria-hidden="true" />}
            </span>
            {t(labelKey)}
          </NavLink>
        ))}
      </nav>

      <button className="assistant-fab" onClick={() => setAssistantOpen(true)} aria-label="PULA AI assistant">
        <MessageCircleMore size={22} aria-hidden="true" />
      </button>

      {assistantOpen && (
        <AssistantPanel
          onClose={() => setAssistantOpen(false)}
          circleId={currentCircleId}
          productId={currentProductId}
        />
      )}
    </div>
  )
}
