import { useEffect, useRef, useState } from "react"
import { extractPaymentCode } from "../../lib/walletLogic"

interface Detector { detect(src: HTMLVideoElement): Promise<{ rawValue?: string }[]> }
type DetectorCtor = new (opts: { formats: string[] }) => Detector

/**
 * Camera QR scanning where the browser offers BarcodeDetector + getUserMedia. It only ever extracts a payment code and hands
 * it to the caller for REVIEW; a scan never pays. Where unsupported or denied it says so and the user pastes the code.
 */
export function QrScanner({ onCode, onClose }: { onCode: (code: string) => void; onClose: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const Ctor = (window as unknown as { BarcodeDetector?: DetectorCtor }).BarcodeDetector
    if (!Ctor || !navigator.mediaDevices?.getUserMedia) {
      setError("This browser cannot scan QR codes. Paste the payment code instead.")
      return
    }
    let stopped = false
    let stream: MediaStream | null = null
    const detector = new Ctor({ formats: ["qr_code"] })

    async function tick() {
      if (stopped || !videoRef.current) return
      try {
        const found = await detector.detect(videoRef.current)
        const code = extractPaymentCode(found[0]?.rawValue)
        if (code) {
          stopped = true
          onCode(code)
          return
        }
      } catch {
        /* a failed frame is fine; try the next one */
      }
      requestAnimationFrame(tick)
    }

    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: "environment" } })
      .then(async (s) => {
        if (stopped) {
          s.getTracks().forEach((t) => t.stop())
          return
        }
        stream = s
        const v = videoRef.current
        if (!v) return
        v.srcObject = s
        await v.play()
        tick()
      })
      .catch(() => setError("Camera unavailable or permission denied. Paste the payment code instead."))

    return () => {
      stopped = true
      stream?.getTracks().forEach((t) => t.stop())
    }
  }, [onCode])

  return (
    <div className="wallet-scanner">
      {error ? (
        <p role="alert" className="field-error">{error}</p>
      ) : (
        <>
          <video ref={videoRef} muted playsInline aria-label="Camera preview for scanning a payment QR code" />
          <p className="field-hint">Scanning only opens the request for review. Nothing is paid until you confirm.</p>
        </>
      )}
      <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>Close scanner</button>
    </div>
  )
}
