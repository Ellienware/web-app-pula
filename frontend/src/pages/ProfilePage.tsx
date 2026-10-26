import { Link, useNavigate } from "react-router-dom"
import { useAuth } from "../context/AuthContext"
import { useLanguage } from "../context/LanguageContext"
import { PageHeader } from "../components/ui/PageHeader"
import { Button } from "../components/ui/Button"
import { translatedLanguages } from "../lib/i18n/translations"

/** Account and settings in one place. Read-only for now: there is no API to edit profile details yet. */
export default function ProfilePage() {
  const { user, logout } = useAuth()
  const { language, setLanguage } = useLanguage()
  const navigate = useNavigate()
  if (!user) return null
  const langs = translatedLanguages()

  return (
    <div className="page stack">
      <PageHeader title="Profile and settings" subtitle="Your account, language and shortcuts." />

      <section className="card stack" aria-labelledby="acct-h">
        <h2 id="acct-h">Account</h2>
        <dl className="stack">
          <div><dt className="muted">Name</dt><dd>{user.fullName}</dd></div>
          <div><dt className="muted">Email</dt><dd>{user.email}</dd></div>
          <div><dt className="muted">Phone</dt><dd>{user.phoneNumber || "Not set"}</dd></div>
        </dl>
        <p className="text-muted-aa">Editing these details isn't available yet.</p>
      </section>

      <section className="card stack" aria-labelledby="lang-h">
        <h2 id="lang-h">Language</h2>
        <label htmlFor="profile-lang" className="muted">Screen language</label>
        <select id="profile-lang" value={language} onChange={(e) => setLanguage(e.target.value as typeof language)}>
          {langs.map((l) => <option key={l.code} value={l.code}>{l.nativeLabel}</option>)}
        </select>
        <p className="text-muted-aa">Only languages with translated screens are listed. Money, legal and safety wording stays in English until it has been reviewed by a fluent speaker.</p>
      </section>

      <section className="card stack" aria-labelledby="short-h">
        <h2 id="short-h">Shortcuts</h2>
        <div className="row wrap">
          <Link to="/refunds" className="btn btn-ghost btn-sm">Refunds</Link>
          <Link to="/momoride/history" className="btn btn-ghost btn-sm">Ride history and receipts</Link>
          <Link to="/notifications" className="btn btn-ghost btn-sm">Notifications</Link>
          <Link to="/sell" className="btn btn-ghost btn-sm">My business</Link>
          <Link to="/help" className="btn btn-ghost btn-sm">Help</Link>
          {user.role === "ADMIN" && <Link to="/admin" className="btn btn-ghost btn-sm">Administration</Link>}
        </div>
      </section>

      <section className="card stack" aria-labelledby="sess-h">
        <h2 id="sess-h">Session</h2>
        <p className="text-muted-aa">Signing out removes your session from this browser.</p>
        <div><Button variant="secondary" onClick={() => { logout(); navigate("/") }}>Sign out</Button></div>
      </section>
    </div>
  )
}
