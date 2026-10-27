import { useEffect, useRef, useState } from "react"
import { Button } from "./ui/Button"
import { ONBOARDING_STEPS } from "../lib/guideContent"
import { clampStep } from "../lib/onboardingState"

/** Three short welcome steps. Explains, before sign-up, that money in PULA is simulated. Skippable at any point. */
export function OnboardingIntro({ onDone }: { onDone: () => void }) {
  const [index, setIndex] = useState(0)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const total = ONBOARDING_STEPS.length
  const step = ONBOARDING_STEPS[clampStep(index, total)]
  const last = index >= total - 1

  useEffect(() => {
    headingRef.current?.focus()
  }, [index])

  return (
    <div className="auth-wrap">
      <div className="auth-panel">
        <section aria-labelledby="onb-title" style={{ maxWidth: 440, margin: "0 auto", textAlign: "center" }}>
          <img className="brand-mark large brand-image-mark" src="/brand/pula-app-icon-1024.png" alt="PULA logo" />
          <p className="muted" aria-live="polite">Step {index + 1} of {total}</p>
          <h1 id="onb-title" tabIndex={-1} ref={headingRef}>{step.title}</h1>
          <p>{step.body}</p>
          <div className="onboarding-steps" aria-hidden="true">
            {ONBOARDING_STEPS.map((s, i) => (
              <span key={s.id} className={`onboarding-dot ${i === index ? "active" : ""}`} />
            ))}
          </div>
          <div className="row wrap" style={{ justifyContent: "center", gap: 8 }}>
            {index > 0 && <Button variant="ghost" onClick={() => setIndex(index - 1)}>Back</Button>}
            {last ? (
              <Button size="lg" onClick={onDone}>Get started</Button>
            ) : (
              <Button size="lg" onClick={() => setIndex(index + 1)}>Next</Button>
            )}
          </div>
          {!last && <button type="button" className="btn btn-ghost btn-sm" onClick={onDone}>Skip</button>}
        </section>
      </div>
    </div>
  )
}
