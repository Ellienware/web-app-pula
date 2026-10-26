import { useCallback, useEffect, useMemo, useState } from "react"
import { Link, useParams } from "react-router-dom"
import { businessApi, businessScope, walletApi, type WalletScope } from "../lib/walletApi"
import type { BusinessAccess, BusinessOverview, WalletOverview } from "../lib/walletTypes"
import { roleDescription, visibleTabs, type WalletTab } from "../lib/walletLogic"
import { PageHeader } from "../components/ui/PageHeader"
import { Card } from "../components/ui/Card"
import { EmptyState, ErrorState } from "../components/ui/StatePanel"
import { SkeletonCard } from "../components/ui/Skeleton"
import { SimulatedBanner } from "../components/wallet/SimulatedBanner"
import { WalletSummary } from "../components/wallet/WalletSummary"
import { SendFlow } from "../components/wallet/SendFlow"
import { RequestFlow } from "../components/wallet/RequestFlow"
import { PayFlow } from "../components/wallet/PayFlow"
import { ActivityList } from "../components/wallet/ActivityList"
import { MembersPanel } from "../components/wallet/MembersPanel"
import "../components/wallet/wallet.css"

const LABELS: Record<WalletTab, string> = {
  wallet: "Wallet", send: "Send", request: "Request", pay: "Pay a code", activity: "Activity", members: "People",
}

/**
 * One wallet screen for a personal OR a business wallet. The scope decides which API is used; the tabs shown follow the
 * permissions the server granted (the server still enforces each one). Everything here is simulated demo funds.
 */
function WalletShell({ scope, title, subtitle, walletIdForMembers, back }: {
  scope: WalletScope; title: string; subtitle: string; walletIdForMembers?: string; back: { to: string; label: string }
}) {
  const [overview, setOverview] = useState<WalletOverview | null>(null)
  const [state, setState] = useState<"loading" | "ok" | "error">("loading")
  const [tab, setTab] = useState<WalletTab>("wallet")
  const [version, setVersion] = useState(0)
  const [codeToReview, setCodeToReview] = useState<string | null>(null)

  const load = useCallback(() => {
    scope.overview().then((o) => { setOverview(o); setState("ok") }).catch(() => setState((s) => (s === "ok" ? s : "error")))
  }, [scope])
  useEffect(load, [load])

  const changed = useCallback(() => { load(); setVersion((v) => v + 1) }, [load])
  const tabs = visibleTabs(scope.kind, scope.permissions)

  return (
    <div>
      <PageHeader eyebrow={scope.kind === "business" ? "PULA Money · Business" : "PULA Money"} title={title} subtitle={subtitle}
        actions={<Link className="btn btn-ghost btn-sm" to={back.to}>{back.label}</Link>} />
      <SimulatedBanner />
      {state === "loading" && <SkeletonCard />}
      {state === "error" && <ErrorState title="Could not load this wallet" description="Nothing was changed." onRetry={() => { setState("loading"); load() }} />}
      {state === "ok" && overview && (
        <>
          <div className="wallet-tabs" role="tablist" aria-label="Wallet sections">
            {tabs.map((t) => (
              <button key={t} role="tab" id={`wtab-${t}`} aria-selected={tab === t} aria-controls="wallet-panel"
                className={`wallet-tab ${tab === t ? "is-active" : ""}`} onClick={() => setTab(t)}>{LABELS[t]}</button>
            ))}
            {scope.kind === "personal" && <Link className="wallet-tab" to="/money/wallet/business">Business wallets</Link>}
          </div>
          <div id="wallet-panel" role="tabpanel" aria-labelledby={`wtab-${tab}`}>
            {tab === "wallet" && <><WalletSummary overview={overview} onChanged={changed} scope={scope} /><ActivityList version={version} onChanged={changed} scope={scope} /></>}
            {tab === "send" && <SendFlow overview={overview} onChanged={changed} scope={scope} />}
            {tab === "request" && <RequestFlow overview={overview} scope={scope} onOpenCode={scope.kind === "personal" ? (c) => { setCodeToReview(c); setTab("pay") } : undefined} />}
            {tab === "pay" && <PayFlow initialCode={codeToReview} onChanged={changed} />}
            {tab === "activity" && <ActivityList version={version} onChanged={changed} scope={scope} />}
            {tab === "members" && walletIdForMembers && <MembersPanel walletId={walletIdForMembers} />}
          </div>
        </>
      )}
    </div>
  )
}

/** PULA Money demo wallet (route /money/wallet): the user's own wallet. */
export function WalletPage() {
  return <WalletShell scope={walletApi} title="Demo wallet" subtitle="Simulated funds for trying out sending, requesting and paying inside PULA."
    back={{ to: "/money", label: "Back to money tools" }} />
}

/** A business wallet the signed-in user owns or has been given access to (route /money/wallet/business/:walletId). */
export function BusinessWalletPage() {
  const { walletId = "" } = useParams()
  const [info, setInfo] = useState<BusinessOverview | null>(null)
  const [state, setState] = useState<"loading" | "ok" | "error">("loading")

  useEffect(() => {
    setState("loading")
    businessApi.overview(walletId).then((o) => { setInfo(o); setState("ok") }).catch(() => setState("error"))
  }, [walletId])

  const scope = useMemo(() => (info ? businessScope(walletId, info.permissions) : null), [walletId, info])

  if (state === "loading") return <div><SimulatedBanner /><SkeletonCard /></div>
  if (state === "error" || !info || !scope) {
    return <div><SimulatedBanner /><ErrorState title="Business wallet not found" description="It may not exist, or you may not have access to it." /></div>
  }
  return <WalletShell scope={scope} title={info.businessName} subtitle={roleDescription(info.role)} walletIdForMembers={walletId}
    back={{ to: "/money/wallet/business", label: "All business wallets" }} />
}

/** Lists the business wallets the user can act on (route /money/wallet/business). */
export function BusinessWalletsPage() {
  const [list, setList] = useState<BusinessAccess[] | null>(null)
  const [state, setState] = useState<"loading" | "ok" | "error">("loading")
  const load = useCallback(() => {
    setState("loading")
    businessApi.list().then((l) => { setList(l); setState("ok") }).catch(() => setState("error"))
  }, [])
  useEffect(load, [load])

  return (
    <div>
      <PageHeader eyebrow="PULA Money" title="Business wallets" subtitle="Demo wallets for businesses you own or have been given access to."
        actions={<Link className="btn btn-ghost btn-sm" to="/money/wallet">My wallet</Link>} />
      <SimulatedBanner />
      {state === "loading" && <SkeletonCard />}
      {state === "error" && <ErrorState title="Could not load business wallets" onRetry={load} />}
      {state === "ok" && list && list.length === 0 && (
        <EmptyState title="No business wallets" description="Business owners get one automatically; owners can also give you access." />
      )}
      {state === "ok" && list && list.length > 0 && (
        <div className="wallet-stack">
          {list.map((b) => (
            <Card key={b.walletId}>
              <h3>{b.businessName}</h3>
              <p className="field-hint">{roleDescription(b.role)} · <code>{b.walletId}</code></p>
              <Link className="btn btn-secondary btn-sm" to={`/money/wallet/business/${encodeURIComponent(b.walletId)}`}>Open wallet</Link>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
