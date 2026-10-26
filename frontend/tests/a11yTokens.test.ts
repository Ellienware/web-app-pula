import { test } from "node:test"
import assert from "node:assert/strict"

function lum(h: string) {
  const c = h.replace("#", "")
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(c.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const ratio = (a: string, b: string) => {
  const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m)
  return (x + 0.05) / (y + 0.05)
}

// Every text/background pair added to index.css for status pills, the offline banner and muted text.
const PAIRS: [string, string, string][] = [
  ["pill neutral", "#414140", "#f1f1f0"],
  ["pill success", "#1f7a1e", "#e7f7e6"],
  ["pill warning", "#8a5a00", "#fbf1dc"],
  ["pill danger", "#c62828", "#fdeceb"],
  ["pill simulated", "#414140", "#ececeb"],
  ["offline banner", "#8a5a00", "#fbf1dc"],
  ["muted text on white", "#6b6b69", "#ffffff"],
  ["refund note on white", "#595957", "#ffffff"],
]

test("every colour pair introduced for new components meets WCAG AA (4.5:1) for normal text", () => {
  for (const [name, fg, bg] of PAIRS) assert.ok(ratio(fg, bg) >= 4.5, `${name}: ${ratio(fg, bg).toFixed(2)}`)
})

test("brand green is never used as text on white (it measures below AA)", () => {
  assert.ok(ratio("#38bc37", "#ffffff") < 3, "documenting why green text uses #1f7a1e instead")
})
