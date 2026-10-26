/**
 * Plain-language content for onboarding and Help. Shared verbatim by web and mobile (pure data, no imports).
 *
 * Content rules (enforced by tests): PULA is NOT described as a bank or licensed provider, never claims to hold or move real
 * customer money, never names a bank/payment/telecom partner, and never promises returns or guarantees.
 */

export interface OnboardingStep { id: string; title: string; body: string }

export const ONBOARDING_STEPS: readonly OnboardingStep[] = [
  {
    id: "welcome",
    title: "Welcome to PULA",
    body: "Save with your circle or stokvel, shop the market, pay for taxi rides, run your business, and learn to plan your money, all in one place.",
  },
  {
    id: "demo",
    title: "Demo money only, for now",
    body: "PULA is not a bank. Right now every payment, wallet balance, refund and payout is a simulation. No real money moves, and no shop, driver or supplier is actually paid. Anything that looks paid or refunded is marked \"demo\".",
  },
  {
    id: "control",
    title: "You stay in control",
    body: "PULA always asks you to confirm before a money action. Plans and recommendations are only suggestions and never move money by themselves. If a message or offer feels rushed or too good to be true, check it in the ScamShield first.",
  },
]

export interface FaqItem { id: string; question: string; answer: string }

export const FAQ: readonly FaqItem[] = [
  {
    id: "what",
    question: "What is PULA?",
    answer: "PULA brings together savings circles and stokvels, a market for shopping and food, taxi fare payments, tools for small businesses, and money-planning help. It is a demo of how these could work together.",
  },
  {
    id: "real-money",
    question: "Is the money in PULA real?",
    answer: "No. PULA currently runs on simulated payments. Wallet balances, payments, refunds, settlements and payouts are demo records. Nothing is charged to a real account and nothing is paid out to a real account.",
  },
  {
    id: "bank",
    question: "Is PULA a bank?",
    answer: "No. PULA is not a bank and does not hold or safeguard real customer funds.",
  },
  {
    id: "refunds",
    question: "How do refunds work?",
    answer: "When an order is cancelled or a collective purchase falls short, PULA records a demo refund. Open Refunds to see what has been requested, recorded or is due. Because it is a simulation, nothing is sent back to a real account.",
  },
  {
    id: "confirm",
    question: "Will PULA move my money without asking?",
    answer: "No. Every money action asks you to confirm first. Plans, allocations and coach answers are suggestions only and never move money automatically.",
  },
  {
    id: "circles",
    question: "What is the difference between a circle and a stokvel?",
    answer: "A circle is a savings group with shared goals. A stokvel is a rotating or pooled savings group with its own rules for who contributes and who receives. In this demo, contributions are simulated records.",
  },
  {
    id: "collective",
    question: "What is a collective purchase?",
    answer: "Several small businesses commit to buying from a supplier together to reach a better price. Each business has its own allocation and pays its own share. The price is worked out by the server, not on your phone.",
  },
  {
    id: "branches",
    question: "How do branch permissions work?",
    answer: "A business owner can add branches and give a manager or staff member specific permissions, such as managing stock or seeing orders. The server checks these on every request, so hiding a button is never the only protection.",
  },
  {
    id: "coach",
    question: "What does the AI coach do?",
    answer: "It explains your plan in plain language. It is not financial advice, it cannot move money, and if the AI service is unavailable a built-in answer is shown instead.",
  },
  {
    id: "language",
    question: "Why is some text still in English?",
    answer: "Only some screens are translated so far. We keep financial, legal and safety wording in English until a fluent speaker has reviewed it, so nothing important is mistranslated.",
  },
]

export interface ScamTip { id: string; tip: string }

export const SCAM_TIPS: readonly ScamTip[] = [
  { id: "pressure", tip: "Be careful when someone rushes you to pay or says an offer ends in minutes." },
  { id: "advance-fee", tip: "Never pay a fee to receive a prize, loan or payout." },
  { id: "pin", tip: "Never share your PIN, password or one-time codes, even with someone who says they work for PULA." },
  { id: "verify", tip: "Check a business or driver inside PULA before you pay, and use the ScamShield on any message you are unsure about." },
  { id: "returns", tip: "Treat promises of guaranteed or very high returns as a warning sign." },
]

/** Words that must never appear in onboarding/help copy unless they are being negated (see tests). */
export const FORBIDDEN_CLAIMS: readonly RegExp[] = [
  /licen[cs]ed/i,
  /\bguarantee[sd]?\b/i,
  /\b(FNB|MTN|Vodacom|Capitec|Standard Bank|Absa|Nedbank|Paystack|Stitch|Visa|Mastercard)\b/i,
  /\bFDIC\b|\binsured\b/i,
]
