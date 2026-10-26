/**
 * Stable idempotency key for ONE logical payment attempt (Phase 8).
 *
 * Problem: generating a fresh key per click means that when a response is lost (timeout, dropped connection) the user's
 * retry is a brand-new payment. Rule: reuse the same key while the outcome is UNKNOWN; rotate it as soon as the server
 * has given a definitive answer, because replaying a key after a definitive FAILED would just return that same failure.
 */
export type KeyGenerator = () => string

export class AttemptKey {
  private gen: KeyGenerator
  private sig: string | null = null
  private key: string | null = null

  constructor(gen: KeyGenerator) {
    this.gen = gen
  }

  /** Key for this payment (identified by `signature`, e.g. code|amount|currency). A different signature gets a new key. */
  keyFor(signature: string): string {
    if (this.key === null || this.sig !== signature) {
      this.sig = signature
      this.key = this.gen()
    }
    return this.key
  }

  /** Call when the server returned any definitive outcome (success, failure, or a 4xx rejection). */
  settle(): void {
    this.sig = null
    this.key = null
  }

  /** Call from a catch block: keeps the key if the outcome is unknown, otherwise rotates it. */
  onError(err: unknown): void {
    if (!AttemptKey.isOutcomeUnknown(err)) this.settle()
  }

  /**
   * Network failure / timeout (no HTTP status), a 5xx, or a transient 4xx (408, 425, 429) means the charge may or may
   * not have happened. 409 is also "unknown": the server uses it for "a charge with this key is already in progress",
   * and rotating the key there would turn the next retry into a second charge. Other 4xx are definitive rejections.
   */
  static isOutcomeUnknown(err: unknown): boolean {
    const status = (err as { status?: unknown } | null)?.status
    if (typeof status !== "number") return true
    return status >= 500 || status === 408 || status === 409 || status === 425 || status === 429
  }
}
