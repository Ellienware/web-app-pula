import { Component, type ErrorInfo, type ReactNode } from "react"
import { AlertTriangle } from "lucide-react"

interface Props {
  children: ReactNode
}
interface State {
  failed: boolean
}

/**
 * Last line of defence: a render error in any screen shows a recoverable message instead of a blank page.
 * It never shows stack traces or technical detail to the user.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Kept for developers; users only see the friendly panel below.
    console.error("PULA render error:", error, info.componentStack)
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div className="app-loading" role="alert">
        <div className="state-panel is-error">
          <div className="state-panel-icon">
            <AlertTriangle size={22} aria-hidden="true" />
          </div>
          <h2>Something went wrong on this screen</h2>
          <p>Nothing was charged or changed by this error. You can reload, or go back to your home screen.</p>
          <div className="row wrap" style={{ justifyContent: "center" }}>
            <button className="btn btn-primary btn-sm" onClick={() => window.location.reload()}>
              Reload
            </button>
            <a className="btn btn-ghost btn-sm" href="/">
              Go to home
            </a>
          </div>
        </div>
      </div>
    )
  }
}
