import { Link } from "react-router-dom"
import { ShieldAlert } from "lucide-react"
import { PageHeader } from "../components/ui/PageHeader"
import { FAQ, SCAM_TIPS } from "../lib/guideContent"
import { hasSupportContact, parseSupportContact } from "../lib/support"

const env = (import.meta as unknown as { env?: Record<string, string | undefined> }).env ?? {}
const CONTACT = parseSupportContact(env.VITE_SUPPORT_EMAIL, env.VITE_SUPPORT_PHONE)

/** Help centre: answers, scam tips, and a contact line that only appears when one has really been configured. */
export default function HelpPage() {
  return (
    <div className="page stack">
      <PageHeader title="Help" subtitle="Plain answers about how PULA works." />

      <div className="demo-notice" role="note">
        <ShieldAlert size={18} aria-hidden="true" />
        <div><strong>Demo money only.</strong> PULA is not a bank. Payments, balances, refunds and payouts are simulated, and nothing moves in a real account.</div>
      </div>

      <section className="card stack" aria-labelledby="faq-h">
        <h2 id="faq-h">Common questions</h2>
        <div className="help-faq">
          {FAQ.map((f) => (
            <details key={f.id}>
              <summary>{f.question}</summary>
              <p>{f.answer}</p>
            </details>
          ))}
        </div>
      </section>

      <section className="card stack" aria-labelledby="scam-h">
        <h2 id="scam-h">Spotting scams</h2>
        <ul>{SCAM_TIPS.map((t) => <li key={t.id}>{t.tip}</li>)}</ul>
        <Link to="/money" className="btn btn-secondary btn-sm">Check a message in ScamShield</Link>
      </section>

      <section className="card stack" aria-labelledby="links-h">
        <h2 id="links-h">Looking for something?</h2>
        <div className="row wrap">
          <Link to="/refunds" className="btn btn-ghost btn-sm">Refunds</Link>
          <Link to="/momoride/history" className="btn btn-ghost btn-sm">Ride history and receipts</Link>
          <Link to="/notifications" className="btn btn-ghost btn-sm">Notifications</Link>
          <Link to="/profile" className="btn btn-ghost btn-sm">Profile and settings</Link>
        </div>
      </section>

      <section className="card stack" aria-labelledby="contact-h">
        <h2 id="contact-h">Contact</h2>
        {hasSupportContact(CONTACT) ? (
          <ul>
            {CONTACT.email && <li>Email: <a href={`mailto:${CONTACT.email}`}>{CONTACT.email}</a></li>}
            {CONTACT.phone && <li>Phone: <a href={`tel:${CONTACT.phone.replace(/[^+0-9]/g, "")}`}>{CONTACT.phone}</a></li>}
          </ul>
        ) : (
          <p className="text-muted-aa">Support contact details have not been set up for this version of PULA yet. Please check the questions above.</p>
        )}
      </section>
    </div>
  )
}
