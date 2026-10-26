import { Link, useLocation } from "react-router-dom"
import { SearchX } from "lucide-react"
import { EmptyState } from "../components/ui/StatePanel"
import { Button } from "../components/ui/Button"

/** Shown for any unknown address, instead of silently bouncing the user to the home screen. */
export function NotFoundPage() {
  const { pathname } = useLocation()
  return (
    <div className="page">
      <EmptyState
        icon={<SearchX size={22} aria-hidden="true" />}
        title="We couldn't find that page"
        description={`There is nothing at ${pathname}. The link may be old or mistyped.`}
        action={
          <div className="row wrap" style={{ justifyContent: "center" }}>
            <Link to="/">
              <Button>Go to home</Button>
            </Link>
            <Link to="/help">
              <Button variant="secondary">Get help</Button>
            </Link>
          </div>
        }
      />
    </div>
  )
}
